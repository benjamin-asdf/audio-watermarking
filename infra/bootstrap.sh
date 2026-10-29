#!/usr/bin/env bash
## One-time (idempotent) server setup: copies infra/remote/ to the server and runs bootstrap there.
. "$(dirname "$0")/_lib.sh"
rsync -az -e 'ssh -o BatchMode=yes' "$INFRA_DIR/remote/" "$REMOTE_HOST:/root/earmark-bootstrap/"
remote "REMOTE_ROOT='$REMOTE_ROOT' APP_PORT='$APP_PORT' BASE_PATH='$BASE_PATH' PUBLIC_URL='$PUBLIC_URL' SERVICE_NAME='$SERVICE_NAME' bash /root/earmark-bootstrap/bootstrap-origin.sh"
