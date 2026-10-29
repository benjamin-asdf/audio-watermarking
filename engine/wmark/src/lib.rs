//! Embed and detect artist watermarks.
//!
//! audiowmark does the watermarking (128-bit payload, full band). ffmpeg decodes anything in and
//! encodes the lossless output, fpcalc computes the Chromaprint fingerprint the server uses to
//! check that a clip really is the registered recording.
//!
//! audiowmark always reports candidate patterns, even for unmarked audio. Telling a real payload
//! from noise is the server's job: real payloads carry an HMAC tag (see server `wm.payload`).

pub mod earmark;

use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::Command;

/// Paths of the external tools. Lookup order per tool: env var, next to our own executable
/// (where Tauri puts sidecars), then PATH.
#[derive(Debug, Clone)]
pub struct Tools {
    pub audiowmark: PathBuf,
    pub ffmpeg: PathBuf,
    pub ffprobe: PathBuf,
    pub fpcalc: PathBuf,
}

impl Tools {
    pub fn locate() -> Tools {
        let find = |name: &str, var: &str| -> PathBuf {
            if let Some(p) = std::env::var_os(var) {
                return PathBuf::from(p);
            }
            let exe_name = format!("{name}{}", std::env::consts::EXE_SUFFIX);
            if let Some(dir) = std::env::current_exe().ok().and_then(|e| e.parent().map(Path::to_path_buf)) {
                let p = dir.join(&exe_name);
                if p.exists() {
                    return p;
                }
            }
            PathBuf::from(exe_name)
        };
        Tools {
            audiowmark: find("audiowmark", "WMARK_AUDIOWMARK"),
            ffmpeg: find("ffmpeg", "WMARK_FFMPEG"),
            ffprobe: find("ffprobe", "WMARK_FFPROBE"),
            fpcalc: find("fpcalc", "WMARK_FPCALC"),
        }
    }
}

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

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub struct Candidate {
    pub payload: String,
    pub quality: f64,
    pub pos: String,
    pub speed: f64,
}

#[derive(Debug, Serialize)]
pub struct DetectReport {
    /// Best first, distinct payloads only.
    pub candidates: Vec<Candidate>,
    pub fingerprint: Vec<i64>,
    pub duration: f64,
}

pub const MAX_CANDIDATES: usize = 32;

fn run(cmd: &mut Command) -> Result<String> {
    let out = cmd.output().with_context(|| format!("could not start {:?}", cmd.get_program()))?;
    if !out.status.success() {
        bail!(
            "{:?} failed: {}",
            cmd.get_program(),
            String::from_utf8_lossy(&out.stderr).trim()
        );
    }
    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
}

pub fn valid_payload(hex: &str) -> bool {
    hex.len() == 32 && hex.bytes().all(|b| b.is_ascii_hexdigit())
}

/// Bit depth of the first audio stream, for keeping the artist's format on output.
fn source_bits(tools: &Tools, input: &Path) -> Result<u32> {
    let out = run(Command::new(&tools.ffprobe).args(["-v", "error", "-select_streams", "a:0",
        "-show_entries", "stream=sample_fmt,bits_per_raw_sample,bits_per_sample", "-of", "json"]).arg(input))?;
    let v: serde_json::Value = serde_json::from_str(&out)?;
    let s = v["streams"].get(0).ok_or_else(|| anyhow!("no audio stream in {}", input.display()))?;
    let as_num = |k: &str| -> u32 {
        s[k].as_u64().map(|n| n as u32).or_else(|| s[k].as_str().and_then(|t| t.parse().ok())).unwrap_or(0)
    };
    let bits = as_num("bits_per_raw_sample").max(as_num("bits_per_sample"));
    let fmt = s["sample_fmt"].as_str().unwrap_or("");
    Ok(if fmt.starts_with("s16") && bits <= 16 { 16 } else { 24 })
}

/// Any audio file -> 32-bit float WAV at its native rate and channel count.
fn decode_to_wav(tools: &Tools, input: &Path, wav: &Path) -> Result<()> {
    run(Command::new(&tools.ffmpeg).args(["-nostdin", "-v", "error", "-y", "-i"]).arg(input)
        .args(["-map", "0:a:0", "-c:a", "pcm_f32le"]).arg(wav))?;
    Ok(())
}

fn encode_output(tools: &Tools, wav: &Path, output: &Path, bits: u32) -> Result<()> {
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
    let codec: &[&str] = match (ext.as_str(), bits) {
        ("wav", 16) => &["-c:a", "pcm_s16le"],
        ("wav", _) => &["-c:a", "pcm_s24le"],
        ("aif" | "aiff", 16) => &["-c:a", "pcm_s16be"],
        ("aif" | "aiff", _) => &["-c:a", "pcm_s24be"],
        ("flac", 16) => &["-c:a", "flac", "-sample_fmt", "s16"],
        ("flac", _) => &["-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24"],
        _ => bail!("output must be .wav, .flac or .aiff (lossless), got {}", output.display()),
    };
    run(Command::new(&tools.ffmpeg).args(["-nostdin", "-v", "error", "-y", "-i"]).arg(wav)
        .args(codec).arg(output))?;
    Ok(())
}

