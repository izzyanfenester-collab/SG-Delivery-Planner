#!/usr/bin/env python3
"""Build SG Delivery's *drivable* Singapore road graph from permitted OSM extracts.

Input is a Singapore-area OSM .pbf, NOT scraped map tiles. Requires pyosmium:
  python3 -m pip install osmium
  python3 scripts/build_sg_road_graph.py singapore.osm.pbf --output sg_offline.zip

The compact SGR1 memory-mappable graph includes all supported motor-vehicle
highways, directed edges, speed estimates, and via-node turn restrictions.
No per-device server or internet connection is needed to route on this graph.
The package includes a DAYLIGHT cartographic MBTiles basemap generated directly
from the same licensed OSM PBF plus public-domain Natural Earth coastline.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
from math import asin, cos, radians, sin, sqrt
from pathlib import Path
import re
import struct
import tempfile
import zipfile

import osmium
import numpy as np
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import connected_components
from render_sg_offline_tiles import render as render_offline_roads

HIGHWAYS = {
    "motorway": 90, "motorway_link": 45,
    "trunk": 70, "trunk_link": 40,
    "primary": 55, "primary_link": 40,
    "secondary": 45, "secondary_link": 35,
    "tertiary": 40, "tertiary_link": 30,
    "unclassified": 35, "residential": 30,
    "living_street": 12, "service": 20, "road": 25,
}
DENY = {"no", "private", "agricultural", "forestry"}
BOX = (103.54, 1.12, 104.19, 1.52)  # SG plus short border approach roads.

def inside(lat: float, lon: float) -> bool:
    return BOX[1] <= lat <= BOX[3] and BOX[0] <= lon <= BOX[2]

def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    dy = radians(lat2 - lat1)
    dx = radians(lon2 - lon1)
    h = sin(dy / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dx / 2) ** 2
    return 12_742_000.0 * asin(min(1.0, sqrt(max(0.0, h))))

def speed_kmh(tags: dict, cls: str) -> float:
    raw = tags.get("maxspeed", "").lower().strip()
    first = re.search(r"\b(\d{1,3})(?:\s*(mph|km/h|kph))?\b", raw)
    if first:
        speed = float(first.group(1))
        if first.group(2) == "mph":
            speed *= 1.609344
        if 5 <= speed <= 140:
            return speed
    return HIGHWAYS[cls]

def direction(tags: dict, cls: str) -> int:
    value = tags.get("oneway:motor_vehicle", tags.get("oneway", "")).lower().strip()
    if value in {"-1", "reverse"}:
        return -1
    if value in {"yes", "1", "true"}:
        return 1
    if value in {"no", "false", "0"}:
        return 0
    return 1 if cls == "motorway" or tags.get("junction") == "roundabout" else 0

class RoadHandler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.coords: dict[int, tuple[float, float]] = {}
        self.edges: list[tuple[int, int, int, int, int]] = []
        self.way_ids: dict[int, int] = {}
        # Cartographic road classification is separate from driving time and cannot
        # be guessed from integer-rounded seconds on very short road segments.
        self.way_classes: dict[int, int] = {}
        self.turns: list[tuple[int, int, int, int]] = []
        self.via_way_skipped = 0

    def way(self, way):
        tags = dict(way.tags)
        cls = tags.get("highway", "")
        if cls not in HIGHWAYS:
            return
        if any(tags.get(tag, "").lower() in DENY for tag in
               ("access", "vehicle", "motor_vehicle", "motorcar")):
            return
        if tags.get("service") in {"parking_aisle", "driveway"}:
            return
        pairs: list[tuple[int, float, float]] = []
        for node in way.nodes:
            try:
                if not node.location.valid():
                    return
                lat, lon = node.location.lat, node.location.lon
            except (ValueError, RuntimeError):
                return
            if not inside(lat, lon):
                # Keep complete ways passing through boundary but clip far outside.
                return
            pairs.append((node.ref, lat, lon))
        if len(pairs) < 2:
            return
        compact_way = self.way_ids.setdefault(way.id, len(self.way_ids) + 1)
        self.way_classes[compact_way] = (
            3 if cls in {"motorway", "motorway_link", "trunk", "trunk_link"}
            else 2 if cls in {"primary", "primary_link", "secondary", "secondary_link"}
            else 1 if cls in {"tertiary", "tertiary_link", "unclassified"}
            else 0
        )
        oneway = direction(tags, cls)
        speed = speed_kmh(tags, cls)
        for a, b in zip(pairs, pairs[1:]):
            if a[0] == b[0]:
                continue
            metres = max(1, round(distance_m(a[1], a[2], b[1], b[2])))
            if metres > 12_000:
                continue
            seconds = max(1, round(metres * 3.6 / speed))
            self.coords[a[0]] = (a[1], a[2])
            self.coords[b[0]] = (b[1], b[2])
            if oneway >= 0:
                self.edges.append((a[0], b[0], metres, seconds, compact_way))
            if oneway <= 0:
                self.edges.append((b[0], a[0], metres, seconds, compact_way))

    def relation(self, relation):
        tags = dict(relation.tags)
        if tags.get("type") != "restriction":
            return
        exceptions = set(re.split(r"[,; ]+", tags.get("except", "").lower()))
        if exceptions.intersection({"motorcar", "motor_vehicle", "vehicle", "car"}):
            return
        value = tags.get("restriction:motorcar", tags.get("restriction", "")).lower()
        if not value.startswith(("no_", "only_")):
            return
        from_ids = [x.ref for x in relation.members if x.role == "from" and x.type == "w"]
        to_ids = [x.ref for x in relation.members if x.role == "to" and x.type == "w"]
        via = [x.ref for x in relation.members if x.role == "via" and x.type == "n"]
        if not via or not from_ids or not to_ids:
            if any(x.role == "via" and x.type == "w" for x in relation.members):
                self.via_way_skipped += 1
            return
        kind = 1 if value.startswith("only_") else 0
        for fr in from_ids:
            for to in to_ids:
                self.turns.append((via[0], fr, to, kind))

def write_graph(pbf: Path, target: Path) -> dict:
    handler = RoadHandler()
    handler.apply_file(str(pbf), locations=True, idx="flex_mem")
    if len(handler.coords) < 1000 or len(handler.edges) < 1000:
        raise RuntimeError("Input is not a complete motor-vehicle Singapore road graph")
    node_ids = sorted(handler.coords)
    node_index = {key: index for index, key in enumerate(node_ids)}
    edges = sorted(
        (node_index[a], node_index[b], length, duration, way)
        for a, b, length, duration, way in handler.edges
        if a in node_index and b in node_index
    )

    # Snap delivery addresses to the largest strongly connected DRIVING component,
    # not to isolated estate/service fragments with no valid path back to the city.
    # Keep every road in the actual graph for complete cartography and diagnostics.
    sources = np.fromiter((row[0] for row in edges), dtype=np.int32, count=len(edges))
    destinations = np.fromiter((row[1] for row in edges), dtype=np.int32, count=len(edges))
    adjacency = csr_matrix(
        (np.ones(len(edges), dtype=np.uint8), (sources, destinations)),
        shape=(len(node_ids), len(node_ids))
    )
    component_count, labels = connected_components(adjacency, directed=True, connection="strong")
    sizes = np.bincount(labels)
    primary_component = int(np.argmax(sizes))
    primary_mask = labels == primary_component
    print("Driving components:", component_count, "main component nodes:", int(sizes[primary_component]))

    first = [0] * len(node_ids)
    degree = [0] * len(node_ids)
    for start, _, _, _, _ in edges:
        degree[start] += 1
    offset = 0
    for i in range(len(node_ids)):
        first[i] = offset
        offset += degree[i]
    accepted = []
    incoming = {(b, way) for a, b, _, _, way in edges}
    outgoing = {(a, way) for a, b, _, _, way in edges}
    for via_id, from_id, to_id, mode in handler.turns:
        n = node_index.get(via_id)
        fr = handler.way_ids.get(from_id)
        to = handler.way_ids.get(to_id)
        if n is not None and fr is not None and to is not None:
            if (n, fr) in incoming and (n, to) in outgoing:
                accepted.append((n, fr, to, mode))
    accepted = sorted(set(accepted))
    with target.open("wb") as f:
        f.write(struct.pack("<4s7i", b"SGR1", 2, len(node_ids),
                            len(edges), len(accepted), 0, 0, 0))
        for i, osm_id in enumerate(node_ids):
            latitude, longitude = handler.coords[osm_id]
            # SGR2: high bit of signed outdegree marks the routable giant SCC.
            # Degree remains the low 31 bits; edge and restriction layout unchanged.
            encoded_degree = degree[i] | (0x80000000 if primary_mask[i] else 0)
            if encoded_degree >= 0x80000000:
                encoded_degree -= 0x100000000
            f.write(struct.pack("<4i", round(latitude * 1e7), round(longitude * 1e7),
                                first[i], encoded_degree))
        for _, to, length, duration, way in edges:
            f.write(struct.pack("<4i", to, length, duration, way))
        for data in accepted:
            f.write(struct.pack("<4i", *data))
    # Build-time-only sidecar: the app still installs the exact same SGR2 graph.
    # Class IDs index the existing compact OSM way IDs; sidecar is not shipped.
    class_codes = bytearray(len(handler.way_ids) + 1)
    for compact_id, kind in handler.way_classes.items():
        class_codes[compact_id] = kind
    target.with_suffix(".classes").write_bytes(class_codes)
    print("Graph: nodes", len(node_ids), "edges", len(edges),
          "restrictions", len(accepted), "unhandled via-way", handler.via_way_skipped)
    return {
        "nodes": len(node_ids), "edges": len(edges),
        "primary_scc_nodes": int(sizes[primary_component]),
        "turn_restrictions": len(accepted),
        "via_way_restrictions_unhandled": handler.via_way_skipped,
        "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        "bytes": target.stat().st_size,
    }

def package(pbf: Path, output: Path, version: str, land_shp: Path) -> dict:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        graph = Path(temp) / "sg-routing.bin"
        details = write_graph(pbf, graph)
        tiles = Path(temp) / "sg-road-map.mbtiles"
        render_offline_roads(graph, tiles, pbf, land_shp)
        tile_sha = hashlib.sha256(tiles.read_bytes()).hexdigest()
        metadata = {
            "schema": 2,
            "version": version,
            "built_at": datetime.now(timezone.utc).isoformat(),
            "source": "© OpenStreetMap contributors (ODbL), Geofabrik OSM extract; Natural Earth land (Public Domain)",
            "scope": "Singapore driving road network plus border approaches",
            "graph": details,
            "road_tiles": {"sha256": tile_sha, "bytes": tiles.stat().st_size, "format": "mbtiles", "min_zoom": 9, "max_zoom": 16},
            "note": "Directed driving graph and independently generated DAYLIGHT OSM/Natural Earth cartographic basemap (land/water/parks/urban/roads/labels); via-way restrictions not yet supported.",
        }
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=8) as archive:
            archive.write(graph, "sg-routing.bin")
            archive.write(tiles, "sg-road-map.mbtiles")
            archive.writestr("metadata.json", json.dumps(metadata, separators=(",", ":")))
    return metadata

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pbf", type=Path)
    parser.add_argument("--land-shp", type=Path, required=True, help="Public-domain Natural Earth 10m land shapefile")
    parser.add_argument("--output", type=Path, default=Path("sg-offline-road-network.zip"))
    parser.add_argument("--version", default=datetime.now(timezone.utc).strftime("%Y%m%d"))
    args = parser.parse_args()
    metadata = package(args.pbf, args.output, args.version, args.land_shp)
    print(json.dumps(metadata, indent=2))
    print("package SHA256:", hashlib.sha256(args.output.read_bytes()).hexdigest())

if __name__ == "__main__":
    main()
