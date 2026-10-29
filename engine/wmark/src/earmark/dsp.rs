//! STFT geometry like scipy.signal.stft(boundary="zeros", padded=True): frame t is centred at
//! sample t*hop. Log cell energies, and the prototype's local 2-D whitening
//! (scipy.ndimage.uniform_filter, mode "nearest").

use super::{BINS_PER_CELL, BIN_LO};
use realfft::num_complex::Complex32;
use realfft::RealFftPlanner;

/// Periodic Hann (scipy's default for spectral analysis).
pub fn hann(n: usize) -> Vec<f32> {
    (0..n).map(|i| (0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / n as f64).cos()) as f32).collect()
}

pub fn frame_count(len: usize, n_fft: usize, hop: usize) -> usize {
    let l = len + 2 * (n_fft / 2);
    let nadd = (hop - (l - n_fft) % hop) % hop;
    (l + nadd - n_fft) / hop + 1
}

/// Windowed frame t (zeros outside the signal) into buf.
fn frame(x: &[f32], t: usize, n_fft: usize, hop: usize, win: &[f32], buf: &mut [f32]) {
    let start = (t * hop) as isize - (n_fft / 2) as isize;
    for (i, b) in buf.iter_mut().enumerate() {
        let j = start + i as isize;
        *b = if j >= 0 && (j as usize) < x.len() { x[j as usize] * win[i] } else { 0.0 };
    }
}

/// Bin-power rows for bins lo..hi (exclusive), one row per frame.
pub fn power_rows(x: &[f32], n_fft: usize, hop: usize, lo: usize, hi: usize) -> (usize, Vec<f32>) {
    let win = hann(n_fft);
    let fft = RealFftPlanner::<f32>::new().plan_fft_forward(n_fft);
    let mut buf = fft.make_input_vec();
    let mut spec = fft.make_output_vec();
    let frames = frame_count(x.len(), n_fft, hop);
    let w = hi - lo;
    // scipy's stft scales by 1/sum(window). Irrelevant after whitening, except next to digital
    // silence, where the +1e-12 floor of log_cells kicks in: keep the prototype's scale there.
    let scale = 1.0 / (win.iter().map(|&v| v as f64).sum::<f64>().powi(2)) as f32;
    let mut out = vec![0f32; frames * w];
    for t in 0..frames {
        frame(x, t, n_fft, hop, &win, &mut buf);
        fft.process(&mut buf, &mut spec).unwrap();
        for k in lo..hi {
            out[t * w + k - lo] = spec[k].norm_sqr() * scale;
        }
    }
    (frames, out)
}

/// Pooling of bin power into frequency cells whose edges are scaled by freq_scale (pitch search):
/// for each cell, (bin, overlap weight) with bins taken as [k, k+1) like the embedder.
pub fn cell_pooling(n_f: usize, freq_scale: f64, n_bins: usize) -> Vec<Vec<(usize, f32)>> {
    (0..n_f)
        .map(|j| {
            let lo = (BIN_LO + BINS_PER_CELL * j) as f64 * freq_scale;
            let hi = (BIN_LO + BINS_PER_CELL * (j + 1)) as f64 * freq_scale;
            (lo.floor() as usize..=hi.ceil() as usize)
                .filter(|&k| k < n_bins)
                .filter_map(|k| {
                    let w = (hi.min(k as f64 + 1.0) - lo.max(k as f64)).max(0.0);
                    (w > 0.0).then_some((k, w as f32))
                })
                .collect()
        })
        .collect()
}

/// 10·log10 of pooled power: rows × n_f. `rows` holds bins from `bin0` on.
pub fn log_cells(rows: &[f32], frames: usize, bin0: usize, pooling: &[Vec<(usize, f32)>]) -> Vec<f32> {
    let w = rows.len() / frames.max(1);
    let n_f = pooling.len();
    let mut out = vec![0f32; frames * n_f];
    for t in 0..frames {
        let r = &rows[t * w..(t + 1) * w];
        for (j, p) in pooling.iter().enumerate() {
            let s: f32 = p.iter().map(|&(k, wt)| r[k - bin0] * wt).sum();
            out[t * n_f + j] = 10.0 * (s + 1e-12).log10();
        }
    }
    out
}

