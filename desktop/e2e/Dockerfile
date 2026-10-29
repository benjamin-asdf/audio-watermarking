## Headless runtime for the desktop app: WebKitGTK + WebKitWebDriver under Xvfb.
## The app binary, tauri-driver and the engine tools are mounted from the host (see run.sh).
FROM archlinux:latest
RUN pacman -Syu --noconfirm --needed webkit2gtk-4.1 gtk3 xorg-server-xvfb ffmpeg chromaprint \
      fftw libsndfile libgcrypt mpg123 ttf-dejavu nodejs webkitgtk-6.0 && pacman -Scc --noconfirm
