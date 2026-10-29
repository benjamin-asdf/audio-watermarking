# Earmark engine — design (clean-room, literature-based)

Goal: replace audiowmark (GPL-3) with our own engine under a permissive licence, at least as robust
on `engine/bench`, and closing audiowmark's gap on tempo/pitch changes.

Clean-room rule: written from the published literature below and our own ideas. audiowmark is used
only as a black-box benchmark baseline; its source is not read.

## Literature it builds on

- Bender, Gruhl, Morimoto, Lu (1996). *Techniques for data hiding.* IBM Systems Journal 35(3/4).
  Patchwork and spread-spectrum data hiding in audio.
- Cox, Kilian, Leighton, Shamoon (1997). *Secure spread spectrum watermarking for multimedia.*
  IEEE TIP 6(12).
- Kirovski, Malvar (2003). *Spread-spectrum watermarking of audio signals.* IEEE TSP 51(4).
  Log-magnitude domain chips, chip redundancy, block-level sync search, cepstral pre-filtering at the
  detector.
- Malvar, Florêncio (2003). *Improved spread spectrum: a new modulation technique for robust
  watermarking.* IEEE TSP 51(4). Host-interference cancellation (ISS).
- Seshadri, Sundberg (1994). *List Viterbi decoding algorithms with applications.* IEEE TCOM 42.
- Viterbi (1967); standard K=7 rate-1/3 convolutional codes (textbook, e.g. Lin & Costello).

Patents on the 1996–2003 techniques have expired (20-year term). Do a proper check before launch.

## Our design choices

1. **Signal model.** Analysis on a mono downmix resampled to 44.1 kHz. The watermark is a gain
   change of time-frequency tiles. The same per-sample gain is applied to every channel of the
   original at its own sample rate, so stereo image and full band are preserved.
2. **Tiles on a log-frequency grid.** 1/6-octave bands from ~700 Hz to ~6 kHz (inside what MP3,
   AAC, Opus 64k and an 8 kHz low-pass keep). A ±0.5-semitone pitch shift moves energy by about
   1/4 band, so soft decoding tolerates it even before any search. Tile time width: 4 STFT hops
   (~46 ms at hop 512).
3. **Chips.** Each tile carries ±δ dB of log-magnitude change. Coded bits are spread over several
   tiles chosen by a keyed PRNG over the whole block (interleaving), so local damage (a transient,
   a dropout) only costs a few chips per bit.
4. **ISS host-interference cancellation** (Malvar–Florêncio): subtract the host's own projection
   onto the chip pattern while embedding, so loud-but-uncorrelated music does not drown the mark.
   This is the main robustness gain over plain additive spread spectrum.
5. **Pilot instead of sync blocks.** A quarter of the tiles in each block carry a known
   pseudo-random pilot. The detector correlates the pilot against the log-spectrogram with FFTs
   along time, which finds block starts quickly. The same correlation is run over a small grid of
   time-scale (tempo ±3%) and frequency-scale (pitch ±3%) factors. This is our answer to tempo,
   pitch and speed changes (audiowmark: 0% on those).
6. **Coding.** 128-bit payload, tail-biting convolutional code K=7 rate 1/3, soft-decision
   **list** Viterbi. The server's 61-bit HMAC tag (see `server/src/wm/payload.clj`) turns list
   decoding into a strong check: the detector returns the L best candidates and the server keeps
   the one whose tag verifies. False-accept probability ≈ L · 2^-61.
7. **Soft combining.** When a clip holds several blocks, soft bit metrics are summed before decoding.
8. **Silence gate.** Tiles in near-silent frames are not modulated (inaudibility, and nothing to
   recover there anyway).

## Block layout (initial; tuned by benchmark)

- 16 bands × 128 tile columns = 2048 tiles ≈ 5.9 s per block, so a 10 s clip holds a full block.
- 512 pilot tiles, 1536 data tiles → 384 coded bits × 4 chips.

## Prototype → product

1. Python/numpy prototype `engine/bench/earmark_proto.py`, registered as engine `earmark` in the
   benchmark, iterated on gamelap.
2. When it matches or beats audiowmark: port to Rust inside `engine/wmark` (pure Rust: `rustfft`,
   no C++), with the prototype as the reference for test vectors. Then compile to WASM for in-browser
   detection.
3. Relicense `engine/wmark` (currently GPL-3.0-or-later because it wraps audiowmark).

## Status 2026-10-28 (prototype v2, 96-bit payload)

Benchmark (10 tracks × 30 s, `bench/report.md`): 100% exact recovery on all codec, noise, gain,
low-pass, resample attacks **and on tempo ±2% / pitch +0.5 st** (audiowmark 0%); re-record sim 90%;
10 s crop 70% (audiowmark 100%). Mean SNR 29.3 dB (audiowmark 32.3). Detect ~1 s per 30 s (numpy).

What moved the numbers, in order: fixing the silence gate (was disabling 48–75% of cells);
small independent cells instead of large tiles; multi-block chunks with coarse-to-fine tempo search;
decoding several sync hypotheses (safe because the server's HMAC tag filters candidates);
96-bit payload (+33% chips per coded bit). Tried and dropped: band down to 300 Hz (−3 dB SNR for
little gain), loudness weighting (no gain on top of 96 bits).

Open: 10 s crops (blocks are 5.9 s, so a 10 s clip has < 2 blocks of pilot), listening test,
Rust port.
