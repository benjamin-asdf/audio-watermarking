#!/usr/bin/env bash
## Builds the UI for the Tauri app into desktop/dist. EARMARK_API sets the server URL.
set -euo pipefail
cd "$(dirname "$0")"
npx shadow-cljs release desktop
mkdir -p ../desktop/dist
cp ../server/resources/public/index.html ../server/resources/public/style.css ../desktop/dist/
