#!/usr/bin/env python3
"""Render a locally packaged road-only Singapore MBTiles basemap from SGR1.

Unlike tile-server scraping, all pixels come from the legally obtained OSM PBF
driving graph. No buildings/labels are synthesized: this is a *road-only* map.
OSMDroid can read this archive completely offline via OfflineTileProvider.
"""
from collections import defaultdict
from io import BytesIO
from math import asinh, pi, radians, tan, cos, log
from pathlib import Path
import argparse
import sqlite3
import struct
from PIL import Image, ImageDraw

SIZE = 256
MIN_Z, MAX_Z = 9, 16
NAVY = (16, 42, 76)
LOCAL = (150, 170, 196)
FAST = (219, 196, 116)
EXTENT = (103.54, 1.12, 104.19, 1.52)

def pixel(lat, lon, zoom):
    tiles = 1 << zoom
    x = (lon + 180.0) / 360.0 * tiles * SIZE
    phi = radians(lat)
    y = (1.0 - asinh(tan(phi)) / pi) / 2.0 * tiles * SIZE
    return x, y

def read_segments(path):
    blob = path.read_bytes()
    magic, version, nodes, edges, turns, *_ = struct.unpack_from("<4s7i", blob, 0)
    assert magic == b"SGR1" and version == 1 and nodes > 1000
    node = struct.Struct("<4i")
    edge = struct.Struct("<4i")
    positions = []
    offset = 32
    for index in range(nodes):
        lat, lon, first, degree = node.unpack_from(blob, offset + index * 16)
        positions.append((lat / 1e7, lon / 1e7))
    offset += nodes * 16
    for index, (lat, lon) in enumerate(positions):
        _, _, first, degree = node.unpack_from(blob, 32 + index * 16)
        if degree == 0:
            continue
        for e in range(first, first + degree):
            target, length, seconds, _ = edge.unpack_from(blob, offset + e * 16)
            lat2, lon2 = positions[target]
            if abs(lat2 - lat) > 0.1 or abs(lon2 - lon) > 0.1:
                continue
            speed = (length / seconds) * 3.6
            yield (lat, lon, lat2, lon2, speed)

def render(graph: Path, output: Path):
    con = sqlite3.connect(str(output))
    con.execute("PRAGMA journal_mode = OFF")
    con.execute("PRAGMA synchronous = OFF")
    con.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    con.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    con.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
    meta = {
        "name": "SG Delivery Singapore Offline Road Network",
        "format": "png", "type": "baselayer", "scheme": "tms",
        "minzoom": str(MIN_Z), "maxzoom": str(MAX_Z),
        "bounds": ",".join(str(x) for x in EXTENT),
        "attribution": "© OpenStreetMap contributors (ODbL)",
        "description": "Road-only Singapore basemap derived from licensed OSM data; not the full OSM cartographic basemap"
    }
    con.executemany("INSERT INTO metadata VALUES (?,?)", meta.items())
    con.commit()  # End the SQLite implicit metadata transaction before tile batches.
    # Re-open graph scan per zoom to bound heap use: only one level's tiles in memory.
    for zoom in range(MIN_Z, MAX_Z + 1):
        segments = defaultdict(list)
        for lat1, lon1, lat2, lon2, speed in read_segments(graph):
            x1, y1 = pixel(lat1, lon1, zoom)
            x2, y2 = pixel(lat2, lon2, zoom)
            left = int(min(x1, x2) // SIZE)
            right = int(max(x1, x2) // SIZE)
            top = int(min(y1, y2) // SIZE)
            bottom = int(max(y1, y2) // SIZE)
            if right - left > 2 or bottom - top > 2:
                continue
            major = speed >= 50
            for tx in range(left, right + 1):
                for ty in range(top, bottom + 1):
                    segments[(tx, ty)].append(
                        (round(x1 - tx * SIZE), round(y1 - ty * SIZE),
                         round(x2 - tx * SIZE), round(y2 - ty * SIZE), major))
        count = 0
        con.execute("BEGIN TRANSACTION")
        try:
            for (tx, ty), roads in segments.items():
                canvas = Image.new("RGB", (SIZE, SIZE), NAVY)
                draw = ImageDraw.Draw(canvas)
                for x1, y1, x2, y2, major in roads:
                    draw.line((x1, y1, x2, y2), fill=FAST if major else LOCAL,
                              width=2 if major and zoom >= 13 else 1)
                buffer = BytesIO()
                canvas.save(buffer, format="PNG", compress_level=5)
                con.execute("INSERT INTO tiles VALUES (?,?,?,?)",
                            (zoom, tx, (1 << zoom) - 1 - ty, buffer.getvalue()))
                count += 1
            con.commit()
        except Exception:
            con.rollback()
            raise
        print("Zoom", zoom, "offline tiles", count, flush=True)
    con.execute("PRAGMA journal_mode = DELETE")
    con.close()
    print("MBTiles", output, "bytes", output.stat().st_size, flush=True)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("graph", type=Path)
    p.add_argument("--output", type=Path, default=Path("sg-road-map.mbtiles"))
    a = p.parse_args()
    render(a.graph, a.output)

if __name__ == "__main__":
    main()
