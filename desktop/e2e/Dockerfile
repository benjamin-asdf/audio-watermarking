## Headless runtime for the desktop app: WebKitGTK + WebKitWebDriver under Xvfb.
## The app binary and tauri-driver are mounted from the host (see run.sh). No audio tools: the app is self-contained.
FROM archlinux:latest
RUN pacman -Syu --noconfirm --needed webkit2gtk-4.1 gtk3 xorg-server-xvfb \
      ttf-dejavu nodejs webkitgtk-6.0 && pacman -Scc --noconfirm