/// scipy uniform_filter1d, mode "nearest": window offsets -(size/2) ..= size-1-size/2.
fn box_axis(a: &[f32], rows: usize, cols: usize, size: usize, along_rows: bool) -> Vec<f32> {
    let (n, m) = if along_rows { (rows, cols) } else { (cols, rows) };
    let at = |line: usize, i: usize| if along_rows { i * cols + line } else { line * cols + i };
    let left = (size / 2) as isize;
    let right = (size - 1 - size / 2) as isize;
    let mut out = vec![0f32; a.len()];
    let mut ext = vec![0f64; n + size + 1];
    for line in 0..m {
        // Prefix sums over the line extended by edge values.
        ext[0] = 0.0;
        for (k, e) in (-left..n as isize + right).enumerate() {
            let i = e.clamp(0, n as isize - 1) as usize;
            ext[k + 1] = ext[k] + a[at(line, i)] as f64;
        }
        for i in 0..n {
            out[at(line, i)] = ((ext[i + size] - ext[i]) / size as f64) as f32;
        }
    }
    out
}

pub fn box_filter(a: &[f32], rows: usize, cols: usize, size_t: usize, size_f: usize) -> Vec<f32> {
    let r = box_axis(a, rows, cols, size_t, true);
    box_axis(&r, rows, cols, size_f, false)
}

/// Local 2-D whitening (prototype `whiten`).
pub fn whiten(e: &[f32], rows: usize, cols: usize, span: usize, normalise: bool) -> Vec<f32> {
    let mean = box_filter(e, rows, cols, 5 * span, 5);
    let w: Vec<f32> = e.iter().zip(&mean).map(|(a, b)| a - b).collect();
    if !normalise {
        return w;
    }
    let abs: Vec<f32> = w.iter().map(|x| x.abs()).collect();
    let local = box_filter(&abs, rows, cols, 9 * span, 5);
    w.iter().zip(&local).map(|(x, l)| (x / (l * 1.25 + 1e-3)).clamp(-3.0, 3.0)).collect()
}

/// Weighted overlap-add synthesis helper: applies per-frame complex gains and resynthesises one
/// channel. gain(t, k) gives the linear gain for bin k of frame t.
pub fn resynth(x: &[f32], n_fft: usize, hop: usize, mut gain: impl FnMut(usize, usize) -> f32) -> Vec<f32> {
    let win = hann(n_fft);
    let mut planner = RealFftPlanner::<f32>::new();
    let fwd = planner.plan_fft_forward(n_fft);
    let inv = planner.plan_fft_inverse(n_fft);
    let mut buf = fwd.make_input_vec();
    let mut spec: Vec<Complex32> = fwd.make_output_vec();
    let frames = frame_count(x.len(), n_fft, hop);
    let half = n_fft / 2;
    let len = frames * hop + n_fft;
    let mut acc = vec![0f64; len];
    let mut norm = vec![0f64; len];
    for t in 0..frames {
        frame(x, t, n_fft, hop, &win, &mut buf);
        fwd.process(&mut buf, &mut spec).unwrap();
        for (k, z) in spec.iter_mut().enumerate() {
            let g = gain(t, k);
            if g != 1.0 {
                *z *= g;
            }
        }
        // realfft needs zero imaginary parts at DC and Nyquist.
        spec[0].im = 0.0;
        spec[half].im = 0.0;
        inv.process(&mut spec, &mut buf).unwrap();
        for i in 0..n_fft {
            let p = t * hop + i;
            acc[p] += (buf[i] / n_fft as f32 * win[i]) as f64;
            norm[p] += (win[i] * win[i]) as f64;
        }
    }
    // Padded coordinates start n_fft/2 before the signal.
    (0..x.len())
        .map(|i| {
            let p = i + half;
            if norm[p] > 1e-10 { (acc[p] / norm[p]) as f32 } else { 0.0 }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_like_scipy() {
        // scipy.signal.stft(np.zeros(44100), nperseg=1024, noverlap=1024-128) -> 346 frames
        assert_eq!(frame_count(44100, 1024, 128), 346);
        assert_eq!(frame_count(44100, 1024, 512), 88);
    }

    #[test]
    fn unity_gain_reconstructs() {
        let x: Vec<f32> = (0..20000).map(|i| ((i as f32) * 0.05).sin() * 0.5).collect();
        let y = resynth(&x, 1024, 512, |_, _| 1.0);
        let err = x.iter().zip(&y).map(|(a, b)| (a - b).abs()).fold(0f32, f32::max);
        assert!(err < 1e-4, "max error {err}");
    }

    #[test]
    fn box_filter_matches_scipy() {
        // scipy.ndimage.uniform_filter1d(np.arange(6.), 4, mode="nearest")
        //   -> [0.25, 0.75, 1.5, 2.5, 3.5, 4.25]
        let a: Vec<f32> = (0..6).map(|i| i as f32).collect();
        let r = box_axis(&a, 6, 1, 4, true);
        let want = [0.25, 0.75, 1.5, 2.5, 3.5, 4.25];
        assert!(r.iter().zip(want).all(|(a, b)| (a - b).abs() < 1e-6), "{r:?}");
    }
}
