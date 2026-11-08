#!/usr/bin/env python3
"""Generate a PRIVATE-APP offline car routing graph from licensed OSM source data.

Input: Geofabrik malaysia-singapore-brunei-latest.osm.pbf and a public
Singapore ADM0 GeoJSON boundary. Output: sg-road-graph.zip and manifest.json.

Nodes are OSM coordinates INSIDE Singapore. Way geometry is kept at every OSM
vertex; directed arcs preserve one-ways and supported via-node turn restrictions.
No live-traffic or legal U-turn completeness is claimed for a map snapshot.
"""
import argparse, csv, hashlib, io, json, math, os, re, sys, zipfile
from datetime import datetime, timezone
from pathlib import Path
import osmium
from shapely.geometry import Point, shape
from shapely.prepared import prep

CAR_ROADS = {
    "motorway": 80, "motorway_link": 45, "trunk": 70, "trunk_link": 45,
    "primary": 60, "primary_link": 40, "secondary": 55, "secondary_link": 35,
    "tertiary": 50, "tertiary_link": 30, "unclassified": 40, "residential": 30,
    "living_street": 15, "service": 20, "road": 30
}
SINGAPORE_BOX = (103.57, 1.15, 104.16, 1.49)
DENIED = {"no", "private", "agricultural", "forestry", "delivery", "permit"}
TRUE = {"yes", "true", "1"}

def distance_m(a, b):
    lat1, lon1 = a
    lat2, lon2 = b
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2-p1, math.radians(lon2-lon1)
    q = math.sin(dp / 2)**2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2)**2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(q)))

def road_speed(tags, default):
    value = tags.get("maxspeed", "")
    m = re.fullmatch(r"\s*(\d{1,3})(?:\s*(km/h|kph|mph))?\s*", value, flags=re.I)
    if not m:
        return float(default)
    speed = int(m.group(1)) * (1.609344 if (m.group(2) or "").lower() == "mph" else 1.0)
    return float(max(8, min(110, speed)))

def may_drive(tags):
    road = tags.get("highway", "")
    if road not in CAR_ROADS:
        return False
    for key in ("motorcar", "motor_vehicle", "vehicle", "access"):
        value = tags.get(key, "")
        if value in DENIED:
            return False
        if value in TRUE:
            return True
    if tags.get("service", "") in {"emergency_access"}:
        return False
    return True

class RoadExtractor(osmium.SimpleHandler):
    def __init__(self, boundary):
        super().__init__()
        self.prepared = prep(boundary)
        self.node_coords = {}
        self.edges = []
        self.restrictions = []
        self.seen_way_ids = set()

    def inside(self, lat, lon):
        lo, south, hi, north = SINGAPORE_BOX
        return south <= lat <= north and lo <= lon <= hi and self.prepared.covers(Point(lon, lat))

    def way(self, way):
        if not may_drive(way.tags):
            return
        coords = []
        for nd in way.nodes:
            try:
                if not nd.location.valid():
                    coords.append(None)
                    continue
                p = (round(nd.location.lat, 7), round(nd.location.lon, 7))
            except (osmium.InvalidLocationError, ValueError):
                coords.append(None)
                continue
            coords.append((int(nd.ref), p) if self.inside(*p) else None)
        tags = way.tags
        direction = str(tags.get("oneway", "")).lower()
        if direction in {"-1", "reverse"}:
            forward, backward = False, True
        elif direction in TRUE or str(tags.get("junction", "")).lower() == "roundabout" or str(tags.get("highway", "")) == "motorway":
            forward, backward = True, False
        else:
            forward, backward = True, True
        speed = road_speed(tags, CAR_ROADS[tags.get("highway")])
        for a, b in zip(coords, coords[1:]):
            if a is None or b is None or a[0] == b[0]:
                continue
            meters = distance_m(a[1], b[1])
            if meters < 0.01 or meters > 1500:
                continue
            self.node_coords[a[0]] = a[1]
            self.node_coords[b[0]] = b[1]
            if forward:
                self.edges.append((a[0], b[0], int(way.id), meters, meters / (speed / 3.6)))
            if backward:
                self.edges.append((b[0], a[0], int(way.id), meters, meters / (speed / 3.6)))
            self.seen_way_ids.add(int(way.id))

    def relation(self, rel):
        tags = rel.tags
        if tags.get("type") != "restriction":
            return
        if "motorcar" in str(tags.get("except", "")).split(";"):
            return
        restriction = str(tags.get("restriction:motorcar", tags.get("restriction", "")))
        if not (restriction.startswith("only_") or restriction.startswith("no_")):
            return
        members = list(rel.members)
        fs = [m for m in members if m.role == "from" and m.type == "w"]
        ts = [m for m in members if m.role == "to" and m.type == "w"]
        vs = [m for m in members if m.role == "via" and m.type == "n"]
        if len(fs) == len(ts) == len(vs) == 1:
            self.restrictions.append((int(vs[0].ref), int(fs[0].ref),
                int(ts[0].ref), "only" if restriction.startswith("only_") else "no"))

def generate(args):
    boundary = shape(json.loads(Path(args.boundary).read_text())["features"][0]["geometry"])
    ext = RoadExtractor(boundary)
    ext.apply_file(args.osm, locations=True)
    if len(ext.node_coords) < 10000 or len(ext.edges) < 20000:
        raise RuntimeError("Road graph too small; did the Singapore boundary/source change?")
    node_ids = sorted(ext.node_coords)
    index = {node_id: i for i, node_id in enumerate(node_ids)}
    edges = sorted(((index[a], index[b], way, distance, seconds)
        for a,b,way,distance,seconds in ext.edges),
        key=lambda row: (row[0], row[1], row[2]))
    restrictions = sorted(((index[via], fs, ts, rule)
        for via, fs, ts, rule in ext.restrictions
        if via in index and fs in ext.seen_way_ids and ts in ext.seen_way_ids))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    published = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    def rows_to_bytes(rows):
        stream = io.StringIO()
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerows(rows)
        return stream.getvalue().encode("utf-8")
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr("nodes.tsv", rows_to_bytes(ext.node_coords[k] for k in node_ids))
        z.writestr("arcs.tsv", rows_to_bytes((
            a,b,way,round(distance,2),round(seconds,3)) for a,b,way,distance,seconds in edges
        ))
        z.writestr("restrictions.tsv", rows_to_bytes(restrictions))
        z.writestr("metadata.json", json.dumps({
            "schema":1, "country":"SG", "generated_at":published,
            "nodes":len(node_ids), "arcs":len(edges), "via_node_turn_rules":len(restrictions),
            "source":"OpenStreetMap / Geofabrik malaysia-singapore-brunei",
            "boundary":"geoBoundaries Singapore ADM0",
            "license":"ODbL 1.0 (OSM), geoBoundaries attribution",
            "note":"car profile; only via-node restrictions implemented; not legal advice"
        }))
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    manifest = {
        "schema":1, "country":"SG", "version":published,
        "package_url":args.release_url, "sha256":digest,
        "size_bytes":output.stat().st_size,
        "nodes":len(node_ids), "arcs":len(edges),
        "generated_at":published
    }
    Path(args.manifest).write_text(json.dumps(manifest, indent=2) + "\n")
    print("Generated", output, output.stat().st_size, "bytes",len(node_ids),"nodes",
        len(edges),"arcs",len(restrictions),"restrictions")

if __name__ == "__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--osm",required=True)
    p.add_argument("--boundary",required=True)
    p.add_argument("--output",required=True)
    p.add_argument("--manifest",required=True)
    p.add_argument("--release-url",required=True)
    generate(p.parse_args())
