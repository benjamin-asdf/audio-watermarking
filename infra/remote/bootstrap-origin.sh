#!/usr/bin/env bash
## Runs ON the Hetzner box as root (via infra/bootstrap.sh). Idempotent: safe to re-run.
## Secrets are generated here and never leave the server; nothing secret is printed.
set -euo pipefail
: "${REMOTE_ROOT:?}" "${SERVICE_NAME:?}"
HERE="$(cd "$(dirname "$0")" && pwd)"
AUDIOWMARK_VERSION=0.6.5
OTS_CLIENT_VERSION=0.8.0

echo "==> apt packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ffmpeg libchromaprint-tools python3-venv git curl \
  build-essential autoconf automake libtool pkg-config \
  libfftw3-dev libsndfile1-dev libgcrypt20-dev libzita-resampler-dev libmpg123-dev >/dev/null

echo "==> audiowmark $AUDIOWMARK_VERSION"
if [ "$(audiowmark --version 2>/dev/null | awk '{print $2}')" != "$AUDIOWMARK_VERSION" ]; then
  rm -rf /usr/local/src/audiowmark
  git clone -q --depth 1 --branch "$AUDIOWMARK_VERSION" https://github.com/swesterfeld/audiowmark /usr/local/src/audiowmark
  (cd /usr/local/src/audiowmark && ./autogen.sh --prefix=/usr/local >/dev/null && make -s -j"$(nproc)" >/dev/null && make -s install >/dev/null)
fi
audiowmark --version | head -1

echo "==> user, dirs"
id earmark >/dev/null 2>&1 || useradd --system --home-dir "$REMOTE_ROOT/home" --shell /usr/sbin/nologin earmark
mkdir -p "$REMOTE_ROOT"/{code,bin,data,log,home}
chown earmark:earmark "$REMOTE_ROOT"/{data,log,home}
chmod 700 "$REMOTE_ROOT/data"

echo "==> ots client $OTS_CLIENT_VERSION (venv)"
[ -x "$REMOTE_ROOT/venv/bin/ots" ] || {
  python3 -m venv "$REMOTE_ROOT/venv"
  "$REMOTE_ROOT/venv/bin/pip" install -q "opentimestamps-client==$OTS_CLIENT_VERSION"
}

echo "==> Postgres role + db"
ENV_FILE="$REMOTE_ROOT/.env"
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='earmark'" | grep -q 1; then
  ( umask 077
    PW="$(openssl rand -hex 24)"
    ## Password goes via stdin, never on a command line.
    sudo -u postgres psql -q -v ON_ERROR_STOP=1 <<SQL
CREATE ROLE earmark LOGIN PASSWORD '$PW';
CREATE DATABASE earmark OWNER earmark;
SQL
    echo "WM_DB_URL=jdbc:postgresql://127.0.0.1:5432/earmark?user=earmark&password=$PW" > "$ENV_FILE" )
fi
chown root:earmark "$ENV_FILE"; chmod 640 "$ENV_FILE"

echo "==> systemd unit"
install -m 644 "$HERE/earmark.service" "/etc/systemd/system/$SERVICE_NAME.service"
systemctl daemon-reload
systemctl enable -q "$SERVICE_NAME"

echo "==> nginx origin location"
install -m 644 "$HERE/origin-earmark.conf" /etc/nginx/snippets/earmark-origin.conf
SITE=/etc/nginx/sites-enabled/summerfest
if ! grep -q 'earmark-origin.conf' "$SITE"; then
  cp "$SITE" "/root/nginx-summerfest.pre-earmark"
  ## Insert the include right before the catch-all `location / {` of the 443 server.
  sed -i --follow-symlinks '0,/^    location \/ {/s//    include \/etc\/nginx\/snippets\/earmark-origin.conf;\n\n    location \/ {/' "$SITE"
fi
if ! nginx -t 2>/dev/null; then
  echo "nginx -t failed: restoring $SITE"; cp /root/nginx-summerfest.pre-earmark "$SITE"; exit 1
fi
systemctl reload nginx
echo "==> bootstrap done"
