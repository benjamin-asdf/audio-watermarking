#!/usr/bin/env bash
## End-to-end check of the self-contained wmark CLI: several input formats and sample rates, lossless
## outputs, and reading the mark back from lossy clips. ffmpeg only makes the test inputs/attacks.
## Usage: tests/e2e.sh <44.1k wav> [workdir]
set -euo pipefail
WMARK="$(dirname "$0")/../target/release/wmark"
SRC="$1"
D="${2:-$(mktemp -d)}"
mkdir -p "$D"
P=a1b2c3d4e5f60718293a4b5c
ff() { ffmpeg -nostdin -loglevel error -y "$@"; }
hit() { jq -r --arg p "$P" '[.candidates[] | select(.payload == $p) | .rank][0] // "MISS"'; }

ff -i "$SRC" -t 30 "$D/in-44k.wav"
ff -i "$SRC" -t 30 -ar 48000 -c:a pcm_s24le "$D/in-48k24.wav"
ff -i "$SRC" -t 30 -c:a flac "$D/in.flac"
ff -i "$SRC" -t 30 -b:a 320k "$D/in.mp3"
ff -i "$SRC" -t 30 -c:a aac -b:a 256k "$D/in.m4a"

fail=0
for f in in-44k.wav in-48k24.wav in.flac in.mp3 in.m4a; do
  for o in wav flac; do
    out="$D/${f%.*}-${f##*.}.marked.$o"
    t0=$(date +%s.%N)
    rep=$("$WMARK" embed "$D/$f" "$out" "$P") || { echo "$f -> $o: $rep"; fail=1; continue; }
    t1=$(date +%s.%N)
    v=$(jq -r '.verified' <<<"$rep")
    sr=$(ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate,bits_per_raw_sample,bits_per_sample -of csv=p=0 "$out" | tr '\n' ' ')
    ## Attacks on the marked file: 15 s MP3 128k clip from 8 s, and 2% faster.
    ff -ss 8 -t 15 -i "$out" -b:a 128k "$D/clip.mp3"
    ff -i "$out" -filter:a atempo=1.02 -b:a 192k "$D/tempo.mp3"
    c=$("$WMARK" detect "$D/clip.mp3" | hit)
    tt=$("$WMARK" detect "$D/tempo.mp3" | hit)
    printf '%-14s -> %-4s verified=%-5s out=[%s] mp3-clip rank=%-4s tempo+2%% rank=%-4s embed=%.1fs\n' "$f" "$o" "$v" "$sr" "$c" "$tt" "$(awk -v a="$t0" -v b="$t1" 'BEGIN{print b-a}')"
    [ "$v" = true ] && [ "$c" != MISS ] && [ "$tt" != MISS ] || fail=1
  done
done
u=$("$WMARK" detect "$D/in.mp3" | hit)
echo "unmarked original: $u (want MISS)"
[ "$u" = MISS ] || fail=1
exit $fail
