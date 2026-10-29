#!/usr/bin/env python3
"""End-to-end smoke test, run ON the server (infra/smoke.sh): login -> register -> embed ->
finalize -> MP3 clip -> read. Uses a synthetic clip and a smoke-test account. The login token and
session stay in this process; only statuses and the work id are printed."""
import json, re, subprocess, sys, tempfile, time, urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:8097/earmark"
LOG = Path("/opt/earmark/log/app.log")
WMARK = "/opt/earmark/bin/wmark"
EMAIL = "smoke-test@earmark.invalid"


def call(method, path, body=None, session=None, raw=None):
    ## Uploads go as octet-stream like a browser File; urllib's default form type would be parsed away.
    headers = {"Content-Type": "application/octet-stream"} if raw is not None else {}
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if session:
        headers["Authorization"] = "Bearer " + session
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=300) as r:
        txt = r.read()
        return r.status, (json.loads(txt) if txt else None)


def login():
    call("POST", "/api/auth/request", {"email": EMAIL})
    time.sleep(1)
    lines = [l for l in LOG.read_text().splitlines() if l.startswith("LOGIN LINK for " + EMAIL)]
    token = re.search(r"#(\S+)$", lines[-1]).group(1)
    return call("POST", "/api/auth/verify", {"token": token})[1]["session"]


def synth(path):
    ## 30 s: a chord plus quiet pink noise, standing in for music.
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=f=220:d=30", "-f", "lavfi", "-i", "sine=f=277:d=30",
                    "-f", "lavfi", "-i", "sine=f=330:d=30", "-f", "lavfi", "-i", "anoisesrc=c=pink:a=0.08:d=30",
                    "-filter_complex", "amix=inputs=4,tremolo=f=2:d=0.6", "-ar", "44100", "-ac", "2", str(path)],
                   check=True)


def main():
    d = Path(tempfile.mkdtemp(prefix="earmark-smoke"))
    session = login()
    print("login: ok")
    _, w = call("POST", "/api/works", {"artist": "Smoke Test", "title": "Synthetic chord"}, session)
    print("registered work", w["work-id"])
    synth(d / "in.wav")
    rep = json.loads(subprocess.run([WMARK, "embed", str(d / "in.wav"), str(d / "marked.wav"), w["payload-hex"]],
                                    check=True, capture_output=True, text=True).stdout)
    print("embed verified:", rep["verified"])
    st, _ = call("POST", f"/api/works/{w['work-id']}/finalize",
                 {"fingerprint": rep["fingerprint"], "audio-sha256": rep["sha256"]}, session)
    print("finalize:", st)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", "8", "-t", "15", "-i", str(d / "marked.wav"),
                    "-b:a", "128k", str(d / "clip.mp3")], check=True)
    _, res = call("POST", "/api/detect", raw=(d / "clip.mp3").read_bytes())
    print("read 15 s MP3 clip:", res.get("status"))
    _, res0 = call("POST", "/api/detect", raw=(d / "in.wav").read_bytes())
    print("read unmarked original:", res0.get("status"))
    sys.exit(0 if res.get("status") == "verified" and res0.get("status") == "no-watermark" else 1)


if __name__ == "__main__":
    main()
