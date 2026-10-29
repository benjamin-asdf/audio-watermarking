"""Embed + detect once per engine on one 30 s track, no attack. usage: smoke.py [engine ...]"""
import random
import sys
import time
import traceback
from pathlib import Path

import soundfile as sf

from bench import snr_db
from engines import ENGINES

track = sorted((Path(__file__).parent / "corpus").glob("*.wav"))[0]
x, sr = sf.read(track, dtype="float32", always_2d=True)
for name in sys.argv[1:] or ENGINES:
    try:
        e = ENGINES[name]()
        payload = [random.randint(0, 1) for _ in range(e.bits)]
        t0 = time.perf_counter()
        y = e.embed(x, sr, payload)
        t1 = time.perf_counter()
        got = e.detect(y, sr)
        t2 = time.perf_counter()
        print(f"SMOKE {name}: exact={got == payload} snr={snr_db(x, y):.1f}dB "
              f"embed={t1 - t0:.1f}s detect={t2 - t1:.1f}s", flush=True)
    except Exception:
        print(f"SMOKE {name}: FAILED", flush=True)
        traceback.print_exc()
