"""Why does a track fail? Pilot z per chunk, energy in the mark band, gated cells, delta reached."""
import sys

import numpy as np
import soundfile as sf

import earmark_proto as em

name = sys.argv[1]
x, sr = sf.read(f"corpus/{name}.wav", dtype="float32", always_2d=True)
bits = np.random.default_rng(7).integers(0, 2, 128)
y = em.embed(x, sr, bits)
p = em.stft_power(x.mean(1), em.FINE_HOP)
tot = p.sum()
band = p[:, em.BIN_LO:em.BIN_HI].sum()
print(f"{name}: energy share in mark band {band / tot:.3f}; below 775 Hz {p[:, :em.BIN_LO].sum() / tot:.3f}")
e = em.log_cells(em.stft_power(x.mean(1), em.HOP))
print(f"gated cells {np.mean(e < e.max() - em.SILENCE_REL_DB):.3f}")
ph, pm = (em.log_cells(em.stft_power(s.mean(1), em.FINE_HOP)) for s in (x, y))
w = em.whiten(pm, em.FPT)
g = em.fold(w[: em.P], 0, 1.0)
z, o = em.pilot_z(g)
d = em.fold((pm - ph)[: em.P], 0, 1.0)
v = d[(o + em._PT * em.FPT) % em.P, em._PF] * em.PILOT_S
gv = g[(o + em._PT * em.FPT) % em.P, em._PF] * em.PILOT_S
print(f"first block: z={z:.1f} o={o} eff_delta={v.mean():.3f} dB cell_snr={gv.mean() / gv.std():.3f}")
raw = em.whiten(pm, em.FPT, normalise=False)
print(f"host noise (dB, robust) {np.median(np.abs(raw)) * 1.4826:.2f}")
_, info = em.detect_list(y, sr)
print("detect chunks:", info["segments"])
