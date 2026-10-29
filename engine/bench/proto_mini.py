"""Mini-benchmark for prototype variants: hard tracks x hard attacks, in parallel.
usage: EM_...=... proto_mini.py  -> prints hits/total and per-attack hits."""
import os
import sys
from concurrent.futures import ProcessPoolExecutor

import numpy as np

TRACKS = ["hornsound", "liveset", "wakan-tanka", "bass-dub", "alicante"]
ATTACK_NAMES = ["none", "aac_128", "opus_64", "crop_10s", "rerecord_sim", "noise_30db", "tempo_-2pct", "pitch_+0.5st"]


def run(track):
    import soundfile as sf
    import earmark_proto as em
    from attacks import ATTACKS
    from bench import snr_db
    x, sr = sf.read(f"corpus/{track}.wav", dtype="float32", always_2d=True)
    bits = np.random.default_rng(7).integers(0, 2, em.PAYLOAD_BITS)
    y = em.embed(x, sr, bits)
    hits = {}
    for a in ATTACK_NAMES:
        c, _ = em.detect_list(*ATTACKS[a](y, sr))
        hits[a] = any(np.array_equal(cc, bits) for cc in c)
    return track, round(snr_db(x, y), 1), hits


if __name__ == "__main__":
    with ProcessPoolExecutor(len(TRACKS)) as ex:
        res = list(ex.map(run, TRACKS))
    total = sum(sum(h.values()) for _, _, h in res)
    label = " ".join(f"{k}={v}" for k, v in os.environ.items() if k.startswith("EM_")) or "default"
    print(f"{label}: {total}/{len(TRACKS) * len(ATTACK_NAMES)} snr={[s for _, s, _ in res]}")
    print("  per attack:", {a: sum(h[a] for _, _, h in res) for a in ATTACK_NAMES})
