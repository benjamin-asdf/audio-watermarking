# Infra

Earmark runs like summerfest: a Clojure process under systemd on the Hetzner box, behind the
linode nginx that terminates TLS for benjamin-schwerdtner.de.

```
browser / desktop app
  │ https://benjamin-schwerdtner.de/earmark/…
  ▼
linode (nginx, TLS)                     config: ~/repos/faster-than-light-memes/build/nginx/nginx.conf
  │ https, pinned self-signed cert       (location ^~ /earmark, deployed with `build/deploy.sh nginx`)
  ▼
Hetzner 49.12.218.29 (project "partymaker", cx23, fsn1, Ubuntu 24.04; ufw: 443 only from linode)
  nginx :443 ─ /etc/nginx/snippets/earmark-origin.conf (included in sites-enabled/summerfest)
  │ http
  ▼
  earmark.service  127.0.0.1:8097, user earmark, -Xmx512m
    ├─ Postgres 16 (shared cluster, db + role `earmark`)
    └─ subprocesses: /opt/earmark/bin/wmark → audiowmark 0.6.5, ffmpeg, fpcalc; ots (venv)
```

The app is mounted at `/earmark` (`WM_BASE_PATH`): the server strips the prefix and writes it into
the page's `<base href>`, so UI assets, API calls and client routes resolve under it.

## Files

| File | What |
|---|---|
| `deploy.env` | Targets (host, root, port, base path, public URL). No secrets. Local override: `deploy.local.env` (gitignored) |
| `bootstrap.sh` → `remote/bootstrap-origin.sh` | One-time, idempotent server setup (see below) |
| `deploy.sh` | Build UI + `wmark`, rsync, restart, wait for health (deadline 120 s) |
| `build-wmark.sh` | Builds `wmark` in `rust:1-bookworm` (needs glibc ≤ 2.36, the server has 2.39) → `build/wmark` |
| `smoke.sh` → `remote/smoke.py` | End-to-end test on the live service |
| `logs.sh` | Tail `/opt/earmark/log/app.log` |
| `remote/earmark.service` | systemd unit (all non-secret config as `Environment=`) |
| `remote/origin-earmark.conf` | Hetzner nginx location |

## Server layout

| Path | What |
|---|---|
| `/opt/earmark/code` | rsynced `server/` (incl. the release UI build in `resources/public/js`) |
| `/opt/earmark/bin/wmark` | engine CLI |
| `/opt/earmark/.env` | `WM_DB_URL` with the DB password (root:earmark 0640). Generated on the server, never leaves it |
| `/opt/earmark/data/payload-secret` | HMAC key for payload tags (earmark 0600), created on first start |
| `/opt/earmark/log/app.log` | stdout/stderr |
| `/opt/earmark/home` | `$HOME` of the service user (Maven cache) |
| `/opt/earmark/venv` | `opentimestamps-client` 0.8.0 (`ots`) |
| `/usr/local/bin/audiowmark` | built from the 0.6.5 tag, source in `/usr/local/src/audiowmark` |

**Back up `payload-secret` and the `earmark` database.** Without the secret no mark can be verified
again: every artist would have to re-mark their tracks. Not set up yet (open item below).

## Commands

```sh
infra/bootstrap.sh           # once per server; re-running is safe
infra/deploy.sh              # build + sync + restart
infra/deploy.sh --no-build   # sync + restart (server code only)
infra/smoke.sh               # login → register → embed → finalize → 15 s MP3 clip → read
infra/logs.sh
```

Desktop app pointed at production: `EARMARK_API=https://benjamin-schwerdtner.de/earmark ui/build-desktop.sh`.

## What bootstrap does

1. apt: ffmpeg, `libchromaprint-tools` (fpcalc), build deps for audiowmark (fftw, libsndfile,
   libgcrypt, zita-resampler, mpg123).
2. Builds audiowmark 0.6.5 into `/usr/local` (skipped if that version is installed).
3. System user `earmark`, the directories above.
4. venv with `opentimestamps-client==0.8.0`.
5. Postgres role + db `earmark` with a random password (passed to psql on stdin) → `.env`.
6. Installs and enables the unit.
7. Installs the nginx snippet, adds one `include` line before `location /` in
   `sites-enabled/summerfest` (backup: `/root/nginx-summerfest.pre-earmark`), `nginx -t`, reload;
   restores the backup if the test fails.

