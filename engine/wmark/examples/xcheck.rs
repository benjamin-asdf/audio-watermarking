//! Cross-check against the Python prototype (engine/bench/xcheck_rust.py). 44.1 kHz WAV only.
//!   xcheck plan <in.wav> <hex> <out.f32>     planned gains (n_tc × n_f, little endian f32)
//!   xcheck embed <in.wav> <out.wav> <hex>    float WAV out
//!   xcheck detect <in.wav>                   JSON: candidates (hex) and segments
use wmark::earmark::{bits_from_hex, detect, embed, hex_from_bits};

fn read(path: &str) -> (Vec<Vec<f32>>, u32) {
    let mut r = hound::WavReader::open(path).unwrap();
    let spec = r.spec();
    let ch = spec.channels as usize;
    let all: Vec<f32> = match spec.sample_format {
        hound::SampleFormat::Float => r.samples::<f32>().map(|s| s.unwrap()).collect(),
        hound::SampleFormat::Int => {
            let scale = (1u64 << (spec.bits_per_sample - 1)) as f32;
            r.samples::<i32>().map(|s| s.unwrap() as f32 / scale).collect()
        }
    };
    let chans = (0..ch).map(|c| all.iter().skip(c).step_by(ch).copied().collect()).collect();
    (chans, spec.sample_rate)
}

fn mono(ch: &[Vec<f32>]) -> Vec<f32> {
    (0..ch[0].len()).map(|i| ch.iter().map(|c| c[i]).sum::<f32>() / ch.len() as f32).collect()
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    match a[1].as_str() {
        "plan" => {
            let (ch, _) = read(&a[2]);
            let p = embed::plan(&mono(&ch), &bits_from_hex(&a[3]).unwrap());
            let bytes: Vec<u8> = p.delta.iter().flat_map(|v| v.to_le_bytes()).collect();
            std::fs::write(&a[4], bytes).unwrap();
            println!("{} {}", p.n_tc, p.n_f);
        }
        "embed" => {
            let (ch, sr) = read(&a[2]);
            let p = embed::plan(&mono(&ch), &bits_from_hex(&a[4]).unwrap());
            let out: Vec<Vec<f32>> = ch.iter().map(|c| embed::apply(&p, c, sr)).collect();
            let spec = hound::WavSpec { channels: ch.len() as u16, sample_rate: sr, bits_per_sample: 32, sample_format: hound::SampleFormat::Float };
            let mut w = hound::WavWriter::create(&a[3], spec).unwrap();
            for i in 0..out[0].len() {
                for c in &out {
                    w.write_sample(c[i]).unwrap();
                }
            }
            w.finalize().unwrap();
        }
        "detect" => {
            let (ch, _) = read(&a[2]);
            let t = std::time::Instant::now();
            let d = detect::detect(&mono(&ch));
            let c: Vec<String> = d.candidates.iter().map(|b| hex_from_bits(b)).collect();
            println!("{}", serde_json::json!({"candidates": c, "segments": d.segments, "ms": t.elapsed().as_millis()}));
        }
        _ => panic!("usage"),
    }
}
