"""Prepare and publish a *verified* 3-source SG postal database for B810.

Runs on GitHub Actions weekly, never on a runner's phone. No OneMap token.
Sources: complete public OneMap-derived CSV, data.gov.sg HDB footprints, and
Geofabrik's OpenStreetMap PBF extract. Database remains downloadable for free.
"""

import csv
from datetime import datetime, timezone
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.error
import urllib.request
import zipfile

REPO = "dhanyyudi/onemap-building-sg"
GEOFABRIK = "https://download.geofabrik.de/asia/malaysia-singapore-brunei-latest.osm.pbf"
HDB_DATASET = "d_16b157c52ed637edd6ba1232e026258d"
POSTAL = re.compile(r"\d{6}\Z")
HEADERS = {"User-Agent": "SG-Delivery-Public-Postal-Builder/1.0", "Accept": "application/json"}


def json_get(url):
    request = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=90) as reply:
        return json.load(reply)


def download(url, path, max_size):
    request = urllib.request.Request(url, headers={"User-Agent": HEADERS["User-Agent"]})
    with urllib.request.urlopen(request, timeout=180) as reply, path.open("wb") as output:
        size = 0
        while True:
            chunk = reply.read(128 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > max_size:
                raise ValueError(f"Source exceeds allowed size: {url.split('?')[0]}")
            output.write(chunk)
    return size


def valid(code, lat, lon):
    return bool(POSTAL.fullmatch(code)) and 1.1 < lat < 1.6 and 103.5 < lon < 104.1


def best_upstream():
    response = json_get(f"https://api.github.com/repos/{REPO}/contents/data?ref=main")
    candidates = []
    for item in response:
        name = item.get("name", "")
        matched = re.fullmatch(r"onemap_(\d{2})(\d{2})(\d{4})\.csv", name)
        if not matched or not 8_000_000 <= item.get("size", 0) <= 35_000_000:
            continue
        date = datetime.strptime("".join(matched.groups()), "%d%m%Y").date()
        url = item.get("download_url", "")
        if url.startswith("https://raw.githubusercontent.com/dhanyyudi/onemap-building-sg/"):
            candidates.append((date, url))
    if not candidates:
        raise ValueError("No complete public OneMap-derived dataset")
    return max(candidates)


def read_base(path):
    entries = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            code = str(row["postal_code"]).strip().zfill(6)
            try:
                lat, lon = float(row["lat"]), float(row["lon"])
            except (TypeError, ValueError):
                continue
            if valid(code, lat, lon):
                entries.setdefault(code, (row["blk_no"].strip(), row["street"].strip(),
                    row["name"].strip(), lat, lon, "onemap_public_snapshot"))
    if len(entries) < 100_000:
        raise ValueError("Base postal dump is incomplete")
    return entries


def hdb_entries(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as zipped:
            candidates = [item for item in zipped.infolist() if
                          item.filename.lower().endswith((".geojson", ".json"))
                          and 0 < item.file_size <= 150_000_000]
            if len(candidates) != 1:
                raise ValueError("Expected one Singapore HDB GeoJSON inside the archive")
            records = json.loads(zipped.read(candidates[0]).decode("utf-8"))
    else:
        records = json.loads(path.read_text(encoding="utf-8"))
    result = {}
    for item in records.get("features", []):
        attrs = item.get("properties") or {}
        code = str(attrs.get("POSTAL_COD") or "").strip().zfill(6)
        if not POSTAL.fullmatch(code):
            continue
        geom = item.get("geometry") or {}
        vertices = []
        def walk(coords):
            if not isinstance(coords, list) or not coords:
                return
            if len(coords) >= 2 and isinstance(coords[0], (int, float)) and isinstance(coords[1], (int, float)):
                vertices.append((float(coords[1]), float(coords[0])))
            else:
                for node in coords:
                    walk(node)
        walk(geom.get("coordinates", []))
        if not vertices:
            continue
        lat = sum(p[0] for p in vertices) / len(vertices)
        lon = sum(p[1] for p in vertices) / len(vertices)
        if valid(code, lat, lon):
            block = str(attrs.get("BLK_NO") or "").strip()
            result.setdefault(code, (block, "HDB", "HDB Building", lat, lon, "hdb_data_gov_sg"))
    if len(result) < 5_000:
        raise ValueError(f"Incomplete HDB footprint dataset: {len(result)}")
    return result


def osm_entries(path):
    try:
        import osmium
    except ImportError as error:
        raise RuntimeError("Install pyosmium on the GitHub runner") from error

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.items = {}
            self.conflicting = set()

        def add(self, tags, lat, lon):
            postal = tags.get("addr:postcode", "").strip()
            if not valid(postal, lat, lon) or postal in self.conflicting:
                return
            if not (tags.get("addr:street") or tags.get("addr:housenumber")):
                return
            entry = (tags.get("addr:housenumber", ""), tags.get("addr:street", ""),
                     tags.get("name", ""), lat, lon, "openstreetmap")
            old = self.items.get(postal)
            if old and abs(old[3] - lat) + abs(old[4] - lon) > 0.002:
                self.conflicting.add(postal)
                self.items.pop(postal, None)
            else:
                self.items.setdefault(postal, entry)

        def node(self, node):
            if node.location.valid():
                self.add(node.tags, node.location.lat, node.location.lon)

        def way(self, way):
            locs = [(n.location.lat, n.location.lon) for n in way.nodes if n.location.valid()]
            if locs:
                self.add(way.tags, sum(x[0] for x in locs)/len(locs),
                         sum(x[1] for x in locs)/len(locs))

    handler = Handler()
    handler.apply_file(str(path), locations=True)
    return handler.items


def write_outputs(folder, entries, stats, source_versions):
    folder.mkdir(parents=True, exist_ok=True)
    archive = folder / "singapore-postals.csv.gz"
    with archive.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
            with io.TextIOWrapper(gz, encoding="utf-8", newline="") as text:
                writer = csv.writer(text, lineterminator="\n")
                writer.writerow(["blk_no", "street", "postal_code", "name", "lat", "lon"])
                for postal in sorted(entries):
                    block, street, name, lat, lon, _source = entries[postal]
                    clean = lambda value: str(value).replace("\r", " ").replace("\n", " ").strip()
                    writer.writerow([clean(block), clean(street), postal, clean(name),
                                     f"{lat:.8f}", f"{lon:.8f}"])
    sha256 = hashlib.sha256(archive.read_bytes()).hexdigest()
    version = int(datetime.now(timezone.utc).strftime("%Y%m%d"))
    manifest = {"schema": 1, "version": version, "postal_count": len(entries),
                "archive": "singapore-postals.csv.gz", "sha256": sha256,
                "bytes": archive.stat().st_size, "source_counts": stats,
                "sources": source_versions, "updated_utc": datetime.now(timezone.utc).isoformat()}
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def github_api(method, path, payload=None):
    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not token or not repo:
        raise RuntimeError("Publishing requires the GitHub Actions repository token")
    url = f"https://api.github.com/repos/{repo}/{path}"
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, body, method=method, headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
        "User-Agent": HEADERS["User-Agent"], "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def publish(folder, manifest):
    branch = "postal-data-live"
    current = github_api("GET", f"git/ref/heads/{branch}")
    if current is None:
        head = github_api("GET", "git/ref/heads/main")
        current = github_api("POST", "git/refs", {"ref": f"refs/heads/{branch}",
                                                        "sha": head["object"]["sha"]})
    parent = current["object"]["sha"]
    previous = github_api("GET", f"contents/postal/manifest.json?ref={branch}")
    if previous:
        import base64
        last = json.loads(base64.b64decode(previous["content"]).decode("utf-8"))
        if last.get("sha256") == manifest["sha256"]:
            print("No changes in verified combined postal data; keeping existing version")
            return
    tree = github_api("GET", f"git/commits/{parent}")["tree"]["sha"]
    new_files = []
    import base64
    for filename in ("manifest.json", "singapore-postals.csv.gz"):
        data = (folder / filename).read_bytes()
        blob = github_api("POST", "git/blobs", {"content": base64.b64encode(data).decode("ascii"),
                                                   "encoding": "base64"})
        new_files.append({"path": f"postal/{filename}", "mode": "100644", "type": "blob", "sha": blob["sha"]})
    new_tree = github_api("POST", "git/trees", {"base_tree": tree, "tree": new_files})["sha"]
    new_commit = github_api("POST", "git/commits", {"message": "Weekly verified SG postal data",
                                      "tree": new_tree, "parents": [parent]})["sha"]
    github_api("PATCH", f"git/refs/heads/{branch}", {"sha": new_commit, "force": False})
    print(f"Published {manifest['postal_count']} SG postals to {branch}: {new_commit}")


def main():
    with tempfile.TemporaryDirectory(prefix="sg-postal-weekly-") as temp:
        base = Path(temp)
        source_date, source_url = best_upstream()
        download(source_url, base / "base.csv", 35_000_000)
        entries = read_base(base / "base.csv")
        base_count = len(entries)

        hdb_api = json_get(f"https://api-open.data.gov.sg/v1/public/api/datasets/{HDB_DATASET}/poll-download")
        hdb_url = hdb_api.get("data", {}).get("url")
        if hdb_api.get("code") != 0 or not hdb_url or not hdb_url.startswith("https://"):
            raise ValueError("HDB public download unavailable")
        download(hdb_url, base / "hdb.geojson", 120_000_000)
        hdb = hdb_entries(base / "hdb.geojson")
        hdb_added = 0
        for code, row in hdb.items():
            if code not in entries:
                entries[code] = row
                hdb_added += 1

        download(GEOFABRIK, base / "region.osm.pbf", 400_000_000)
        osm = osm_entries(base / "region.osm.pbf")
        osm_added = 0
        for code, row in osm.items():
            if code not in entries:
                entries[code] = row
                osm_added += 1
        if len(entries) < base_count:
            raise AssertionError("Never drop baseline records")
        stats = {"onemap_public_snapshot": base_count, "hdb_new": hdb_added,
                 "openstreetmap_new": osm_added, "hdb_total": len(hdb), "openstreetmap_total": len(osm)}
        out = base / "out"
        manifest = write_outputs(out, entries, stats, {
            "onemap_derived": str(source_date), "hdb": HDB_DATASET,
            "openstreetmap": "Geofabrik daily PBF"})
        print(json.dumps(manifest, indent=2))
        if os.environ.get("GITHUB_TOKEN"):
            publish(out, manifest)


if __name__ == "__main__":
    main()
