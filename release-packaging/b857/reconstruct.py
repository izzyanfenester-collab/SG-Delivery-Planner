import hashlib,json,zipfile,sys,zlib,io
from pathlib import Path
base=Path(sys.argv[1]).read_bytes()
def sha(x): return hashlib.sha256(x).hexdigest()
with zipfile.ZipFile(sys.argv[2]) as z:
    ops=json.loads(z.read("ops.json"))
    literal=z.read("literal.bin")
assert sha(base)==ops["base_sha256"]=="0d4cfcf37eb2f25929ad5686a715f85bdc78b7cd602d28215027bd80263d42a6"
basezip=zipfile.ZipFile(io.BytesIO(base))
target="a961d7be6156b701c7da52508098a0247bf3dcbe8da897c13feac9832505b9da"
assert ops["target_sha256"]==target
# Deflate operations identify a ZIP entry and its expected compressed byte length.
# Try the standard compression levels and validate the entire signed APK hash.
cache={}
def candidates(name,expected):
    key=(name,expected)
    if key not in cache:
        raw=basezip.read(name)
        values={}
        for level in (6,9,1,2,3,4,5,7,8,0):
            c=zlib.compressobj(level,zlib.DEFLATED,-15)
            data=c.compress(raw)+c.flush()
            if len(data)==expected: values[level]=data
        if not values: raise ValueError(f"No raw DEFLATE length match for {name}: {expected}")
        cache[key]=values
    return cache[key]
def build(preferred):
    out=bytearray()
    pos=0
    for op in ops["ops"]:
        if op[0]=="deflate":
            a,b=op[1:]
            if isinstance(a,int) and isinstance(b,str): a,b=b,a
            if not isinstance(a,str) or not isinstance(b,int): raise ValueError("Unexpected DEFLATE structure")
            choices=candidates(a,b)
            out.extend(choices.get(preferred,next(iter(choices.values()))))
        elif isinstance(op,list) and len(op)==2 and isinstance(op[1],int):
            n=op[1]; assert 0<=n<=len(literal)-pos
            out.extend(literal[pos:pos+n]); pos+=n
        elif isinstance(op,list) and len(op)==3 and all(isinstance(v,int) for v in op[1:]):
            offset,n=op[1:]; assert 0<=offset<=len(base) and 0<=n<=len(base)-offset
            out.extend(base[offset:offset+n])
        else: raise ValueError(f"Unsupported delta operation {op[0]}")
    assert pos==len(literal)
    return out
for level in (6,9,1,2,3,4,5,7,8,0):
    out=build(level)
    if len(out)==91548446 and sha(out)==target: break
else: raise ValueError("Exact signed candidate reconstruction did not match expected SHA-256")
assert sha(out)==target
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
