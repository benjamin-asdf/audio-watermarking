#!/usr/bin/env bash
## Tail the app log on the server.
. "$(dirname "$0")/_lib.sh"
remote "tail -n 100 -f '$REMOTE_ROOT/log/app.log'"
