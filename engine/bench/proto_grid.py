"""Cell-geometry experiment: for each (N_FFT, TU) run a fresh process (geometry is import-time)
and report effective delta, per-cell SNR and pilot z at the true alignment, plus decode results."""
import json
import os
import subprocess
import sys

CONFIGS = [(1024, 1024), (512, 1024), (1024, 2048), (2048, 4096)]
TRACKS = ["alicante", "bass-dub"]

WORKER = r'''
import json, sys
import numpy as np, soundfile as sf
import earmark_proto as em
from attacks import ATTACKS
out = {}
for name in sys.argv[1:]:
    x, sr = sf.read(f"corpus/{name}.wav", dtype="float32", always_2d=True)
    bits = np.random.default_rng(7).integers(0, 2, 128)
    y = em.embed(x, sr, bits)
    seg = slice(0, em.P)
    ph, pm = (em.log_cells(em.stft_power(s.mean(1), em.FINE_HOP)) for s in (x, y))
    w = em.whiten(pm, em.FPT)
    g = em.fold(w[seg], 0, 1.0)
    z, o = em.pilot_z(g)
    d = em.fold((pm - ph)[seg], 0, 1.0)
    eff = float((d[(o + em._PT * em.FPT) % em.P, em._PF] * em.PILOT_S).mean())
    v = g[(o + em._PT * em.FPT) % em.P, em._PF] * em.PILOT_S
    r = {"chips": em.CHIPS, "eff_db": round(eff, 3), "cell_snr": round(float(v.mean() / v.std()), 3), "z_true": round(float(z), 1)}
    snr = 10 * np.log10(np.sum(x ** 2) / np.sum((y - x) ** 2))
    r["snr_db"] = round(float(snr), 1)
    for a in ("none", "mp3_128", "crop_10s"):
        c, _ = em.detect_list(*ATTACKS[a](y, sr))
        r[a] = next((i for i, cc in enumerate(c) if np.array_equal(cc, bits)), None)
    out[name] = r
print("RESULT " + json.dumps(out))
'''

for nfft, tu in CONFIGS:
    env = dict(os.environ, EM_NFFT=str(nfft), EM_TU=str(tu))
    p = subprocess.run([sys.executable, "-W", "ignore", "-c", WORKER, *TRACKS], env=env,
                       capture_output=True, text=True)
    line = next((l for l in p.stdout.splitlines() if l.startswith("RESULT ")), None)
    print(f"nfft={nfft} tu={tu}:", line[7:] if line else p.stderr[-400:], flush=True)
