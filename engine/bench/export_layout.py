"""Writes the prototype's keyed cell layout to engine/wmark/src/earmark/layout.bin, so the Rust
engine uses exactly the same pilot/data cells and signs without re-implementing numpy's PRNG.

Format (little endian): b"EMLY", u16 N_T, u16 N_F, u16 N_PILOT, u16 CODED_BITS, u16 CHIPS,
u16[N_PILOT] pilot cells, i8[N_PILOT] pilot signs, u16[CODED_BITS*CHIPS] data cells (row-major per
coded bit), i8[CODED_BITS*CHIPS] data signs. A cell index is t * N_F + f.
Run: python export_layout.py  (from engine/bench, with the prototype's default env)."""
import struct
from pathlib import Path

import numpy as np

import earmark_proto as em

out = Path(__file__).resolve().parents[1] / "wmark" / "src" / "earmark" / "layout.bin"
out.parent.mkdir(parents=True, exist_ok=True)
with open(out, "wb") as f:
    f.write(b"EMLY")
    f.write(struct.pack("<5H", em.N_T, em.N_F, em.N_PILOT, em.CODED_BITS, em.CHIPS))
    f.write(em.PILOT.astype("<u2").tobytes())
    f.write(em.PILOT_S.astype("i1").tobytes())
    f.write(em.DATA.astype("<u2").tobytes())
    f.write(em.DATA_S.astype("i1").tobytes())
print(out, out.stat().st_size, "bytes:", dict(N_T=em.N_T, N_F=em.N_F, N_PILOT=em.N_PILOT,
      CODED_BITS=em.CODED_BITS, CHIPS=em.CHIPS, BIN_LO=em.BIN_LO, BIN_HI=em.BIN_HI))
