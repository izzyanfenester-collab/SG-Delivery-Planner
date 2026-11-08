#!/usr/bin/env python3
"""Create a DAYLIGHT, OSM-like offline Singapore raster basemap from LICENSED data.

Does not use openstreetmap.org's volunteer tile servers or copy hosted map tiles.
- OSM Geofabrik PBF: highways, parks, land-use, lakes/reservoirs and place names.
- Natural Earth 1:10m CC0 polygons: sea / land masking and coastal geography.
- SGR2 graph: routing-road geometry and major/minor classifications.

The Android route optimizer continues to use the unchanged SGR2 driving graph.
This renderer changes only the visual offline tile payload (MBTiles PNG).
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
from io import BytesIO
import json
from math import asinh, ceil, cos, floor, hypot, pi, radians, tan
from pathlib import Path
import sqlite3
import struct

from PIL import Image, ImageDraw, ImageFont
import osmium
import shapefile
from shapely.geometry import box as shape_box, shape as shape_from_json

TILE = 256
MIN_Z, MAX_Z = 9, 16
EXTENT = (103.54, 1.12, 104.19, 1.52)  # W,S,E,N: SG and Johor approach.

# Day-style colors reminiscent of traditional OSM street cartography.
SEA = (175, 211, 226)
LAND = (247, 246, 239)
URBAN = (231, 232, 228)
INDUSTRIAL = (222, 220, 219)
PARK = (190, 224, 168)
FOREST = (169, 212, 157)
WATER = (174, 212, 229)
LOCAL_ROAD = (255, 255, 251)
LOCAL_EDGE = (210, 210, 205)
MINOR_ROAD = (255, 235, 194)
MINOR_EDGE = (220, 194, 155)
MAJOR_ROAD = (255, 195, 135)
MAJOR_EDGE = (218, 157, 123)
EXPRESSWAY = (239, 151, 172)
EXPRESS_EDGE = (194, 102, 134)
LABEL = (84, 88, 87)
LABEL_HALO = (255, 255, 250)
WATER_LABEL = (82, 132, 164)


def pixel(lat: float, lon: float, zoom: int) -> tuple[float, float]:
    n = (1 << zoom) * TILE
    lat = max(-85.0, min(85.0, lat))
    return (lon + 180.0) / 360.0 * n, (1 - asinh(tan(radians(lat))) / pi) * n / 2


def in_bbox(box1, box2=EXTENT) -> bool:
    return box1[0] <= box2[2] and box1[2] >= box2[0] and box1[1] <= box2[3] and box1[3] >= box2[1]


def tile_ranges(bbox, zoom):
    x1, y1 = pixel(bbox[1], bbox[0], zoom)
    x2, y2 = pixel(bbox[3], bbox[2], zoom)
    return (floor(min(x1, x2) / TILE), floor(max(x1, x2) / TILE),
            floor(min(y1, y2) / TILE), floor(max(y1, y2) / TILE))


def iter_rings(geometry) -> list[list[tuple[float, float]]]:
    """GeoJSON Polygon/MultiPolygon to (lat, lon) rings. Preserve holes separately."""
    geotype = geometry.get("type")
    coordinates = geometry.get("coordinates", [])
    parts = coordinates if geotype == "MultiPolygon" else [coordinates] if geotype == "Polygon" else []
    rings = []
    for polygon in parts:
        for points in polygon[:1]:  # Exterior; tiny holes are omitted in this raster cartography.
            ring = [(float(y), float(x)) for x, y in points if
                    EXTENT[0] - .1 <= x <= EXTENT[2] + .1 and EXTENT[1] - .1 <= y <= EXTENT[3] + .1]
            if len(ring) >= 3:
                rings.append(ring)
    return rings


def ring_bbox(rings):
    lat = [p[0] for ring in rings for p in ring]
    lon = [p[1] for ring in rings for p in ring]
    return min(lon), min(lat), max(lon), max(lat)


@dataclass
class PolygonFeature:
    kind: str
    rings: list
    bbox: tuple


@dataclass
class LabelPoint:
    lat: float
    lon: float
    name: str
    rank: int


def kind_of(tags):
    natural = tags.get("natural", "")
    landuse = tags.get("landuse", "")
    leisure = tags.get("leisure", "")
    if natural in {"water", "bay", "wetland"} or tags.get("water") or landuse in {"reservoir", "basin"}:
        return "water"
    if natural in {"wood", "forest", "scrub"} or landuse in {"forest", "wood", "orchard"}:
        return "forest"
    if leisure in {"park", "garden", "nature_reserve", "recreation_ground", "golf_course"} or (
        landuse in {"grass", "meadow", "village_green", "recreation_ground"} or
        natural in {"grassland", "heath"}
    ):
        return "park"
    if landuse in {"industrial", "military", "railway"}:
        return "industrial"
    if landuse in {"residential", "commercial", "retail", "construction"}:
        return "urban"
    return None


class OSMCartography(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.fac = osmium.geom.GeoJSONFactory()
        self.polygons: list[PolygonFeature] = []
        self.labels: list[LabelPoint] = []
        self.road_labels: list[LabelPoint] = []
        self._places = set()
        self._roads = set()

    def area(self, a):
        tags = dict(a.tags)
        kind = kind_of(tags)
        if not kind:
            return
        try:
            geo = json.loads(self.fac.create_multipolygon(a))
            # Clip to Singapore area to prevent giant offshore/multipolygon loops
            # spanning the entire world, and keep tile drawing bounded.
            clipped = shape_from_json(geo).intersection(shape_box(*EXTENT))
            if clipped.is_empty:
                return
            shapes = [clipped] if clipped.geom_type == "Polygon" else (
                list(clipped.geoms) if hasattr(clipped, "geoms") else [])
            for p in shapes:
                if p.geom_type != "Polygon" or p.area < 1e-11:
                    continue
                rings = [[(float(y), float(x)) for x, y in p.exterior.coords]]
                self.polygons.append(PolygonFeature(kind, rings, ring_bbox(rings)))
        except (ValueError, RuntimeError, TypeError):
            # Skip malformed unassembled areas, never generate fake geometry.
            return

    def node(self, n):
        tags = dict(n.tags)
        label = tags.get("name:en", tags.get("name", "")).strip()
        place = tags.get("place", "")
        rank = {"city": 0, "town": 1, "suburb": 2, "village": 3,
                "neighbourhood": 4, "quarter": 5}.get(place)
        if rank is None or not label or len(label) > 45:
            return
        try:
            if not n.location.valid() or not (
                EXTENT[1] <= n.location.lat <= EXTENT[3] and
                EXTENT[0] <= n.location.lon <= EXTENT[2]):
                return
            key = (label.lower(), round(n.location.lat, 2), round(n.location.lon, 2))
            if key not in self._places:
                self._places.add(key)
                self.labels.append(LabelPoint(n.location.lat, n.location.lon, label, rank))
        except (ValueError, RuntimeError):
            pass

    def way(self, way):
        tags = dict(way.tags)
        cls = tags.get("highway", "")
        if cls not in {"motorway", "trunk", "primary", "secondary", "tertiary"}:
            return
        label = tags.get("name:en", tags.get("name", "")).strip()
        if not label or len(label) > 40:
            return
        try:
            positions = [(n.location.lat, n.location.lon) for n in way.nodes if n.location.valid()]
            if len(positions) < 2:
                return
            mid = positions[len(positions) // 2]
            if not (EXTENT[1] <= mid[0] <= EXTENT[3] and EXTENT[0] <= mid[1] <= EXTENT[2]):
                return
            key = (label.lower(), round(mid[0], 2), round(mid[1], 2))
            if key not in self._roads:
                self._roads.add(key)
                self.road_labels.append(LabelPoint(mid[0], mid[1], label, 5))
        except (ValueError, RuntimeError):
            pass


def natural_earth_land(shp: Path):
    """Natural Earth public-domain 10m land, cropped to one small area."""
    reader = shapefile.Reader(str(shp))
    clip = shape_box(*EXTENT)
    result = []
    for record in reader.shapes():
        box = record.bbox  # W,S,E,N
        if not in_bbox(box):
            continue
        geom = shape_from_json(record.__geo_interface__).intersection(clip)
        parts = [geom] if geom.geom_type == "Polygon" else (
            list(geom.geoms) if hasattr(geom, "geoms") else [])
        for p in parts:
            if p.geom_type != "Polygon" or p.area < 1e-10:
                continue
            ring = [(float(y), float(x)) for x, y in p.exterior.coords]
            result.append(PolygonFeature("land", [ring], ring_bbox([ring])))
    reader.close()
    if not result:
        raise RuntimeError("Natural Earth coastal land source has no coverage for Singapore")
    return result


def segments_from_graph(file: Path):
    """Avoid external map services and use the existing verified offline road geometry."""
    buf = file.read_bytes()
    magic, version, nodes, edges, turns, *_ = struct.unpack_from("<4s7i", buf)
    if magic != b"SGR1" or version not in (1, 2) or nodes < 1000:
        raise ValueError("SGR2 road network is missing or invalid")
    node = struct.Struct("<4i")
    edge = struct.Struct("<4i")
    nbase = 32
    ebase = nbase + nodes * 16
    coords = [node.unpack_from(buf, nbase + i * 16) for i in range(nodes)]
    for i, (lat, lon, first, count) in enumerate(coords):
        for j in range(first, first + (count & 0x7FFFFFFF)):
            target, metres, seconds, _ = edge.unpack_from(buf, ebase + j * 16)
            lt, ln, *_ = coords[target]
            if abs(lat - lt) > 1_000_000 or abs(lon - ln) > 1_000_000:
                continue
            # Classes derived from estimated driving speed; PBF supplies detailed land-use.
            kmh = metres * 3.6 / max(1, seconds)
            kind = "express" if kmh >= 65 else "major" if kmh >= 46 else (
                "minor" if kmh >= 30 else "local")
            yield (lat / 1e7, lon / 1e7, lt / 1e7, ln / 1e7, kind)


def font(size, bold=False):
    root = "/usr/share/fonts/truetype/dejavu/"
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    try:
        return ImageFont.truetype(root + name, size)
    except OSError:
        return ImageFont.load_default()


def draw_polygon(draw, feature, zoom, tx, ty):
    for ring in feature.rings:
        pts = [(round(pixel(lat, lon, zoom)[0] - tx * TILE),
                round(pixel(lat, lon, zoom)[1] - ty * TILE)) for lat, lon in ring]
        if len(pts) >= 3:
            draw.polygon(pts, fill={
                "land": LAND, "urban": URBAN, "industrial": INDUSTRIAL,
                "park": PARK, "forest": FOREST, "water": WATER,
            }[feature.kind])


def draw_roads(draw, roads, zoom, casings):
    for x1, y1, x2, y2, cls in roads:
        if zoom <= 10 and cls in {"local", "minor"}:
            continue
        if zoom <= 12 and cls == "local":
            continue
        palette = {
            "local": (LOCAL_ROAD, LOCAL_EDGE),
            "minor": (MINOR_ROAD, MINOR_EDGE),
            "major": (MAJOR_ROAD, MAJOR_EDGE),
            "express": (EXPRESSWAY, EXPRESS_EDGE)
        }[cls]
        width = {
            "local": (0 if zoom < 13 else 1 if zoom < 15 else 2),
            "minor": (1 if zoom < 13 else 2 if zoom < 15 else 3),
            "major": (1 if zoom < 11 else 2 if zoom < 14 else 4),
            "express": (2 if zoom < 12 else 3 if zoom < 14 else 5)
        }[cls]
        if width == 0:
            continue
        draw.line((x1, y1, x2, y2), fill=palette[1 if casings else 0],
                  width=width + (2 if casings and zoom >= 12 else 0))


def render(graph: Path, output: Path, pbf: Path, land_shp: Path):
    print("Extracting geographic park, land-use, water and place labels…", flush=True)
    feature_handler = OSMCartography()
    feature_handler.apply_file(str(pbf), locations=True, idx="flex_mem")
    layers = natural_earth_land(land_shp) + feature_handler.polygons
    # Ordering: coastal land -> built-up zones -> green landuse -> rivers/lakes.
    order = {"land": 0, "urban": 1, "industrial": 2, "park": 3,
             "forest": 4, "water": 5}
    layers.sort(key=lambda x: order[x.kind])
    print("Visual features:", len(layers), "labels:", len(feature_handler.labels),
          "roads named:", len(feature_handler.road_labels), flush=True)
    labels = feature_handler.labels + feature_handler.road_labels

    if output.exists():
        output.unlink()
    db = sqlite3.connect(str(output))
    db.execute("PRAGMA journal_mode = OFF")
    db.execute("PRAGMA synchronous = OFF")
    db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    db.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    db.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
    metadata = {
        "name": "SG Delivery Singapore Daylight Offline Map",
        "format": "png", "type": "baselayer", "scheme": "tms",
        "minzoom": str(MIN_Z), "maxzoom": str(MAX_Z),
        "bounds": ",".join(str(x) for x in EXTENT),
        "attribution": "© OpenStreetMap contributors (ODbL) · Natural Earth (Public Domain)",
        "description": "Light/day offline cartographic map: roads, land, water, green space, urban land-use and place labels. Not a full OSM Carto replica."
    }
    db.executemany("INSERT INTO metadata VALUES (?,?)", metadata.items())
    db.commit()

    for z in range(MIN_Z, MAX_Z + 1):
        roads = defaultdict(list)
        for lat1, lon1, lat2, lon2, cls in segments_from_graph(graph):
            x1, y1 = pixel(lat1, lon1, z)
            x2, y2 = pixel(lat2, lon2, z)
            xlo = floor(min(x1, x2) / TILE)
            xhi = floor(max(x1, x2) / TILE)
            ylo = floor(min(y1, y2) / TILE)
            yhi = floor(max(y1, y2) / TILE)
            if xhi - xlo > 2 or yhi - ylo > 2:
                continue
            for tx in range(xlo, xhi + 1):
                for ty in range(ylo, yhi + 1):
                    roads[(tx, ty)].append((round(x1 - tx * TILE), round(y1 - ty * TILE),
                                            round(x2 - tx * TILE), round(y2 - ty * TILE), cls))
        tiles = set(roads)
        # At overview zooms show the whole Singapore coastline, even sea-only cells.
        if z <= 12:
            low_x, high_x, low_y, high_y = tile_ranges(EXTENT, z)
            tiles.update((x, y) for x in range(low_x, high_x + 1)
                         for y in range(low_y, high_y + 1))

        areas = defaultdict(list)
        for feature in layers:
            lo_x, hi_x, lo_y, hi_y = tile_ranges(feature.bbox, z)
            if hi_x - lo_x > 250 or hi_y - lo_y > 250:
                continue
            for tx in range(lo_x, hi_x + 1):
                for ty in range(lo_y, hi_y + 1):
                    if (tx, ty) in tiles:
                        areas[(tx, ty)].append(feature)

        places = defaultdict(list)
        for label in labels:
            if label.rank == 5 and z < 14:
                continue
            if label.rank >= 4 and z < 14:
                continue
            if label.rank == 3 and z < 12:
                continue
            if label.rank == 2 and z < 11:
                continue
            x, y = pixel(label.lat, label.lon, z)
            point = (floor(x / TILE), floor(y / TILE))
            if point in tiles:
                places[point].append((x - point[0] * TILE, y - point[1] * TILE, label))
        small = font(10 if z <= 12 else 11)
        big = font(14 if z <= 11 else 12, bold=True)
        count = 0
        db.execute("BEGIN TRANSACTION")
        try:
            for tx, ty in sorted(tiles):
                canvas = Image.new("RGB", (TILE, TILE), SEA)
                draw = ImageDraw.Draw(canvas)
                for feat in areas.get((tx, ty), []):
                    draw_polygon(draw, feat, z, tx, ty)
                selected = roads.get((tx, ty), [])
                selected.sort(key=lambda v: {"local": 0, "minor": 1, "major": 2, "express": 3}[v[4]])
                draw_roads(draw, selected, z, True)
                draw_roads(draw, selected, z, False)

                placed = []
                for px, py, label in sorted(places.get((tx, ty), []), key=lambda v: v[2].rank):
                    if len(placed) >= 13 or any(hypot(px - a, py - b) < 65 for a, b in placed):
                        continue
                    if label.rank == 5 and z < 15:
                        continue
                    chosen = big if label.rank <= 1 else small
                    fill = WATER_LABEL if "bay" in label.name.lower() else LABEL
                    draw.text((px, py), label.name, fill=fill, anchor="mm", font=chosen,
                              stroke_width=2, stroke_fill=LABEL_HALO)
                    placed.append((px, py))
                output_image = BytesIO()
                canvas.save(output_image, format="PNG", optimize=True, compress_level=6)
                db.execute("INSERT INTO tiles VALUES (?,?,?,?)",
                           (z, tx, (1 << z) - ty - 1, output_image.getvalue()))
                count += 1
            db.commit()
        except Exception:
            db.rollback()
            raise
        print("Zoom", z, "daylight cartographic tiles", count, flush=True)
    db.execute("PRAGMA journal_mode = DELETE")
    integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
    db.close()
    if integrity != "ok":
        raise RuntimeError("Generated daylight MBTiles failed SQLite integrity check")
    print("Daylight MBTiles", output, output.stat().st_size, "bytes", flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("graph", type=Path)
    ap.add_argument("--pbf", type=Path, required=True)
    ap.add_argument("--land-shp", type=Path, required=True)
    ap.add_argument("--output", type=Path, default=Path("sg-road-map.mbtiles"))
    a = ap.parse_args()
    render(a.graph, a.output, a.pbf, a.land_shp)
