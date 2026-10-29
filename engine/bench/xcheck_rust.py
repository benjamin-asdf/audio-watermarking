"""Cross-check the Rust port (engine/wmark/examples/xcheck.rs) against earmark_proto.py on corpus
files: (1) planned cell gains equal, (2) Python-marked audio detected by Rust, (3) Rust-marked audio
detected by Python, (4) SNR of both. Run from engine/bench: python xcheck_rust.py corpus/*.wav"""
import json, subprocess, sys, tempfile
from pathlib import Path

import numpy as np
import soundfile as sf

import earmark_proto as em

XCHECK = str(Path(__file__).resolve().parents[1] / "wmark" / "target" / "release" / "examples" / "xcheck")
rng = np.random.default_rng(7)


def rust(*args):
    return subprocess.run([XCHECK, *args], check=True, capture_output=True, text=True).stdout


def bits_hex(b):
    return "".join("%x" % int("".join(map(str, b[i:i + 4])), 2) for i in range(0, len(b), 4))


def snr(x, y):
    return 10 * np.log10((x ** 2).sum() / (((x - y) ** 2).sum() + 1e-20))


def py_plan(x, bits):
    ## Recompute the prototype's delta exactly as em.embed does, without resynthesis.
    mono = x.mean(1)
    target = 2.0 * em.conv_encode(bits) - 1.0
    e = em.log_cells(em.stft_power(mono, em.HOP))
    fpt = em.TU // em.HOP
    n_tc = int(np.ceil(len(x) / em.TU))
    e = np.pad(e, ((0, max(0, n_tc * fpt - len(e))), (0, 0)), mode="edge")[: n_tc * fpt]
    e_cell = e.reshape(n_tc, fpt, em.N_F).mean(1)
    host = em.whiten(e_cell, 1, normalise=False)
    delta = np.zeros((n_tc, em.N_F))
    pt, pf = em.cell_tf(em.PILOT)
    dt, df = em.cell_tf(em.DATA)
    for k in range(int(np.ceil(n_tc / em.N_T))):
        base = k * em.N_T
        okp = base + pt < n_tc
        delta[base + pt[okp], pf[okp]] = em.ALPHA * em.PILOT_S[okp]
        valid = base + dt < n_tc
        h = np.where(valid, host[np.minimum(base + dt, n_tc - 1), df] * em.DATA_S, 0.0)
        proj = h.sum(1) / np.maximum(valid.sum(1), 1)
        amp = np.clip(em.ALPHA * target - em.ISS_LAMBDA * proj, -em.ALPHA_MAX, em.ALPHA_MAX)
        d = em.DATA_S * amp[:, None]
        delta[base + dt[valid], df[valid]] = d[valid]
    delta[e_cell < e_cell.max() - em.SILENCE_REL_DB] = 0.0
    return delta


ok = True
for f in sys.argv[1:]:
    x, sr = sf.read(f, dtype="float32", always_2d=True)
    bits = rng.integers(0, 2, em.PAYLOAD_BITS)
    hx = bits_hex(bits)
    with tempfile.TemporaryDirectory() as d:
        n_tc, n_f = map(int, rust("plan", f, hx, f"{d}/plan.f32").split())
        rp = np.fromfile(f"{d}/plan.f32", "<f4").reshape(n_tc, n_f)
        pp = py_plan(x, bits)
        plan_err = float(np.abs(rp - pp).max())
        sf.write(f"{d}/py.wav", em.embed(x, sr, bits), sr, subtype="FLOAT")
        rust("embed", f, f"{d}/rs.wav", hx)
        y_rs, _ = sf.read(f"{d}/rs.wav", dtype="float32", always_2d=True)
        y_py, _ = sf.read(f"{d}/py.wav", dtype="float32", always_2d=True)
        r_on_py = json.loads(rust("detect", f"{d}/py.wav"))
        p_on_rs, _ = em.detect_list(y_rs, sr)
        r_on_rs = json.loads(rust("detect", f"{d}/rs.wav"))
        r_on_orig = json.loads(rust("detect", f))
    hit = lambda cands: (cands.index(hx) + 1) if hx in cands else 0
    row = {
        "file": Path(f).name,
        "plan_max_abs_diff_db": round(plan_err, 5),
        "audio_max_abs_diff": round(float(np.abs(y_rs - y_py).max()), 6),
        "snr_py": round(float(snr(x, y_py)), 2), "snr_rs": round(float(snr(x, y_rs)), 2),
        "rust_detects_py_mark_rank": hit(r_on_py["candidates"]),
        "py_detects_rust_mark_rank": hit([bits_hex(c) for c in p_on_rs]),
        "rust_detects_rust_mark_rank": hit(r_on_rs["candidates"]),
        "rust_on_unmarked_hit": hit(r_on_orig["candidates"]),
        "rust_detect_ms": r_on_rs["ms"],
    }
    ok &= row["plan_max_abs_diff_db"] < 0.01 and all(row[k] for k in ("rust_detects_py_mark_rank", "py_detects_rust_mark_rank", "rust_detects_rust_mark_rank"))
    print(json.dumps(row), flush=True)
sys.exit(0 if ok else 1)
