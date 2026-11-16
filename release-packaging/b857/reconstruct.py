import hashlib,json,zipfile,sys,zlib,io
from pathlib import Path
base=Path(sys.argv[1]).read_bytes()
def sha(x): return hashlib.sha256(x).hexdigest()
with zipfile.ZipFile(sys.argv[2]) as z:
    ops=json.loads(z.read("ops.json"))
    literal=z.read("literal.bin")
assert sha(base)==ops["base_sha256"]=="0d4cfcf37eb2f25929ad5686a715f85bdc78b7cd602d28215027bd80263d42a6"
out=bytearray()
pos=0
basezip=zipfile.ZipFile(io.BytesIO(base))
for op in ops["ops"]:
    if isinstance(op,list) and len(op)==3 and op[0]=="deflate":
        a,b=op[1:]
        if isinstance(a,str) and isinstance(b,int) and a in basezip.namelist():
            raw=basezip.read(a)
            compressor=zlib.compressobj(b,zlib.DEFLATED,-15)
            out.extend(compressor.compress(raw)+compressor.flush())
        elif isinstance(b,str) and isinstance(a,int) and b in basezip.namelist():
            raw=basezip.read(b)
            compressor=zlib.compressobj(a,zlib.DEFLATED,-15)
            out.extend(compressor.compress(raw)+compressor.flush())
        else:
            raise ValueError(f"deflate args: {type(a).__name__} {str(a)[:180]} / {type(b).__name__} {str(b)[:180]}")
    elif isinstance(op, list) and len(op)==2 and isinstance(op[1],int):
        n=op[1]; assert 0<=n<=len(literal)-pos
        out.extend(literal[pos:pos+n]); pos+=n
    elif isinstance(op, list) and len(op)==3 and all(isinstance(v,int) for v in op[1:]):
        offset,n=op[1:]; assert 0<=offset<=len(base) and 0<=n<=len(base)-offset
        out.extend(base[offset:offset+n])
    else: raise ValueError(f"Unsupported delta operation type={type(op).__name__}, shape={len(op) if isinstance(op,(list,dict)) else None}, tag={op[0] if isinstance(op,list) and op else None}")
assert pos==len(literal)
assert len(out)==91548446
assert sha(out)==ops["target_sha256"]=="a961d7be6156b701c7da52508098a0247bf3dcbe8da897c13feac9832505b9da", f"Reconstruction hash mismatch: {sha(out)}"
p=Path(sys.argv[3]); p.write_bytes(out)
with zipfile.ZipFile(p) as z:
    assert z.testzip() is None
    root="assets/voicepacks/panda_warrior_v2/"
    clips=json.loads(z.read(root+"manifest.json"))["clips"]
    assert len(clips)==185 and len({c["id"] for c in clips})==185
    names=set(z.namelist())
    for c in clips:
        assert root+c["file"] in names
        assert root+"audio_pcm/"+c["id"]+".pcm" in names
    for abi in ["arm64-v8a","armeabi-v7a"]:
        assert any(n.startswith("lib/"+abi+"/") for n in names)
print("Exact tested signed candidate, 185 original clips, 185 PCM clips, Universal ABI and SHA-256 verified.")
