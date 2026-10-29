"""Benchmark the self-contained Rust CLI (engine/wmark, `wmark embed|detect`) with the same attacks
as bench.py, without torch. Counts a hit when the payload is among the candidates (the server keeps
the one whose HMAC tag verifies), like the prototype rows in report.md.
Run from engine/bench: python bench_rust.py corpus/*.wav  -> results/earmark_rust.csv + table."""
import csv, json, subprocess, sys, tempfile, time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import soundfile as sf

import attacks

WMARK = str(Path(__file__).resolve().parents[1] / "wmark" / "target" / "release" / "wmark")
PROTO = {  ## earmark (prototype) column of report.md
    "aac_128": 100, "crop_10s": 70, "gain_+6db_clip": 100, "gain_-6db": 100, "lowpass_8k": 100,
    "mp3_128": 100, "mp3_320": 100, "mp3_then_aac": 100, "noise_30db": 100, "none": 100, "opus_64": 100,
    "pitch_+0.5st": 100, "rerecord_sim": 90, "resample_22k": 100, "tempo_+2pct": 100, "tempo_-2pct": 100}


def wmark(*args):
    r = subprocess.run([WMARK, *args], capture_output=True, text=True)
    out = json.loads(r.stdout)
    if "error" in out:
        raise RuntimeError(out["error"])
    return out


def run_track(i_path):
    i, path = i_path
    rng = np.random.default_rng(1000 + i)
    payload = "".join("%x" % v for v in rng.integers(0, 16, 24))
    rows = []
    with tempfile.TemporaryDirectory() as d:
        marked = f"{d}/marked.wav"
        t0 = time.time()
        wmark("embed", path, marked, payload)
        embed_s = time.time() - t0
        x, sr = sf.read(path, dtype="float32", always_2d=True)
        y, _ = sf.read(marked, dtype="float32", always_2d=True)
        snr = 10 * np.log10((x ** 2).sum() / (((x - y) ** 2).sum() + 1e-20))
        for name, attack in attacks.ATTACKS.items():
            a, asr = attack(y, sr)
            f = f"{d}/{name}.wav"
            sf.write(f, a, asr, subtype="FLOAT")
            t0 = time.time()
            try:
                cands = [c["payload"] for c in wmark("detect", f)["candidates"]]
                err = ""
            except RuntimeError as e:
                cands, err = [], str(e)
            rows.append({"track": Path(path).name, "attack": name, "hit": int(payload in cands),
                         "rank": (cands.index(payload) + 1) if payload in cands else 0,
                         "detect_s": round(time.time() - t0, 3), "embed_s": round(embed_s, 3),
                         "snr_db": round(float(snr), 2), "error": err})
    return rows


def main(paths):
    with ProcessPoolExecutor(max_workers=5) as ex:
        rows = [r for rs in ex.map(run_track, enumerate(paths)) for r in rs]
    out = Path(__file__).parent / "results" / "earmark_rust.csv"
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print(f"| attack | earmark (Python prototype) | earmark (Rust CLI) |\n|:--|--:|--:|")
    for name in attacks.ATTACKS:
        hits = [r["hit"] for r in rows if r["attack"] == name]
        print(f"| {name} | {PROTO.get(name, '?')}% | {round(100 * sum(hits) / len(hits))}% |")
    snr = [r["snr_db"] for r in rows if r["attack"] == "none"]
    print(f"\nSNR mean {np.mean(snr):.2f} dB, min {np.min(snr):.2f} dB; embed {np.mean([r['embed_s'] for r in rows if r['attack'] == 'none']):.2f} s "
          f"and detect {np.mean([r['detect_s'] for r in rows]):.2f} s per 30 s track; errors: {sum(1 for r in rows if r['error'])}")


if __name__ == "__main__":
    main(sys.argv[1:])
