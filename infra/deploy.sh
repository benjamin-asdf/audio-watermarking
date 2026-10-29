#!/usr/bin/env bash
## Build UI + engine CLI locally, rsync to the Hetzner box, restart the systemd unit.
## Usage: infra/deploy.sh [--no-build] [--no-restart]
. "$(dirname "$0")/_lib.sh"
BUILD=1; RESTART=1
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    --no-restart) RESTART=0 ;;
    *) echo "unknown arg: $a"; exit 2 ;;
  esac
done

if [ "$BUILD" -eq 1 ]; then
  echo "==> UI release build"
  (cd "$PROJECT_ROOT/ui" && npx shadow-cljs release app)
  echo "==> wmark (bookworm container)"
  "$INFRA_DIR/build-wmark.sh"
fi

echo "==> Sync"
remote "mkdir -p '$REMOTE_ROOT/code' '$REMOTE_ROOT/bin'"
rsync -az --delete --exclude='.cpcache' --exclude='target' --exclude='.clj-kondo' --exclude='.lsp' \
  -e 'ssh -o BatchMode=yes' "$PROJECT_ROOT/server/" "$REMOTE_HOST:$REMOTE_ROOT/code/"
rsync -az -e 'ssh -o BatchMode=yes' "$INFRA_DIR/build/wmark" "$REMOTE_HOST:$REMOTE_ROOT/bin/wmark"
## The unit carries the non-secret config; reinstall it when it changed.
unit="/etc/systemd/system/$SERVICE_NAME.service"
if ! remote "cat '$unit'" | cmp -s - "$INFRA_DIR/remote/earmark.service"; then
  echo "==> Unit changed, reinstalling"
  rsync -a -e 'ssh -o BatchMode=yes' "$INFRA_DIR/remote/earmark.service" "$REMOTE_HOST:$unit"
  remote "chmod 644 '$unit' && systemctl daemon-reload"
fi

[ "$RESTART" -eq 1 ] || { echo "==> Skipping restart."; exit 0; }

echo "==> Restart (migrations run on boot; cold start ~30 s)"
remote "systemctl restart '$SERVICE_NAME'"
## Deadline 120 s, then report.
for i in $(seq 1 60); do
  if remote "curl -fs -o /dev/null http://127.0.0.1:$APP_PORT$BASE_PATH/"; then
    echo "==> Up after ~$((i * 2)) s: $PUBLIC_URL/"; exit 0
  fi
  sleep 2
done
echo "==> Not up after 120 s. Logs: infra/logs.sh"; exit 1
