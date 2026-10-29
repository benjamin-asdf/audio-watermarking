"""Host noise (dB) of candidate chip statistics, per band, on real music. Lower = better."""
import numpy as np
import soundfile as sf

import earmark_proto as em

for name in ["alicante", "bass-dub", "wakan-tanka"]:
    x, sr = sf.read(f"corpus/{name}.wav", dtype="float32", always_2d=True)
    p = em.fine_power(x.mean(1))
    e = 10 * np.log10(p @ em.band_matrix(em.N_FFT, em.SR).T + 1e-12)     ## (fine frames, bands)
    row = [name]
    for half in (4, 8, 16):               ## half-tile length in fine frames (x128 samples)
        n = (len(e) // (2 * half)) * 2 * half
        h = e[:n].reshape(-1, 2, half, e.shape[1]).mean(2)                 ## (tiles, 2, bands)
        row.append(f"diff{half * 128}: {np.median((h[:, 0] - h[:, 1]).std(0)):.2f}")
        ## also differential across two halves of the band? approximated by adjacent bands
    t = e[: (len(e) // 16) * 16].reshape(-1, 16, e.shape[1]).mean(1)
    row.append(f"tile-vs-movavg: {np.median((t - np.convolve(np.ones(4) / 4, np.ones(1), 'same')[0] * 0 - np.apply_along_axis(lambda c: np.convolve(c, np.ones(5) / 5, 'same'), 0, t)).std(0)):.2f}")
    row.append(f"adjacent-band diff: {np.median((t[:, 1:] - t[:, :-1]).std(0)):.2f}")
    print("  ".join(row))
