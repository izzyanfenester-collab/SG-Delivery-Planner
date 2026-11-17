#!/usr/bin/env python3
"""Install only the user's authentic 24 camera MP3s, leaving 185 original recordings intact."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

EXPECTED = {
"speed_camera_ahead","speed_camera_advance","speed_camera_near","speed_limit_reminder",
"red_light_camera_ahead","red_light_camera_advance","red_light_camera_near","red_light_camera_safety",
"mobile_speed_camera","mobile_speed_reminder","police_speed_laser",
"average_speed_zone_ahead","average_speed_zone_enter","average_speed_zone_exit",
"speed_limit_prefix","kilometres_per_hour","overspeed_warning","speed_limit_unknown",
"red_light_possible_speed","multiple_camera_warning","speed_camera_short",
"red_light_camera_short","average_speed_zone_short","traffic_light_camera_short"
}

def install(zip_path, repository):
    zip_path = pathlib.Path(zip_path)
    root = pathlib.Path(repository)
    output = root/"app/src/main/assets/voicepacks/panda_warrior_camera"
    original_root = root/"app/src/main/assets/voicepacks/panda_warrior_v2"
    with zipfile.ZipFile(zip_path) as archive:
        assert archive.testzip() is None, "Uploaded ZIP has corrupted members"
        manifest = json.loads(archive.read("manifest.json"))
        entries = manifest["clips"]
        assert len(entries)==209
        ids = [clip["id"] for clip in entries]
        assert len(set(ids))==209, "Duplicate clip IDs"
        old=[c for c in entries if c["file"]=="audio/"+c["id"]+".mp3"]
        camera=[c for c in entries if c["file"]=="audio/camera_alerts/"+c["id"]+".mp3"]
        assert len(old)==185 and len(camera)==24
        assert set(c["id"] for c in camera)==EXPECTED
        assert len(archive.namelist())==212, "Unexpected ZIP file count"
        assert all(".." not in pathlib.PurePosixPath(c["file"]).parts for c in entries)
        # Existing navigation recordings MUST remain byte-identical.
        old_manifest=original_root/"manifest.json"
        if not old_manifest.is_file():
            raise RuntimeError("Import the B857-verified original 185 recordings before running this importer")
        verified=json.loads(old_manifest.read_text())["clips"]
        assert len(verified)==185 and {c["id"] for c in old}=={c["id"] for c in verified}
        for clip in old:
            local=original_root/clip["file"]
            if not local.is_file() or hashlib.sha256(local.read_bytes()).digest()!=hashlib.sha256(archive.read(clip["file"])).digest():
                raise RuntimeError("Original navigation recording differs or is missing: "+clip["id"])
        with tempfile.TemporaryDirectory() as scratch:
            stage=pathlib.Path(scratch)/"panda_warrior_camera"
            (stage/"audio/camera_alerts").mkdir(parents=True)
            (stage/"audio_pcm").mkdir(parents=True)
            for clip in camera:
                file=stage/clip["file"]
                contents=archive.read(clip["file"])
                assert len(contents)>5000 and (contents.startswith(b"ID3") or contents[:2] in (b"\xff\xfb",b"\xff\xf3",b"\xff\xf2"))
                file.write_bytes(contents)
                pcm=stage/"audio_pcm"/(clip["id"]+".pcm")
                subprocess.run(["ffmpeg","-hide_banner","-loglevel","error","-y","-i",str(file),"-vn","-ar","24000","-ac","1","-f","s16le",str(pcm)],check=True)
                seconds=pcm.stat().st_size/(24000*2)
                assert pcm.stat().st_size>10000 and pcm.stat().st_size%2==0
                assert abs(seconds-float(clip["duration_seconds"]))<0.15,clip["id"]
            stage_manifest={"schema_version":1,"source":"User-supplied original ElevenLabs files",
                "source_sha256":hashlib.sha256(zip_path.read_bytes()).hexdigest(),"clips":camera}
            (stage/"manifest.json").write_text(json.dumps(stage_manifest,indent=2,ensure_ascii=False)+"\n")
            (stage/"manifest-209-supplied.json").write_bytes(archive.read("manifest.json"))
            (stage/"clip_index.csv").write_bytes(archive.read("clip_index.csv"))
            (stage/"README.txt").write_bytes(archive.read("README.txt"))
            if output.exists(): shutil.rmtree(output)
            shutil.copytree(stage,output)
    print("Validated and installed 185 unchanged navigation + 24 authentic camera MP3 + 24 PCM = 209 clips")
    print("Camera pack root:",output)
    print("Source ZIP SHA256:",hashlib.sha256(zip_path.read_bytes()).hexdigest())

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("zip_path")
    p.add_argument("--repository",default=".")
    args=p.parse_args()
    install(args.zip_path,args.repository)
