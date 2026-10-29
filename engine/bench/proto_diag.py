"""Per-cell pilot SNR at fs=ts=1 for the marked track: where is the signal lost?"""
import numpy as np
import soundfile as sf

import earmark_proto as em

x, sr = sf.read("corpus/alicante.wav", dtype="float32", always_2d=True)
bits = np.random.default_rng(7).integers(0, 2, 128)
y = em.embed(x, sr, bits)
seg = slice(0, em.P)
for label, sig in (("host", x), ("marked", y)):
    power = em.stft_power(sig.mean(1), em.FINE_HOP)
    for wname, w in (("whiten", em.whiten(em.log_cells(power), em.FPT)),
                     ("raw-hp", em.whiten(em.log_cells(power), em.FPT, normalise=False))):
        g = em.fold(w[seg], 0, 1.0)
        z, o = em.pilot_z(g)
        v = g[(o + em._PT * em.FPT) % em.P, em._PF] * em.PILOT_S
        print(f"{label:6s} {wname:7s} o={o:4d} z={z:5.1f} pilot mean={v.mean():.3f} std={v.std():.3f} snr={v.mean() / v.std():.3f}")
## Embedded delta actually present in dB: compare marked vs host cell log-energies at o.
ph, pm = (em.log_cells(em.stft_power(s.mean(1), em.FINE_HOP)) for s in (x, y))
d = em.fold((pm - ph)[seg], 0, 1.0)
for o in (0, 2, 4, 2046):
    v = d[(o + em._PT * em.FPT) % em.P, em._PF] * em.PILOT_S
    print(f"true delta at o={o}: mean {v.mean():.3f} dB (embedded {em.ALPHA} dB)")
