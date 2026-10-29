//! Embed and detect Earmark watermarks, self-contained (no external tools).
//!
//! The engine (`earmark`) is our own, ported from the Python prototype; `audio` decodes, resamples,
//! writes and fingerprints. The detector returns a ranked candidate list; telling a real payload
//! from noise is the server's job: real payloads carry an HMAC tag (see server `wm.payload`).

pub mod audio;
pub mod earmark;

use anyhow::{bail, Result};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

/// Detection looks at most at this much audio (memory stays bounded for whole albums; the mark
/// repeats every 6 s anyway).
pub const MAX_DETECT_SECONDS: f64 = 120.0;

#[derive(Debug, Serialize)]
pub struct EmbedReport {
    pub output: PathBuf,
    pub payload: String,
    /// The output was re-read and the payload found again.
    pub verified: bool,
    pub sha256: String,
    pub fingerprint: Vec<i64>,
    pub duration: f64,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct Candidate {
    pub payload: String,
    /// 1 = best.
    pub rank: usize,
}

#[derive(Debug, Serialize)]
pub struct DetectReport {
    pub candidates: Vec<Candidate>,
    pub fingerprint: Vec<i64>,
    pub duration: f64,
}

pub fn valid_payload(p: &str) -> bool {
    p.len() == earmark::PAYLOAD_BITS / 4 && p.chars().all(|c| c.is_ascii_hexdigit())
}

fn sha256_file(path: &Path) -> Result<String> {
    let bytes = std::fs::read(path)?;
    Ok(Sha256::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect())
}

fn analysis_copy(a: &audio::Audio, max_seconds: Option<f64>) -> Result<Vec<f32>> {
    let mut mono = a.mono();
    if let Some(s) = max_seconds {
        mono.truncate((s * a.sample_rate as f64) as usize);
    }
    audio::resample(&mono, a.sample_rate, earmark::SR as u32)
}

fn candidates(a: &audio::Audio) -> Result<Vec<Candidate>> {
    let d = earmark::detect::detect(&analysis_copy(a, Some(MAX_DETECT_SECONDS))?);
    Ok(d.candidates.iter().enumerate().map(|(i, b)| Candidate { payload: earmark::hex_from_bits(b), rank: i + 1 }).collect())
}

/// Marks `input` (any supported format) into `output` (.wav or .flac, lossless) with the 96-bit
/// payload (24 hex digits). Keeps the sample rate, channels and (for lossless sources) bit depth.
pub fn embed(input: &Path, output: &Path, payload: &str) -> Result<EmbedReport> {
    if !valid_payload(payload) {
        bail!("payload must be {} hex digits", earmark::PAYLOAD_BITS / 4);
    }
    let payload = payload.to_ascii_lowercase();
    let src = audio::decode(input)?;
    let plan = earmark::embed::plan(&analysis_copy(&src, None)?, &earmark::bits_from_hex(&payload).unwrap());
    let marked: Vec<Vec<f32>> = src.channels.iter().map(|c| earmark::embed::apply(&plan, c, src.sample_rate)).collect();
    audio::write(output, &marked, src.sample_rate, src.bits.unwrap_or(24))?;

    let check = audio::decode(output)?;
    let verified = candidates(&check)?.iter().any(|c| c.payload == payload);
    Ok(EmbedReport {
        output: output.to_path_buf(),
        payload,
        verified,
        sha256: sha256_file(output)?,
        fingerprint: audio::fingerprint(&check)?,
        duration: check.duration(),
    })
}

pub fn detect(input: &Path) -> Result<DetectReport> {
    let a = audio::decode(input)?;
    Ok(DetectReport { candidates: candidates(&a)?, fingerprint: audio::fingerprint(&a)?, duration: a.duration() })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn payload_validation() {
        assert!(valid_payload("0123456789abcdef01234567"));
        assert!(!valid_payload("0123"));
        assert!(!valid_payload("0123456789abcdef0123456g"));
    }

    #[test]
    fn hex_roundtrip() {
        let h = "0123456789abcdef01234567";
        assert_eq!(earmark::hex_from_bits(&earmark::bits_from_hex(h).unwrap()), h);
    }
}
