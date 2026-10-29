"""What fraction of cells does the silence gate disable, and at which level percentiles?"""
import numpy as np
import soundfile as sf

import earmark_proto as em

for name in ["alicante", "bass-dub", "wakan-tanka"]:
    x, _ = sf.read(f"corpus/{name}.wav", dtype="float32", always_2d=True)
    e = em.log_cells(em.stft_power(x.mean(1), em.HOP))
    print(f"{name:12s} gated={np.mean(e < e.max() - em.SILENCE_REL_DB):.3f}")
