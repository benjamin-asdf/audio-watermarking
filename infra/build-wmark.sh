#!/usr/bin/env bash
## Builds the wmark CLI for the server inside Debian bookworm (glibc 2.36, runs on Ubuntu 24.04).
## Output: infra/build/wmark. Cargo registry cache: infra/build/cargo (gitignored).
. "$(dirname "$0")/_lib.sh"
mkdir -p "$INFRA_DIR/build/cargo"
docker run --rm \
  -u "$(id -u):$(id -g)" \
  -e CARGO_HOME=/cargo \
  -v "$INFRA_DIR/build/cargo:/cargo" \
  -v "$PROJECT_ROOT/engine/wmark:/src:ro" \
  -v "$INFRA_DIR/build:/out" \
  -w /tmp \
  rust:1-bookworm \
  sh -c 'cp -r /src /tmp/wmark && cd /tmp/wmark && cargo build --release --locked && cp target/release/wmark /out/wmark'
echo "built $INFRA_DIR/build/wmark"
