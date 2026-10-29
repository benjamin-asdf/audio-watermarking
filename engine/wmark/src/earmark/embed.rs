//! Embedding (prototype `embed`): cell gains are planned on the 44.1 kHz mono analysis copy, then
//! applied to every channel of the original at its own sample rate.

use super::{code, dsp, layout, ALPHA, ALPHA_MAX, BINS_PER_CELL, BIN_LO, HOP, ISS_LAMBDA, N_FFT, SILENCE_REL_DB, SR, TU};

/// Planned gains in dB: n_tc time cells × n_f frequency cells.
pub struct Plan {
    pub n_tc: usize,
    pub n_f: usize,
    pub delta: Vec<f32>,
}

/// mono: 44.1 kHz analysis signal. payload: PAYLOAD_BITS bits.
pub fn plan(mono: &[f32], payload: &[u8]) -> Plan {
    let l = layout::get();
    let n_f = l.n_f;
    let n = mono.len();
    let target: Vec<f32> = code::encode(payload).iter().map(|&c| 2.0 * c as f32 - 1.0).collect();

    // Host cell values in dB, 2 synthesis-hop frames per time cell.
    let bin_hi = BIN_LO + BINS_PER_CELL * n_f;
    let (frames, rows) = dsp::power_rows(mono, N_FFT, HOP, BIN_LO, bin_hi);
    let pooling = dsp::cell_pooling(n_f, 1.0, N_FFT / 2 + 1);
    let e = dsp::log_cells(&rows, frames, BIN_LO, &pooling);
    let fpt = TU / HOP;
    let n_tc = n.div_ceil(TU);
    let e_cell: Vec<f32> = (0..n_tc)
        .flat_map(|tc| {
            let e = &e;
            (0..n_f).map(move |f| {
                // Rows past the end repeat the last frame (np.pad mode="edge").
                (0..fpt).map(|k| e[(tc * fpt + k).min(frames - 1) * n_f + f]).sum::<f32>() / fpt as f32
            })
        })
        .collect();
    let host = dsp::whiten(&e_cell, n_tc, n_f, 1, false);

    let mut delta = vec![0f32; n_tc * n_f];
    let n_t = l.n_t;
    for k in 0..n_tc.div_ceil(n_t) {
        let base = k * n_t;
        for c in &l.pilot {
            if base + c.t < n_tc {
                delta[(base + c.t) * n_f + c.f] = ALPHA * c.s;
            }
        }
        for (bit, chips) in l.data.iter().enumerate() {
            let (mut sum, mut count) = (0f32, 0usize);
            for c in chips {
                if base + c.t < n_tc {
                    sum += host[(base + c.t) * n_f + c.f] * c.s;
                    count += 1;
                }
            }
            let proj = sum / count.max(1) as f32;
            let amp = (ALPHA * target[bit] - ISS_LAMBDA * proj).clamp(-ALPHA_MAX, ALPHA_MAX);
            for c in chips {
                if base + c.t < n_tc {
                    delta[(base + c.t) * n_f + c.f] = c.s * amp;
                }
            }
        }
    }
    // Silence gate: leave cells far below the loudest cell alone.
    let loudest = e_cell.iter().copied().fold(f32::NEG_INFINITY, f32::max);
    for (d, &e) in delta.iter_mut().zip(&e_cell) {
        if e < loudest - SILENCE_REL_DB {
            *d = 0.0;
        }
    }
    Plan { n_tc, n_f, delta }
}

/// Applies the plan to one channel sampled at `sr`. At 44.1 kHz this is the prototype exactly
/// (N_FFT 1024, bins 18..112 in pairs); other rates use a window of the same duration and map each
/// bin to a cell by frequency, and each frame to a time cell by time.
pub fn apply(plan: &Plan, channel: &[f32], sr: u32) -> Vec<f32> {
    let n_fft = if sr as usize == SR { N_FFT } else { ((N_FFT as f64 * sr as f64 / SR as f64) / 2.0).round() as usize * 2 };
    let hop = n_fft / 2;
    let bin_hz = sr as f64 / n_fft as f64;
    let cell_bin_hz = SR as f64 / N_FFT as f64;
    // Cell of each bin, or none.
    let bin_cell: Vec<Option<usize>> = (0..=n_fft / 2)
        .map(|k| {
            let b44 = if sr as usize == SR { k as f64 } else { k as f64 * bin_hz / cell_bin_hz };
            if b44 < BIN_LO as f64 {
                return None;
            }
            let j = ((b44 - BIN_LO as f64) / BINS_PER_CELL as f64).floor() as usize;
            (j < plan.n_f).then_some(j)
        })
        .collect();
    let mut gains: Vec<f32> = Vec::new();
    let mut last_tc = usize::MAX;
    dsp::resynth(channel, n_fft, hop, |t, k| {
        let tc = ((t * hop) as f64 * SR as f64 / sr as f64 / TU as f64).floor() as usize;
        let tc = tc.min(plan.n_tc - 1);
        if tc != last_tc {
            gains = plan.delta[tc * plan.n_f..(tc + 1) * plan.n_f].iter().map(|d| 10f32.powf(d / 20.0)).collect();
            last_tc = tc;
        }
        match bin_cell[k] {
            Some(j) => gains[j],
            None => 1.0,
        }
    })
}