## Log

- **2026-10-28**: First deploy.
  - Bootstrap ran on the Hetzner box.
  - linode got `location ^~ /earmark` in the blog repo's `build/nginx/nginx.conf`, pushed with
    `build/deploy.sh nginx` (summerfest's pinned cert, so no new cert or ufw rule).
  - `infra/smoke.sh`: the 15 s MP3 clip read as `verified`, the unmarked original as `no-watermark`.
  - Headless browser through the public URL: `Verified`.
  - The smoke test left a work "Synthetic chord / Smoke Test" (account `smoke-test@earmark.invalid`)
    in the production DB; each smoke run adds another one.

- **2026-10-29**: Login mail over SMTP.
  - Sender `earmark@benjamin-schwerdtner.de` on `mail.benjamin-schwerdtner.de:587` (STARTTLS), the
    mail server in `~/repos/ftlm-mail` on the linode (Hetzner Cloud blocks outbound 25, not 587).
  - Password: `pass earmark-smtp-pw` → `WM_SMTP_PASSWORD` in `/opt/earmark/.env`; the rest is in the unit.
  - Addresses under `.invalid` still go to `app.log` (`LOGIN LINK for …`, never paste those lines:
    they are login tokens), so `infra/smoke.sh` keeps working.
  - `deploy.sh` now reinstalls the unit when it changed.
  - Verified: a login request for benjamin@ arrived from `Earmark <earmark@…>` with a valid DKIM
    signature; smoke test `verified` / `no-watermark`. Device sign-in and `WM_CORS_ORIGINS` code went
    live with this deploy.

- **2026-10-29**: Signed timestamps (RFC 3161) as the shown proof of date.
  - `wm.tsa` asks DigiCert (`http://timestamp.digicert.com`), falls back to FreeTSA, at finalize and on
    each edit; the 30-min job backfills versions without one. OpenTimestamps keeps running as the
    second, long-term proof. Override the TSAs with `WM_TSA_URLS` (comma separated).
  - Downloads per version: `proof.tsr`, `proof.ots`, `record.json` (the bytes both proofs cover).
    `openssl ts -verify -data record.json -in proof.tsr -CAfile /etc/ssl/certs/ca-certificates.crt`
    → OK for the demo work (a FreeTSA proof needs FreeTSA's own CA certificate instead).
  - All 4 final works in production were backfilled within a minute of the deploy.

## Mail

| | |
|---|---|
| Transport | `wm.mail` (Jakarta Mail / Angus 2.0.5), chosen by `WM_SMTP_HOST`; unset = log only (dev) |
| Config | unit: `WM_SMTP_HOST`, `WM_SMTP_PORT`, `WM_SMTP_USER`, `WM_MAIL_FROM`; `.env`: `WM_SMTP_PASSWORD` |
| Rotate the password | `pass generate -f earmark-smtp-pw`, `~/repos/ftlm-mail/deploy.sh passwd earmark` (with `PASSWORD_FILE`), rewrite the `.env` line, restart |

## Rollback

- App: `ssh root@49.12.218.29 'systemctl disable --now earmark'`.
- Hetzner nginx: `cp /root/nginx-summerfest.pre-earmark /etc/nginx/sites-enabled/summerfest`
  (follow the symlink), `nginx -t && systemctl reload nginx`.
- linode: remove the earmark block from the blog repo's `build/nginx/nginx.conf`, then
  `build/deploy.sh nginx`.

## Open

- **Backups** of `payload-secret` and the database (e.g. a nightly `pg_dump` and the secret to an
  off-box location).
- **Memory.** summerfest (-Xmx1024m) and earmark (-Xmx512m) share 3.8 GB; a detect also runs
  ffmpeg + audiowmark (~100–300 MB per request). Two detects in parallel are fine; many are not
  (no queue yet).
- A Hetzner Cloud project of its own once it has users ("partymaker" for now).
