//! Audio in and out without external tools: symphonia decodes (WAV, AIFF, FLAC, MP3, AAC/M4A, Ogg
//! Vorbis, ALAC), rubato resamples the analysis copy, hound and flacenc write the lossless output.

use anyhow::{anyhow, bail, Context, Result};
use std::path::Path;
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::errors::Error as SymErr;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

pub struct Audio {
    /// One Vec per channel, samples in [-1, 1].
    pub channels: Vec<Vec<f32>>,
    pub sample_rate: u32,
    /// Bit depth of a PCM/lossless source (16 or 24), None for lossy codecs.
    pub bits: Option<u32>,
}

impl Audio {
    pub fn duration(&self) -> f64 {
        self.channels[0].len() as f64 / self.sample_rate as f64
    }

    pub fn mono(&self) -> Vec<f32> {
        let n = self.channels.len() as f32;
        (0..self.channels[0].len()).map(|i| self.channels.iter().map(|c| c[i]).sum::<f32>() / n).collect()
    }
}

pub fn decode(path: &Path) -> Result<Audio> {
    let file = std::fs::File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());
    let mut hint = Hint::new();
    if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
        hint.with_extension(ext);
    }
    let probed = symphonia::default::get_probe()
        .format(&hint, mss, &FormatOptions::default(), &MetadataOptions::default())
        .map_err(|e| anyhow!("unsupported or damaged audio file: {e}"))?;
    let mut format = probed.format;
    let track = format.default_track().ok_or_else(|| anyhow!("no audio track"))?;
    let track_id = track.id;
    let params = track.codec_params.clone();
    let mut decoder = symphonia::default::get_codecs()
        .make(&params, &DecoderOptions::default())
        .map_err(|e| anyhow!("unsupported codec: {e}"))?;
    let mut channels: Vec<Vec<f32>> = Vec::new();
    let mut sample_rate = params.sample_rate.unwrap_or(0);
    loop {
        let packet = match format.next_packet() {
            Ok(p) => p,
            Err(SymErr::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(SymErr::ResetRequired) => break,
            Err(e) => return Err(anyhow!("cannot read audio: {e}")),
        };
        if packet.track_id() != track_id {
            continue;
        }
        let buf = match decoder.decode(&packet) {
            Ok(b) => b,
            // A damaged frame in a lossy stream: skip it, like players do.
            Err(SymErr::DecodeError(_)) => continue,
            Err(e) => return Err(anyhow!("cannot decode audio: {e}")),
        };
        let spec = *buf.spec();
        sample_rate = spec.rate;
        let ch = spec.channels.count();
        if channels.is_empty() {
            channels = vec![Vec::new(); ch];
        }
        let mut sb = SampleBuffer::<f32>::new(buf.capacity() as u64, spec);
        sb.copy_interleaved_ref(buf);
        for (i, s) in sb.samples().iter().enumerate() {
            channels[i % ch].push(*s);
        }
    }
    if channels.is_empty() || channels[0].is_empty() || sample_rate == 0 {
        bail!("no audio in {}", path.display());
    }
    let codec_name = symphonia::default::get_codecs().get_codec(params.codec).map(|d| d.short_name).unwrap_or("");
    let lossless = codec_name.starts_with("pcm") || codec_name == "flac" || codec_name == "alac";
    let bits = lossless.then(|| match params.bits_per_sample {
        Some(b) if b <= 16 => 16,
        _ => 24,
    });
    Ok(Audio { channels, sample_rate, bits })
}

