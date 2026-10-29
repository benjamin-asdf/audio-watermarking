"""Attack suite: ordinary handling a released track goes through. Each attack maps
(x float32 (n, ch), sr) -> (y, sr) at the same sample rate."""
import subprocess
import tempfile

import numpy as np
import soundfile as sf

RNG = np.random.default_rng(1234)


def ffmpeg_roundtrip(x, sr, enc_args=(), filters=None, ext="wav"):
    """Encode with ffmpeg (codec args and/or filter), decode back to sr-Hz float WAV."""
    with tempfile.TemporaryDirectory() as d:
        src, mid, dst = f"{d}/in.wav", f"{d}/mid.{ext}", f"{d}/out.wav"
        sf.write(src, x, sr, subtype="FLOAT")
        cmd = ["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", src]
        if filters:
            cmd += ["-af", filters]
        subprocess.run(cmd + list(enc_args) + [mid], check=True)
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", mid,
                        "-ar", str(sr), "-ac", str(x.shape[1]), "-c:a", "pcm_f32le", dst], check=True)
        y, _ = sf.read(dst, dtype="float32", always_2d=True)
    return y, sr


def codec(args, ext):
    return lambda x, sr: ffmpeg_roundtrip(x, sr, args, ext=ext)


def filt(f):
    return lambda x, sr: ffmpeg_roundtrip(x, sr, ["-c:a", "pcm_f32le"], filters=f)


def noise(snr_db):
    def attack(x, sr):
        p = np.mean(x ** 2)
        return (x + RNG.normal(0, np.sqrt(p / 10 ** (snr_db / 10)), x.shape)).astype(np.float32), sr
    return attack


def gain(db):
    return lambda x, sr: (np.clip(x * 10 ** (db / 20), -1, 1).astype(np.float32), sr)


def crop(seconds):
    def attack(x, sr):
        n = int(seconds * sr)
        start = int(RNG.integers(0, max(1, len(x) - n)))
        return x[start:start + n], sr
    return attack


def chain(*attacks):
    def attack(x, sr):
        for a in attacks:
            x, sr = a(x, sr)
        return x, sr
    return attack


def pitch(semitones):
    r = 2 ** (semitones / 12)
    return lambda x, sr: filt(f"asetrate={int(sr * r)},aresample={sr},atempo={1 / r:.6f}")(x, sr)


mp3_128 = codec(["-c:a", "libmp3lame", "-b:a", "128k"], "mp3")
aac_128 = codec(["-c:a", "aac", "-b:a", "128k"], "m4a")

ATTACKS = {
    "none": lambda x, sr: (x, sr),
    "mp3_128": mp3_128,
    "mp3_320": codec(["-c:a", "libmp3lame", "-b:a", "320k"], "mp3"),
    "aac_128": aac_128,
    "opus_64": codec(["-c:a", "libopus", "-b:a", "64k"], "opus"),
    "resample_22k": filt("aresample=22050,aresample=44100"),
    "gain_-6db": gain(-6),
    "gain_+6db_clip": gain(6),
    "noise_30db": noise(30),
    "crop_10s": crop(10),
    "tempo_+2pct": filt("atempo=1.02"),
    "tempo_-2pct": filt("atempo=0.98"),
    "pitch_+0.5st": pitch(0.5),
    "lowpass_8k": filt("lowpass=f=8000:poles=2,lowpass=f=8000:poles=2"),
    "rerecord_sim": chain(filt("highpass=f=120,lowpass=f=7000,aecho=0.8:0.6:23|47:0.25|0.15"), noise(25)),
    "mp3_then_aac": chain(mp3_128, aac_128),
}
