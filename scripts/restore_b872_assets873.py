#!/usr/bin/env python3
"""Restore only byte-verified original voice assets from the exact Official B872 APK."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

BASELINE_SHA256="93accd9cdc732a2a9fb7e33b9af4edbd1c4fa4e8b34378036584ee75b2c2a4ae"

def restore(path):
    apk=Path(path)
    assert hashlib.sha256(apk.read_bytes()).hexdigest()==BASELINE_SHA256
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        for name in z.namelist():
            if name.startswith("assets/voicepacks/") and not name.endswith("/"):
                assert ".." not in Path(name).parts
                out=Path("app/src/main")/name
                out.parent.mkdir(parents=True,exist_ok=True)
                out.write_bytes(z.read(name))
        assert len(json.loads(z.read("assets/voicepacks/panda_warrior_v2/manifest.json"))["clips"])==185
        assert len(json.loads(z.read("assets/voicepacks/panda_warrior_camera/manifest.json"))["clips"])==24
    print("Restored all 209 original B872 MP3 and PCM byte-for-byte")

if __name__=="__main__":restore(sys.argv[1])