/// Resamples one channel (used for the 44.1 kHz analysis copy).
pub fn resample(x: &[f32], from: u32, to: u32) -> Result<Vec<f32>> {
    use rubato::{FftFixedIn, Resampler};
    if from == to {
        return Ok(x.to_vec());
    }
    let chunk = 1024;
    let mut r = FftFixedIn::<f32>::new(from as usize, to as usize, chunk, 2, 1)?;
    let delay = r.output_delay();
    let want = (x.len() as f64 * to as f64 / from as f64).round() as usize;
    let mut out = Vec::with_capacity(want + delay + chunk);
    let mut pos = 0;
    while pos + chunk <= x.len() {
        out.extend_from_slice(&r.process(&[&x[pos..pos + chunk]], None)?[0]);
        pos += chunk;
    }
    out.extend_from_slice(&r.process_partial(Some(&[&x[pos..]]), None)?[0]);
    // Flush the filter so the tail is complete.
    while out.len() < want + delay {
        out.extend_from_slice(&r.process_partial::<&[f32]>(None, None)?[0]);
    }
    Ok(out[delay..delay + want].to_vec())
}

fn quantise(x: f32, bits: u32) -> i32 {
    let max = ((1i64 << (bits - 1)) - 1) as f32;
    (x.clamp(-1.0, 1.0) * max).round() as i32
}

/// Writes .wav or .flac (by extension) at the given bit depth (16 or 24).
pub fn write(path: &Path, channels: &[Vec<f32>], sample_rate: u32, bits: u32) -> Result<()> {
    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
    let n = channels[0].len();
    let interleaved = || (0..n).flat_map(move |i| channels.iter().map(move |c| quantise(c[i], bits)));
    match ext.as_str() {
        "wav" => {
            let spec = hound::WavSpec {
                channels: channels.len() as u16,
                sample_rate,
                bits_per_sample: bits as u16,
                sample_format: hound::SampleFormat::Int,
            };
            let mut w = hound::WavWriter::create(path, spec)?;
            for s in interleaved() {
                w.write_sample(s)?;
            }
            w.finalize()?;
        }
        "flac" => {
            use flacenc::component::BitRepr;
            use flacenc::error::Verify;
            let samples: Vec<i32> = interleaved().collect();
            let config = flacenc::config::Encoder::default().into_verified().map_err(|e| anyhow!("flac config: {e:?}"))?;
            let source = flacenc::source::MemSource::from_samples(&samples, channels.len(), bits as usize, sample_rate as usize);
            let stream = flacenc::encode_with_fixed_block_size(&config, source, config.block_size)
                .map_err(|e| anyhow!("flac encode: {e:?}"))?;
            let mut sink = flacenc::bitsink::ByteSink::new();
            stream.write(&mut sink).map_err(|e| anyhow!("flac write: {e:?}"))?;
            let mut bytes = sink.as_slice().to_vec();
            // flacenc puts the (shorter) last frame's size into STREAMINFO's minimum block size.
            // In a fixed-blocksize stream the last block does not count; with the wrong minimum,
            // decoders (symphonia, `flac -t`) miscount sample numbers. Set min = max.
            if bytes.len() > 12 && &bytes[..4] == b"fLaC" {
                let (min, max) = bytes.split_at_mut(10);
                min[8..10].copy_from_slice(&max[..2]);
            }
            std::fs::write(path, bytes)?;
        }
        _ => bail!("output must be .wav or .flac (lossless), got {}", path.display()),
    }
    Ok(())
}

/// Chromaprint fingerprint (raw, like `fpcalc -raw -length 0`), from the decoded audio.
pub fn fingerprint(a: &Audio) -> Result<Vec<i64>> {
    use rusty_chromaprint::{Configuration, Fingerprinter};
    let mut p = Fingerprinter::new(&Configuration::preset_test2());
    p.start(a.sample_rate, a.channels.len() as u32).map_err(|e| anyhow!("fingerprint: {e:?}"))?;
    let n = a.channels[0].len();
    let mut buf: Vec<i16> = Vec::with_capacity(65536);
    let mut i = 0;
    while i < n {
        buf.clear();
        for k in i..(i + 16384).min(n) {
            for c in &a.channels {
                buf.push((c[k].clamp(-1.0, 1.0) * 32767.0).round() as i16);
            }
        }
        p.consume(&buf);
        i += 16384;
    }
    p.finish();
    Ok(p.fingerprint().iter().map(|&v| v as i64).collect())
}
