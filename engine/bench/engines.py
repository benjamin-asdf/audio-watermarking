"""Watermark engine adapters with a common interface.

Every engine exposes `bits` (payload capacity), `embed(x, sr, payload) -> y` and
`detect(x, sr) -> payload | None`, where x/y are float32 arrays shaped (samples, channels)
at 44.1 kHz and payload is a list of 0/1 ints.

The 16 kHz neural engines are made full-band by computing the watermark residual on a 16 kHz
mono downmix, upsampling the residual and adding it to every channel of the original.
"""
import os
import re
import subprocess
import tempfile
from pathlib import Path

os.environ.setdefault("NO_TORCH_COMPILE", "1")  ## torch 2.2 has no Dynamo on Python 3.12

import numpy as np
import soundfile as sf
import torch
import torchaudio.functional as AF

DEVICE = os.environ.get("WM_DEVICE", "cuda" if torch.cuda.is_available() else "cpu")


def bits_hex(bits):
    return "".join("%x" % int("".join(map(str, bits[i:i + 4])), 2) for i in range(0, len(bits), 4))


def hex_bits(h):
    return [int(b) for c in h for b in format(int(c, 16), "04b")]


def resample(x, sr_from, sr_to):
    t = torch.from_numpy(np.ascontiguousarray(x.T))
    return AF.resample(t, sr_from, sr_to).numpy().T


def add_residual_16k(x, sr, mark_16k_mono):
    """mark_16k_mono: fn(mono16k np.float32) -> watermarked mono16k. Returns full-band x + residual."""
    mono = x.mean(axis=1, keepdims=True)
    m16 = resample(mono, sr, 16000)[:, 0].astype(np.float32)
    residual16 = (mark_16k_mono(m16) - m16)[:, None]
    residual = resample(residual16, 16000, sr)[: len(x)]
    if len(residual) < len(x):
        residual = np.pad(residual, ((0, len(x) - len(residual)), (0, 0)))
    return (x + residual).astype(np.float32)


def to_mono_16k(x, sr):
    return resample(x.mean(axis=1, keepdims=True), sr, 16000)[:, 0].astype(np.float32)


class Audiowmark:
    name = "audiowmark"
    bits = 128
    get_args = ()

    def _run(self, *args):
        return subprocess.run(["audiowmark", *args], check=True, capture_output=True, text=True)

    def embed(self, x, sr, payload):
        with tempfile.TemporaryDirectory() as d:
            src, dst = f"{d}/in.wav", f"{d}/out.wav"
            sf.write(src, x, sr, subtype="FLOAT")
            self._run("add", src, dst, bits_hex(payload))
            y, _ = sf.read(dst, dtype="float32", always_2d=True)
        return y

    def detect(self, x, sr):
        with tempfile.TemporaryDirectory() as d:
            src = f"{d}/in.wav"
            sf.write(src, x, sr, subtype="FLOAT")
            out = self._run("get", *self.get_args, src).stdout
        ## Lines look like: "pattern  all <32 hex> <quality> <error> ..." -- prefer the "all" vote.
        best = None
        for line in out.splitlines():
            m = re.match(r"pattern\s+(\S+)\s+([0-9a-f]{32})", line)
            if m and (best is None or m.group(1) == "all"):
                best = m.group(2)
        return hex_bits(best) if best else None


class AudiowmarkSpeed(Audiowmark):
    """Same marks, detector also searches playback-speed changes (slower detection)."""
    name = "audiowmark_speed"
    seed_name = "audiowmark"
    get_args = ("--detect-speed",)


class AudioSealEngine:
    name = "audioseal"
    bits = 16

    def __init__(self):
        from audioseal import AudioSeal
        self.gen = AudioSeal.load_generator("audioseal_wm_16bits").to(DEVICE).eval()
        self.det = AudioSeal.load_detector("audioseal_detector_16bits").to(DEVICE).eval()

    @torch.no_grad()
    def embed(self, x, sr, payload):
        msg = torch.tensor([payload], device=DEVICE)

        def mark(m16):
            w = torch.from_numpy(m16)[None, None].to(DEVICE)
            return (w + self.gen.get_watermark(w, 16000, message=msg))[0, 0].cpu().numpy()

        return add_residual_16k(x, sr, mark)

    @torch.no_grad()
    def detect(self, x, sr):
        w = torch.from_numpy(to_mono_16k(x, sr))[None, None].to(DEVICE)
        prob, msg = self.det.detect_watermark(w, 16000)
        return [int(b) for b in msg[0].tolist()] if prob > 0.5 else None


class WavMarkEngine:
    name = "wavmark"
    bits = 16  ## wavmark uses 16 of its 32 bits per second as a sync pattern

    def __init__(self):
        import wavmark
        self.wm = wavmark
        self.model = wavmark.load_model().to(DEVICE)

    def embed(self, x, sr, payload):
        def mark(m16):
            y, _ = self.wm.encode_watermark(self.model, m16, np.array(payload), show_progress=False)
            return y.astype(np.float32)

        return add_residual_16k(x, sr, mark)

    def detect(self, x, sr):
        payload, _ = self.wm.decode_watermark(self.model, to_mono_16k(x, sr), show_progress=False)
        return None if payload is None else [int(b) for b in payload]


class SilentCipherEngine:
    name = "silentcipher"
    bits = 40  ## 5 bytes
    ## Phase-shift search finds marks in cropped audio but costs ~95 s per 30 s clip on gamelap.
    phase_shift = os.environ.get("WM_SC_PHASE_SHIFT") == "1"

    def __init__(self):
        import silentcipher
        from huggingface_hub import snapshot_download
        ckpt = Path(snapshot_download("sony/silentcipher")) / "44_1_khz" / "73999_iteration"
        self.model = silentcipher.get_model(model_type="44.1k", ckpt_path=str(ckpt),
                                            config_path=str(ckpt / "hparams.yaml"), device=DEVICE)

    @staticmethod
    def _bytes(payload):
        return [int("".join(map(str, payload[i:i + 8])), 2) for i in range(0, 40, 8)]

    def embed(self, x, sr, payload):
        mono = x.mean(axis=1)
        y, _ = self.model.encode_wav(mono, sr, self._bytes(payload))
        residual = (np.asarray(y)[: len(mono)] - mono)[:, None]
        return (x + residual).astype(np.float32)

    def detect(self, x, sr):
        r = self.model.decode_wav(x.mean(axis=1), sr, phase_shift_decoding=self.phase_shift)
        if r.get("status") and r.get("messages"):
            return [int(b) for byte in r["messages"][0] for b in format(int(byte), "08b")]
        return None


class EarmarkProto:
    """Our clean-room engine (engine/DESIGN.md). Returns a candidate list; the product keeps the
    candidate whose server HMAC tag verifies, so the benchmark counts a hit if the payload is in it."""
    name = "earmark"

    def __init__(self):
        import earmark_proto
        self.em = earmark_proto
        self.bits = earmark_proto.PAYLOAD_BITS

    def embed(self, x, sr, payload):
        return self.em.embed(x, sr, np.array(payload))

    def detect_list(self, x, sr):
        cands, _ = self.em.detect_list(x, sr)
        return [[int(b) for b in c] for c in cands]

    def detect(self, x, sr):
        c = self.detect_list(x, sr)
        return c[0] if c else None


ENGINES = {
    "audiowmark": Audiowmark,
    "audiowmark_speed": AudiowmarkSpeed,
    "earmark": EarmarkProto,
    "audioseal": AudioSealEngine,
    "wavmark": WavMarkEngine,
    "silentcipher": SilentCipherEngine,
}
