//! The Earmark engine (engine/DESIGN.md): a port of the Python prototype
//! `engine/bench/earmark_proto.py`, which stays the reference.
//!
//! Mark = ±δ dB gain on small time-frequency cells (23 ms × 86 Hz, 775 Hz – 4.8 kHz). Blocks of
//! 256 × 47 cells (5.94 s) repeat through the track; a quarter of the cells carry a known pilot for
//! synchronisation, the rest a K=7 rate-1/3 tail-biting convolutional code of the 96-bit payload
//! (31 chips per coded bit), with ISS host-interference cancellation at embed time.
//!
//! Analysis runs on a mono 44.1 kHz copy; the gains are applied to every channel of the original at
//! its own sample rate.

pub mod code;
pub mod detect;
pub mod dsp;
pub mod embed;
pub mod layout;

pub const SR: usize = 44_100;
pub const N_FFT: usize = 1024;
pub const HOP: usize = N_FFT / 2;
pub const FINE_HOP: usize = 128;
/// Samples (at 44.1 kHz) per time cell.
pub const TU: usize = 1024;
pub const BINS_PER_CELL: usize = 2;
pub const BIN_LO: usize = 18;
pub const BLOCK: usize = 262_144;
pub const PAYLOAD_BITS: usize = 96;
pub const CODED_BITS: usize = 3 * PAYLOAD_BITS;

pub const ALPHA: f32 = 1.0;
pub const ISS_LAMBDA: f32 = 0.8;
pub const ALPHA_MAX: f32 = 2.5;
pub const SILENCE_REL_DB: f32 = 90.0;

/// Number of frequency cells (47), from the layout.
pub fn n_f() -> usize {
    layout::get().n_f
}

/// Number of time cells per block (256), from the layout.
pub fn n_t() -> usize {
    layout::get().n_t
}

pub fn bits_from_hex(hex: &str) -> Option<Vec<u8>> {
    if hex.len() * 4 != PAYLOAD_BITS {
        return None;
    }
    let mut bits = Vec::with_capacity(PAYLOAD_BITS);
    for c in hex.chars() {
        let v = c.to_digit(16)?;
        for k in (0..4).rev() {
            bits.push(((v >> k) & 1) as u8);
        }
    }
    Some(bits)
}

pub fn hex_from_bits(bits: &[u8]) -> String {
    bits.chunks(4)
        .map(|n| {
            let v = n.iter().fold(0u32, |a, &b| (a << 1) | b as u32);
            std::char::from_digit(v, 16).unwrap()
        })
        .collect()
}
