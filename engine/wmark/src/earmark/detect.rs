//! Detection (prototype `detect_list`): band log-energies at a fine hop → local 2-D whitening →
//! fold modulo the block period → pilot circular cross-correlation over a grid of frequency scales
//! (pitch) and time scales (tempo), coarse then fine → soft bits weighted by pilot strength, summed
//! across chunks → list Viterbi = ranked candidates.

use super::{code, dsp, layout, BINS_PER_CELL, BIN_LO, BLOCK, CODED_BITS, FINE_HOP, N_FFT, TU};
use realfft::num_complex::Complex32;
use realfft::RealFftPlanner;
use std::collections::HashSet;
use std::sync::Arc;

const P: usize = BLOCK / FINE_HOP; // fold bins per block (2048)
const FPT: usize = TU / FINE_HOP; // fold bins per time cell (8)
const MIN_SEGMENT: f64 = 0.6;
const CHUNK_BLOCKS: usize = 4;
const COARSE_KEEP: usize = 3;
const FINE_STEP: f64 = 0.0005;
/// Sync hypotheses below this pilot z are ignored; wrong ones only add candidates, which the
/// server's HMAC tag rejects.
const Z_MIN: f32 = 4.0;
const PER_DECODE: usize = 8;
pub const LIST_SIZE: usize = 64;

/// 0.97 ..= 1.03 in steps of 0.005 (13 values), as the prototype's np.round(np.arange(...), 4).
fn scales() -> Vec<f64> {
    (0..13).map(|i| ((0.97 + 0.005 * i as f64) * 1e4).round() / 1e4).collect()
}

struct Correlator {
    fwd: Arc<dyn realfft::RealToComplex<f32>>,
    inv: Arc<dyn realfft::ComplexToReal<f32>>,
    /// conj(rfft) of each pilot column.
    h: Vec<Vec<Complex32>>,
}

impl Correlator {
    fn new(n_f: usize) -> Correlator {
        let mut planner = RealFftPlanner::<f32>::new();
        let fwd = planner.plan_fft_forward(P);
        let inv = planner.plan_fft_inverse(P);
        let mut cols = vec![vec![0f32; P]; n_f];
        for c in &layout::get().pilot {
            cols[c.f][c.t * FPT] += c.s;
        }
        let h = cols
            .into_iter()
            .map(|mut col| {
                let mut s = fwd.make_output_vec();
                fwd.process(&mut col, &mut s).unwrap();
                s.iter().map(|z| z.conj()).collect()
            })
            .collect();
        Correlator { fwd, inv, h }
    }

    /// Pilot z-score and block offset of a folded segment g (P × n_f).
    fn pilot_z(&self, g: &[f32], n_f: usize) -> (f32, usize) {
        let mut acc = vec![Complex32::new(0.0, 0.0); P / 2 + 1];
        let mut col = vec![0f32; P];
        let mut s = self.fwd.make_output_vec();
        for f in 0..n_f {
            for i in 0..P {
                col[i] = g[i * n_f + f];
            }
            self.fwd.process(&mut col, &mut s).unwrap();
            for (a, (x, h)) in acc.iter_mut().zip(s.iter().zip(&self.h[f])) {
                *a += x * h;
            }
        }
        acc[0].im = 0.0;
        acc[P / 2].im = 0.0;
        let mut sc = vec![0f32; P];
        self.inv.process(&mut acc, &mut sc).unwrap();
        let mean = sc.iter().map(|&v| v as f64).sum::<f64>() / P as f64;
        let var = sc.iter().map(|&v| (v as f64 - mean).powi(2)).sum::<f64>() / P as f64;
        let std = var.sqrt() + 1e-9;
        let (o, best) = sc.iter().enumerate().fold((0, f32::NEG_INFINITY), |(bo, bv), (i, &v)| if v > bv { (i, v) } else { (bo, bv) });
        (((best as f64 - mean) / std) as f32, o)
    }
}

/// Fold whitened frames (absolute frame index start + i) into P block-phase bins, then average
/// over each time cell (circular box of FPT bins).
fn fold(w: &[f32], rows: std::ops::Range<usize>, n_f: usize, time_scale: f64) -> Vec<f32> {
    let mut s = vec![0f64; P * n_f];
    let mut c = vec![0u32; P];
    for t in rows {
        let pos = (t * FINE_HOP) as f64 * time_scale;
        let bin = ((pos % BLOCK as f64) / FINE_HOP as f64).floor() as usize % P;
        c[bin] += 1;
        for f in 0..n_f {
            s[bin * n_f + f] += w[t * n_f + f] as f64;
        }
    }
    let f_avg: Vec<f64> = (0..P * n_f).map(|i| if c[i / n_f] > 0 { s[i] / c[i / n_f] as f64 } else { 0.0 }).collect();
    let mut g = vec![0f32; P * n_f];
    for f in 0..n_f {
        let mut run: f64 = (0..FPT).map(|k| f_avg[k * n_f + f]).sum();
        for i in 0..P {
            g[i * n_f + f] = (run / FPT as f64) as f32;
            run += f_avg[((i + FPT) % P) * n_f + f] - f_avg[i * n_f + f];
        }
    }
    g
}

