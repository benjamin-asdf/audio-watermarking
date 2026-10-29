"""Runs every engine over corpus/*.wav x every attack. Resumable: rows already in
results/<engine>.csv are skipped. Marked files go to work/<engine>/ for listening tests.

usage: bench.py [engine ...]   WM_ATTACKS=a,b limits the attack set.
"""
import csv
import os
import random
import sys
import time
import traceback
from pathlib import Path

import numpy as np
import soundfile as sf

from attacks import ATTACKS
from engines import ENGINES

HERE = Path(__file__).parent
FIELDS = ["engine", "track", "attack", "bits", "detected", "exact", "bit_acc",
          "snr_db", "embed_s", "detect_s", "error"]


def results_file(engine):
    return HERE / "results" / f"{engine}.csv"


def done_keys(engine):
    RESULTS = results_file(engine)
    if not RESULTS.exists():
        return set()
    with RESULTS.open() as f:
        return {(r["engine"], r["track"], r["attack"]) for r in csv.DictReader(f)}


def append(row):
    RESULTS = results_file(row["engine"])
    RESULTS.parent.mkdir(exist_ok=True)
    new = not RESULTS.exists()
    with RESULTS.open("a", newline="") as f:
        w = csv.DictWriter(f, FIELDS)
        if new:
            w.writeheader()
        w.writerow(row)


def snr_db(x, y):
    n = min(len(x), len(y))
    return 10 * np.log10(np.sum(x[:n] ** 2) / max(np.sum((y[:n] - x[:n]) ** 2), 1e-20))


def marked_file(engine, track_path, payload):
    """Embeds once per engine x track, cached in work/. Returns (y, sr, snr, embed_s)."""
    out = HERE / "work" / engine.name / track_path.name
    x, sr = sf.read(track_path, dtype="float32", always_2d=True)
    t0 = time.perf_counter()
    if out.exists():
        y, _ = sf.read(out, dtype="float32", always_2d=True)
        embed_s = float("nan")
    else:
        y = engine.embed(x, sr, payload)
        embed_s = time.perf_counter() - t0
        out.parent.mkdir(parents=True, exist_ok=True)
        sf.write(out, y, sr, subtype="FLOAT")
    return y, sr, snr_db(x, y), embed_s


def run_engine(name, tracks):
    done = done_keys(name)
    engine = ENGINES[name]()
    for track in tracks:
        ## Variants that share marks (e.g. audiowmark_speed) seed by their base engine name.
        seed = getattr(engine, "seed_name", name)
        payload = [random.Random(f"{seed}/{track.name}").randint(0, 1) for _ in range(engine.bits)]
        y, sr, snr, embed_s = marked_file(engine, track, payload)
        only = os.environ.get("WM_ATTACKS")
        for attack_name, attack in ATTACKS.items():
            if only and attack_name not in only.split(","):
                continue
            if (name, track.stem, attack_name) in done:
                continue
            row = dict(engine=name, track=track.stem, attack=attack_name, bits=engine.bits,
                       snr_db=round(snr, 2), embed_s=round(embed_s, 2))
            try:
                ya, sra = attack(y, sr)
                t0 = time.perf_counter()
                if hasattr(engine, "detect_list"):
                    cands = engine.detect_list(ya, sra)
                    got = payload if payload in cands else (cands[0] if cands else None)
                else:
                    got = engine.detect(ya, sra)
                row["detect_s"] = round(time.perf_counter() - t0, 2)
                row["detected"] = got is not None
                acc = np.mean(np.array(got) == np.array(payload)) if got else 0.0
                row["bit_acc"] = round(float(acc), 4)
                row["exact"] = bool(got == payload)
            except Exception as e:
                row["error"] = f"{type(e).__name__}: {e}"[:200]
                traceback.print_exc()
            append(row)
            print(name, track.stem, attack_name, row.get("exact"), row.get("bit_acc"), flush=True)


def main():
    names = sys.argv[1:] or list(ENGINES)
    tracks = sorted((HERE / "corpus").glob("*.wav"))
    for name in names:
        try:
            run_engine(name, tracks)
        except Exception:
            print(f"ENGINE FAILED: {name}", flush=True)
            traceback.print_exc()


if __name__ == "__main__":
    main()
