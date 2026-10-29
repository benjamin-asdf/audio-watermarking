# Earmark (working name): audio watermarking for artists

Live: https://benjamin-schwerdtner.de/earmark/

Artists mark their tracks with an inaudible ID on their own computer and attach a public record
(who, what, links, contact). Anyone who later finds the track, even re-encoded, cut, or
re-recorded, can look it up. Spec and decisions: [SPEC.md](SPEC.md).

## Layout

| Path | What |
|---|---|
| `engine/DESIGN.md` | Our clean-room watermark engine: literature, design, status |
| `engine/bench/` | Benchmark: engines × 10 tracks × 16 attacks (runs on gamelap) |
| `engine/wmark/` | Rust crate + CLI `wmark embed/detect` (JSON). Currently wraps audiowmark; our own engine replaces its internals |
| `server/` | Clojure API: magic-link auth, works registry, payload issuing (HMAC tag), fingerprint match, OpenTimestamps |
| `ui/` | ClojureScript (shadow-cljs, reagent): website and desktop UI |
| `desktop/` | Tauri 2 shell around the UI and the `wmark` crate |
| `infra/` | Deploy: Hetzner box behind benjamin-schwerdtner.de/earmark ([infra/README.md](infra/README.md)) |

## Dev

```sh
## Postgres (user-space cluster, port 5439)
pg_ctl -D ~/.local/share/audio-watermarking/pg -o "-p 5439 -k /tmp" -l ~/.local/share/audio-watermarking/pg.log start

## engine CLI (needs audiowmark, ffmpeg, fpcalc on PATH: engine/bench/setup-gamelap.sh --tools-only)
(cd engine/wmark && cargo build --release && cargo test --release)

## server on :8090 (login links are printed to stdout in dev)
## WM_CORS_ORIGINS: `cargo tauri dev` serves the UI from http://localhost:1430
(cd server && WM_CORS_ORIGINS=http://localhost:1430,http://127.0.0.1:1430 \
   WM_WMARK_CMD=$PWD/../engine/wmark/target/release/wmark clojure -M:run)
(cd server && clojure -M:test)

## web UI → server/resources/public/js
(cd ui && npm install && npx shadow-cljs watch app)

## desktop
(cd desktop/src-tauri && cargo tauri dev)
## headless end-to-end test of the real app (Docker: WebKitGTK + Xvfb + tauri-driver):
## read a clip by drop; with a WAV and SERVER_LOG also sign in, mark by drop, register
SERVER_LOG=<dev server log> desktop/e2e/run.sh <clip.mp3> [<track.wav>]
```

Benchmark (gamelap): `engine/bench/setup-gamelap.sh`, `make-corpus.sh`, `run-all.sh`, then
`summarize.py` → `report.md`.

## Business

Open source under the AGPL-3.0-or-later (`LICENSE`); `engine/wmark` is GPL-3.0-or-later (it wraps
audiowmark), which combines with the AGPL. Revenue comes from the hosted registry: artists pay to
register tracks, looking a file up stays free. What people pay for is the shared registry and its
proofs of date, which a copy of the code does not have. The AGPL makes anyone running a modified
copy as a service publish their changes; the site links its source in the footer, as the AGPL
requires for network use.