#[derive(Deserialize)]
struct AwMatch {
    bits: String,
    quality: f64,
    pos: String,
    #[serde(default = "one")]
    speed: f64,
}

fn one() -> f64 {
    1.0
}

#[derive(Deserialize)]
struct AwResult {
    matches: Vec<AwMatch>,
}

/// Distinct payloads ordered by quality, best first.
pub fn rank_candidates(matches: Vec<Candidate>) -> Vec<Candidate> {
    let mut ms = matches;
    ms.sort_by(|a, b| b.quality.partial_cmp(&a.quality).unwrap_or(std::cmp::Ordering::Equal));
    let mut seen = std::collections::HashSet::new();
    ms.into_iter().filter(|c| seen.insert(c.payload.clone())).take(MAX_CANDIDATES).collect()
}

fn audiowmark_get(tools: &Tools, wav: &Path, speed: bool) -> Result<Vec<Candidate>> {
    let dir = tempfile::tempdir()?;
    let json = dir.path().join("get.json");
    let mut cmd = Command::new(&tools.audiowmark);
    cmd.args(["get", "-q", "--json"]).arg(&json);
    if speed {
        cmd.arg("--detect-speed");
    }
    run(cmd.arg(wav))?;
    let r: AwResult = serde_json::from_str(&std::fs::read_to_string(&json)?)?;
    Ok(rank_candidates(r.matches.into_iter()
        .map(|m| Candidate { payload: m.bits, quality: m.quality, pos: m.pos, speed: m.speed })
        .collect()))
}

#[derive(Deserialize)]
struct FpcalcOut {
    duration: f64,
    fingerprint: Vec<i64>,
}

pub fn fingerprint(tools: &Tools, input: &Path) -> Result<(Vec<i64>, f64)> {
    let out = run(Command::new(&tools.fpcalc).args(["-raw", "-json", "-length", "0"]).arg(input))?;
    let f: FpcalcOut = serde_json::from_str(&out)?;
    Ok((f.fingerprint, f.duration))
}

fn sha256_file(path: &Path) -> Result<String> {
    let bytes = std::fs::read(path)?;
    Ok(Sha256::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect())
}

pub fn embed(tools: &Tools, input: &Path, output: &Path, payload: &str) -> Result<EmbedReport> {
    if !valid_payload(payload) {
        bail!("payload must be 32 hex digits");
    }
    let payload = payload.to_ascii_lowercase();
    let bits = source_bits(tools, input)?;
    let dir = tempfile::tempdir()?;
    let src = dir.path().join("in.wav");
    let marked = dir.path().join("marked.wav");
    decode_to_wav(tools, input, &src)?;
    run(Command::new(&tools.audiowmark).args(["add", "-q"]).arg(&src).arg(&marked).arg(&payload))?;
    encode_output(tools, &marked, output, bits)?;

    let check = dir.path().join("check.wav");
    decode_to_wav(tools, output, &check)?;
    let verified = audiowmark_get(tools, &check, false)?.iter().any(|c| c.payload == payload);
    let (fingerprint, duration) = fingerprint(tools, output)?;
    Ok(EmbedReport { output: output.to_path_buf(), payload, verified, sha256: sha256_file(output)?, fingerprint, duration })
}

pub fn detect(tools: &Tools, input: &Path, speed: bool) -> Result<DetectReport> {
    let dir = tempfile::tempdir()?;
    let wav = dir.path().join("in.wav");
    decode_to_wav(tools, input, &wav)?;
    let candidates = audiowmark_get(tools, &wav, speed)?;
    let (fingerprint, duration) = fingerprint(tools, &wav)?;
    Ok(DetectReport { candidates, fingerprint, duration })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(p: &str, q: f64) -> Candidate {
        Candidate { payload: p.into(), quality: q, pos: "0:00".into(), speed: 1.0 }
    }

    #[test]
    fn ranks_and_dedups() {
        let r = rank_candidates(vec![c("a", 0.2), c("b", 1.3), c("a", 0.9)]);
        assert_eq!(r, vec![c("b", 1.3), c("a", 0.9)]);
    }

    #[test]
    fn payload_validation() {
        assert!(valid_payload("0123456789abcdef0123456789ABCDEF"));
        assert!(!valid_payload("0123"));
        assert!(!valid_payload("0123456789abcdef0123456789abcdeg"));
    }
}
