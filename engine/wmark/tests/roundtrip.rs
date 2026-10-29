//! Needs audiowmark, ffmpeg, ffprobe, fpcalc on PATH. Uses a generated test tone + noise so no
//! corpus is required.

use std::path::Path;
use std::process::Command;

const PAYLOAD: &str = "1f2e3d4c5b6a79880123456789abcdef";

fn make_test_audio(path: &Path) {
    let ok = Command::new("ffmpeg")
        .args(["-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i",
               "sine=frequency=220:duration=20,volume=0.3[a];anoisesrc=d=20:a=0.05[b];[a][b]amix=inputs=2",
               "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le"])
        .arg(path)
        .status()
        .unwrap()
        .success();
    assert!(ok);
}

fn transcode(src: &Path, dst: &Path, args: &[&str]) {
    let ok = Command::new("ffmpeg").args(["-nostdin", "-v", "error", "-y", "-i"]).arg(src).args(args).arg(dst)
        .status().unwrap().success();
    assert!(ok);
}

#[test]
fn embed_then_detect_through_mp3_and_aac() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src.wav");
    let marked = dir.path().join("marked.flac");
    make_test_audio(&src);
    let tools = wmark::Tools::locate();

    let r = wmark::embed(&tools, &src, &marked, PAYLOAD).unwrap();
    assert!(r.verified);
    assert_eq!(r.sha256.len(), 64);
    assert!(r.fingerprint.len() > 100);

    for (name, args) in [("m.mp3", &["-b:a", "128k"][..]), ("m.m4a", &["-c:a", "aac", "-b:a", "128k"][..])] {
        let clip = dir.path().join(name);
        transcode(&marked, &clip, args);
        let d = wmark::detect(&tools, &clip, false).unwrap();
        assert_eq!(d.candidates[0].payload, PAYLOAD, "{name}");
    }
}

#[test]
fn unmarked_audio_yields_only_noise_candidates() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src.wav");
    make_test_audio(&src);
    let d = wmark::detect(&wmark::Tools::locate(), &src, false).unwrap();
    assert!(d.candidates.iter().all(|c| c.payload != PAYLOAD));
}

#[test]
fn rejects_lossy_output() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src.wav");
    make_test_audio(&src);
    let err = wmark::embed(&wmark::Tools::locate(), &src, &dir.path().join("o.mp3"), PAYLOAD).unwrap_err();
    assert!(err.to_string().contains("lossless"));
}
