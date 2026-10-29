#!/usr/bin/env bash
## Runs remote/smoke.py on the server against the live service (127.0.0.1, behind nginx).
. "$(dirname "$0")/_lib.sh"
scp -q -o BatchMode=yes "$INFRA_DIR/remote/smoke.py" "$REMOTE_HOST:/root/earmark-smoke.py"
remote "timeout 600 python3 /root/earmark-smoke.py"
