#!/usr/bin/env python3
"""Restore only authentic voice assets from the exact, hash-verified B871 baseline."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

BASELINE_SHA256 = "25c1d06a38d4f3b98f0a3dacf540f53baefe1eb0dc744b8eeab6f9cdb05e59a2"

def restore(path):
    apk = Path(path)
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == BASELINE_SHA256
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        for name in archive.namelist():
            if name.startswith("assets/voicepacks/") and not name.endswith("/"):
                assert ".." not in Path(name).parts
                target = Path("app/src/main") / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
        nav = json.loads(archive.read("assets/voicepacks/panda_warrior_v2/manifest.json"))["clips"]
        cam = json.loads(archive.read("assets/voicepacks/panda_warrior_camera/manifest.json"))["clips"]
        assert len(nav) == 185 and len(cam) == 24
    print("Restored 209 original B871 MP3 and existing offline PCM without regeneration")

if __name__ == "__main__":
    restore(sys.argv[1])
