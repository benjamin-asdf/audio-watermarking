"""Quick prototype check on one corpus track: codec sanity, then embed/detect under a few attacks."""
import sys
import time

import numpy as np
import soundfile as sf

import earmark_proto as em
from attacks import ATTACKS
from bench import snr_db

rng = np.random.default_rng(7)
bits = rng.integers(0, 2, 128)
assert np.array_equal(em.list_viterbi(2.0 * em.conv_encode(bits) - 1)[0][1], bits), "viterbi"
print("viterbi ok")

track = sys.argv[1] if len(sys.argv) > 1 else "corpus/alicante.wav"
x, sr = sf.read(track, dtype="float32", always_2d=True)
t0 = time.perf_counter()
y = em.embed(x, sr, bits)
print(f"embed {time.perf_counter() - t0:.1f}s snr={snr_db(x, y):.1f}dB")
for name in (sys.argv[2].split(",") if len(sys.argv) > 2 else ["none", "mp3_128", "crop_10s", "tempo_+2pct"]):
    ya, sra = ATTACKS[name](y, sr)
    t0 = time.perf_counter()
    cands, info = em.detect_list(ya, sra)
    rank = next((i for i, c in enumerate(cands) if np.array_equal(c, bits)), None)
    ber = np.mean(cands[0] != bits) if cands else 1.0
    print(f"{name:14s} rank={rank} ber_top={ber:.2f} {time.perf_counter() - t0:.1f}s segs={info['segments']}", flush=True)
