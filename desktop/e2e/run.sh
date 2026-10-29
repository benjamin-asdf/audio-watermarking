#!/usr/bin/env bash
## Headless end-to-end test of the real desktop app (WebKitGTK under Xvfb, driven via tauri-driver).
## Usage: desktop/e2e/run.sh <audio-to-read> [<wav-to-mark>]
##        E2E_SCRIPT=typing.mjs desktop/e2e/run.sh <audio>   (key-by-key typing check, ROUNDS, KEY_DELAY_MS)
## API: the build's EARMARK_API (default localhost:8090). Marking signs in through the dev server's
## printed login link, so it needs SERVER_LOG (the dev server's stdout log).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
AUDIO="$(realpath "$1")"
OUT="${E2E_OUT:-$HERE/out}"
mkdir -p "$OUT"
MARK_ENV=()
if [ -n "${2:-}" ]; then
  ## The marked copy is written next to the input, so the input goes into the writable out dir.
  cp "$2" "$OUT/to-mark.wav"; rm -f "$OUT/to-mark.marked.wav"
  MARK_ENV=(-e MARK_AUDIO=/out/to-mark.wav -v "${SERVER_LOG:?set SERVER_LOG}:/logs/server.log:ro")
fi
(cd "$HERE/../src-tauri" && cargo build -q)
docker image inspect earmark-desktop-e2e >/dev/null 2>&1 || docker build -q -t earmark-desktop-e2e "$HERE"
docker run --rm --network host -u "$(id -u):$(id -g)" \
  -e HOME=/tmp -e XDG_RUNTIME_DIR=/tmp \
  -e WMARK_AUDIOWMARK=/tools/bin/audiowmark -e LD_LIBRARY_PATH=/tools/lib \
  -e E2E_SCRIPT="${E2E_SCRIPT:-test.mjs}" -e KEY_DELAY_MS -e ROUNDS -e AUDIO="/audio/$(basename "$AUDIO")" "${MARK_ENV[@]}" \
  -v "$HERE/../src-tauri/target/debug/earmark-desktop:/app/earmark-desktop:ro" \
  -v "$HOME/.cargo/bin/tauri-driver:/usr/local/bin/tauri-driver:ro" \
  -v "$HOME/.local/bin:/tools/bin:ro" -v "$HOME/.local/lib:/tools/lib:ro" \
  -v "$(dirname "$AUDIO"):/audio:ro" -v "$HERE:/e2e:ro" -v "$OUT:/out" \
  earmark-desktop-e2e sh -c '
    Xvfb :99 -screen 0 1024x900x24 >/dev/null 2>&1 &
    export DISPLAY=:99
    tauri-driver --port 4444 >/out/tauri-driver.log 2>&1 &
    sleep 2
    timeout 180 node "/e2e/$E2E_SCRIPT"'
