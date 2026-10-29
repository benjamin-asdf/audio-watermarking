## Shared by the infra scripts: . "$(dirname "$0")/_lib.sh"
set -euo pipefail
INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$INFRA_DIR/.." && pwd)"
. "$INFRA_DIR/deploy.env"
[ -f "$INFRA_DIR/deploy.local.env" ] && . "$INFRA_DIR/deploy.local.env"
remote() { ssh -o BatchMode=yes "$REMOTE_HOST" "$@"; }
