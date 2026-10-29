# Spec — artist audio watermarking

Status: draft 2026-10-28. Decisions below were picked by Benjamin; open items are marked OPEN.

## Goal

A non-technical artist marks their track with an inaudible ID in one drag-and-drop, attaches a
public record (who, what, links, contact) to that ID, and anyone who later finds the track — even
re-encoded to MP3/AAC, trimmed, or as a clip — can upload it and see the record.

## Non-goals

- Unremovable watermarks. A determined attacker with signal-processing skills can strip any
  public-detector watermark. We aim for robustness against *ordinary* handling (transcoding,
  streaming, trimming, loudness normalisation), not against adversarial removal.
- DRM / playback blocking.
- Legal proof on its own. We provide evidence (watermark + fingerprint + timestamp proof).

## Decisions

| Topic | Decision |
|---|---|
| Watermark engine | **audiowmark** (benchmark 2026-10-28, `engine/bench/report.md`): 100% exact recovery under every codec/noise/crop/re-record attack, 128-bit payload, CPU-only, ~0.5 s detect per 30 s. Neural engines lost 10–40% even under plain AAC/Opus. Known gap: tempo/pitch change (all engines 0%). |
| Embed location | Local (desktop app). Audio never leaves the artist's machine. Server issues the ID. |
| Registry | Our Postgres + OpenTimestamps anchor per registration. No blockchain/wallets. |
| Stack | Clojure backend, ClojureScript UI; same UI served as website and wrapped by Tauri for desktop. Engine is a bundled CLI sidecar. |
| License | AGPL-3.0-or-later for the project (decided 2026-10-29), engine included (own Rust engine since 2026-10-29). Revenue lives in the hosted registry (artists pay to register, lookups free), not in selling the app. |

## Payload

Target 128 bits (audiowmark native); shrink if a neural engine wins.

```
| ver 4b | work-id 63b | tag 61b |
tag = truncate(HMAC-SHA256(server-secret, ver || work-id), 60b)
```

- `work-id` is random, allocated by the server per registered work.
- `tag` lets the server reject IDs nobody was issued (invented/garbled payloads) without a DB hit.
- Detection is public by design; the watermark key is a fixed public constant.
- audiowmark reports candidate patterns even for unmarked audio. The detector returns all
  candidates; the server accepts only one whose tag validates (false-accept 2^-61 per candidate).

## Anti-impersonation: fingerprint binding

Problem: someone copies a real artist's payload into *their own* audio. Countermeasure:
at registration the desktop app also uploads a perceptual fingerprint (Chromaprint, `fpcalc`) of
the marked file — not the audio. On read, the server compares the clip's fingerprint to the stored
one and reports one of:

- **Verified** — watermark found, audio matches the registered work.
- **ID found, audio differs** — watermark present but this is not the registered recording (remix,
  sample, or copied mark). Shown with a warning.
- **No watermark found** — optionally fall back to fingerprint-only lookup.

## Flows

### Mark (desktop app, local)
1. Artist signs in (email magic link).
2. Drops WAV/FLAC/AIFF (MP3 accepted with a quality warning).
3. Fills record: artist name, title, optional ISRC, links, contact, licence note.
4. App → server: `POST /works` with record → gets `{work-id, payload}`.
5. App runs engine sidecar locally → writes `<name>.marked.wav|flac` next to the original.
6. App verifies by detecting its own output, computes fingerprint + SHA-256 of output,
   `POST /works/:id/finalize` with both.
7. Server stores, creates OpenTimestamps proof of the record hash; upgrades proof when the
   Bitcoin attestation lands (~hours).

### Read (website or app)
1. Anyone drops a file/clip (website: uploaded, processed, not stored; app: detected locally,
   only payload + fingerprint sent).
2. Result page: status (above), public record, registration date, downloadable OTS proof.

### Manage
Artist edits the public record (history kept; the OTS proof covers each version).

## Components

```
engine/      benchmark + chosen engine wrapper CLI `wmark embed|detect` (JSON on stdout)
server/      Clojure: http-kit/reitit, next.jdbc + Postgres, OTS via `ots` CLI
ui/          ClojureScript (shadow-cljs, reagent) — website + desktop
desktop/     Tauri shell; bundles `wmark` + `fpcalc` sidecars per OS
```

## Engine benchmark (first milestone, on gamelap)

- Corpus: ~10 music tracks (varied genres, 44.1 kHz), 30 s excerpts.
- Attacks: none, MP3 128k/320k, AAC 128k, Opus 64k, resample 44.1→22.05→44.1, loudness ±6 dB,
  white noise at 30 dB SNR, crop to 10 s at random offset, time-stretch ±2 %, pitch ±0.5 st,
  low-pass 8 kHz, re-recording sim (EQ + reverb + noise), chain MP3→AAC.
- Metrics: bit accuracy / exact-payload recovery rate per attack, payload capacity, quality
  (ViSQOL music mode or PEAQ-like ODG; SNR as fallback), embed/detect time per minute of audio,
  CPU-only feasibility (desktop app has no GPU guarantee), licence.
- Output: `engine/bench/results.csv` + short report → pick engine.

## Milestones

1. ~~Engine benchmark → choice.~~ audiowmark.
2. ~~`wmark` CLI~~ (`engine/wmark`, Rust; reusable as a library from Tauri).
3. ~~Server: works API, payload issuing, fingerprint match, OTS.~~ (`server/`)
4. UI: mark / read / manage pages (web).
5. Tauri desktop with sidecars; Linux build first, then macOS/Windows in CI.
6. Deploy (benjamin-schwerdtner.de subdomain OPEN), landing page, privacy policy.

## OPEN

- Product name and domain.
- Hosting target for server + Postgres.
- Account model: email magic link assumed.
- Donation/premium: README says free + donate; no paywall planned.
