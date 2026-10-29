#!/usr/bin/env bash
## Cuts 30 s excerpts (44.1 kHz, stereo, 16 bit) from local tracks into corpus/.
## Corpus is copyrighted material: private benchmark use only, never committed.
set -euo pipefail
OUT="$(cd "$(dirname "$0")" && pwd)/corpus"
MA="$HOME/repos/musicanalysis"
mkdir -p "$OUT"

excerpt() { ## name src start_seconds
  ffmpeg -nostdin -v error -y -ss "$3" -t 30 -i "$2" -ar 44100 -ac 2 -sample_fmt s16 "$OUT/$1.wav"
}

excerpt alicante      "$MA/alicante.wav" 60
excerpt thamcut       "$MA/thamcut.wav" 60
excerpt aldebara      "$MA/aldebara.wav" 120
excerpt neuland       "$MA/d-neuland-vom-feisten-i-chaos.wav" 90
excerpt hornsound     "$MA/hornsound.wav" 90
excerpt avocado       "$MA/avocado1.wav" 120
excerpt bass-dub      "$MA/such-a-mess-kyle-watson-bass-dub.wav" 120
excerpt alle-warten   "$MA/alle-warten.wav" 150
excerpt liveset       "$MA/vom Feisten @ 44 Hertz at Kater Berlin - 12⧸12⧸25 [c7bSbvAHbMc].wav" 3600
excerpt wakan-tanka   "$HOME/repos/bjs-smoke-viz/Wim Hof & Tahir Burhan - Wakan Tanka  (Official Audio) [ZLJIC_m4kAA].wav" 60
ls "$OUT" | wc -l
