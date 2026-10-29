#!/usr/bin/env bash
## Installs the benchmark toolchain into ~/.local (no sudo): zita-resampler, audiowmark, fpcalc,
## and a Python 3.12 venv with the neural watermark engines.
set -euo pipefail

PREFIX="$HOME/.local"
SRC="$HOME/src/wm-bench"
BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$SRC" "$PREFIX/bin" "$PREFIX/lib" "$PREFIX/include"

build_zita_resampler() {
  [ -f "$PREFIX/lib/libzita-resampler.so" ] && return
  cd "$SRC"
  curl -fsSL -o zita.tar.xz https://kokkinizita.linuxaudio.org/linuxaudio/downloads/zita-resampler-1.11.2.tar.xz
  tar xf zita.tar.xz
  cd zita-resampler-1.11.2/source
  make PREFIX="$PREFIX" -j"$(nproc)"
  make PREFIX="$PREFIX" LIBDIR="$PREFIX/lib" install
}

build_audiowmark() {
  command -v audiowmark >/dev/null && return
  cd "$SRC"
  [ -d audiowmark ] || git clone --depth 1 https://github.com/swesterfeld/audiowmark
  cd audiowmark
  ## autogen.sh runs configure itself, so the paths must be in the environment before it.
  export CPATH="$PREFIX/include" LIBRARY_PATH="$PREFIX/lib" LDFLAGS="-Wl,-rpath,$PREFIX/lib"
  ./autogen.sh --prefix="$PREFIX"
  make -j"$(nproc)"
  make install
}

install_fpcalc() {
  command -v fpcalc >/dev/null && return
  cd "$SRC"
  curl -fsSL -o fpcalc.tar.gz https://github.com/acoustid/chromaprint/releases/download/v1.5.1/chromaprint-fpcalc-1.5.1-linux-x86_64.tar.gz
  tar xf fpcalc.tar.gz
  cp chromaprint-fpcalc-1.5.1-linux-x86_64/fpcalc "$PREFIX/bin/"
}

python_env() {
  cd "$BENCH_DIR"
  uv venv --python 3.12 .venv
  uv pip install --python .venv/bin/python torch==2.2.2 torchaudio==2.2.2 numpy scipy soundfile pandas pyloudnorm tabulate \
    audioseal wavmark silentcipher
}

build_zita_resampler
build_audiowmark
install_fpcalc
[ "${1:-}" = "--tools-only" ] || python_env
echo SETUP_DONE