fn soft_bits(g: &[f32], o: usize, n_f: usize) -> Vec<f32> {
    let y: Vec<f32> = layout::get()
        .data
        .iter()
        .map(|chips| chips.iter().map(|c| g[((o + c.t * FPT) % P) * n_f + c.f] * c.s).sum())
        .collect();
    normalise(&y)
}

fn normalise(y: &[f32]) -> Vec<f32> {
    let mean = y.iter().map(|&v| v as f64).sum::<f64>() / y.len() as f64;
    let std = (y.iter().map(|&v| (v as f64 - mean).powi(2)).sum::<f64>() / y.len() as f64).sqrt() + 1e-9;
    y.iter().map(|&v| (v as f64 / std) as f32).collect()
}

struct Hyp {
    z: f32,
    scale: usize,
    o: usize,
    g: Vec<f32>,
}

fn sync_chunk(corr: &Correlator, w_by_fs: &[Vec<f32>], start: usize, stop: usize, n_f: usize) -> Vec<Hyp> {
    let coarse_ts = scales();
    let first = start..stop.min(start + P);
    let mut coarse: Vec<(f32, usize, f64)> = Vec::new();
    for (si, w) in w_by_fs.iter().enumerate() {
        for &ts in &coarse_ts {
            let (z, _) = corr.pilot_z(&fold(w, first.clone(), n_f, ts), n_f);
            coarse.push((z, si, ts));
        }
    }
    coarse.sort_by(|a, b| b.0.total_cmp(&a.0));
    let mut hyps: Vec<Hyp> = coarse
        .iter()
        .take(COARSE_KEEP)
        .map(|&(_, si, ts0)| {
            let mut best: Option<Hyp> = None;
            for k in 0..13 {
                let ts = ts0 - 0.003 + k as f64 * FINE_STEP;
                let g = fold(&w_by_fs[si], start..stop, n_f, ts);
                let (z, o) = corr.pilot_z(&g, n_f);
                if best.as_ref().is_none_or(|b| z > b.z) {
                    best = Some(Hyp { z, scale: si, o, g });
                }
            }
            best.unwrap()
        })
        .collect();
    hyps.sort_by(|a, b| b.z.total_cmp(&a.z));
    hyps
}

pub struct Detection {
    /// Candidate payloads (bits), best first.
    pub candidates: Vec<Vec<u8>>,
    /// Per chunk: (pilot z, frequency scale) of each accepted sync hypothesis.
    pub segments: Vec<Vec<(f32, f64)>>,
}

/// mono: 44.1 kHz analysis signal.
pub fn detect(mono: &[f32]) -> Detection {
    let l = layout::get();
    let n_f = l.n_f;
    let fs_list = scales();
    // Bins needed by the widest frequency scale.
    let lo = ((BIN_LO as f64) * fs_list[0]).floor() as usize;
    let hi = (((BIN_LO + BINS_PER_CELL * n_f) as f64) * fs_list[12]).ceil() as usize + 1;
    let (frames, rows) = dsp::power_rows(mono, N_FFT, FINE_HOP, lo, hi.min(N_FFT / 2 + 1));
    let w_by_fs: Vec<Vec<f32>> = fs_list
        .iter()
        .map(|&fs| {
            let e = dsp::log_cells(&rows, frames, lo, &dsp::cell_pooling(n_f, fs, hi.min(N_FFT / 2 + 1)));
            dsp::whiten(&e, frames, n_f, FPT, true)
        })
        .collect();
    let corr = Correlator::new(n_f);
    let span = CHUNK_BLOCKS * P;
    let mut starts: Vec<usize> = (0..frames)
        .step_by(span)
        .filter(|&s| ((frames.min(s + span) - s) as f64) >= MIN_SEGMENT * P as f64)
        .collect();
    if starts.is_empty() {
        starts.push(0);
    }
    let mut total = vec![0f32; CODED_BITS];
    // (metric, bits, z) with z = inf for the combined decode, which wins ties.
    let mut decoded: Vec<(f32, Vec<u8>, f32)> = Vec::new();
    let mut segments = Vec::new();
    for s in starts {
        let hyps: Vec<Hyp> = sync_chunk(&corr, &w_by_fs, s, frames.min(s + span), n_f).into_iter().filter(|h| h.z >= Z_MIN).collect();
        segments.push(hyps.iter().map(|h| (h.z, fs_list[h.scale])).collect());
        for (i, h) in hyps.iter().enumerate() {
            let sb = soft_bits(&h.g, h.o, n_f);
            if i == 0 {
                for (t, v) in total.iter_mut().zip(&sb) {
                    *t += h.z * v;
                }
            }
            decoded.extend(code::list_viterbi(&normalise(&sb)).into_iter().take(PER_DECODE).map(|(m, b)| (m, b, h.z)));
        }
    }
    if total.iter().any(|&v| v != 0.0) {
        decoded.extend(code::list_viterbi(&normalise(&total)).into_iter().take(PER_DECODE).map(|(m, b)| (m, b, f32::INFINITY)));
    }
    decoded.sort_by(|a, b| b.0.total_cmp(&a.0).then(b.2.total_cmp(&a.2)));
    let mut seen = HashSet::new();
    let candidates = decoded.into_iter().filter(|(_, b, _)| seen.insert(b.clone())).map(|(_, b, _)| b).take(LIST_SIZE).collect();
    Detection { candidates, segments }
}
