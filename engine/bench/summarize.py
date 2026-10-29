"""results/*.csv -> report.md: exact-payload recovery rate per engine x attack, plus quality/speed."""
from pathlib import Path

import pandas as pd

HERE = Path(__file__).parent
df = pd.concat(pd.read_csv(f) for f in sorted((HERE / "results").glob("*.csv")))
df["exact"] = df["exact"].astype(str).eq("True")

recovery = df.pivot_table(index="attack", columns="engine", values="exact", aggfunc="mean")
recovery = (recovery * 100).round(0).astype("Int64").astype(str) + "%"
per_engine = df.groupby("engine").agg(
    payload_bits=("bits", "first"),
    snr_db_mean=("snr_db", "mean"),
    snr_db_min=("snr_db", "min"),
    embed_s_per_30s=("embed_s", "mean"),
    detect_s_per_30s=("detect_s", "mean"),
    errors=("error", lambda s: s.notna().sum()),
).round(2)

n_tracks = df["track"].nunique()
out = [f"# Engine benchmark\n\n{n_tracks} tracks x 30 s, exact-payload recovery rate:\n",
       recovery.to_markdown(), "\n\n## Quality and speed\n", per_engine.to_markdown(), "\n"]
(HERE / "report.md").write_text("\n".join(out))
print("\n".join(out))
