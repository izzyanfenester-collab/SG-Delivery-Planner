"""Build SG Delivery's account-free Singapore postal lookup seed.

Usage: python3 scripts/build_postal_seed.py [--source-file /path/to.csv]
The pinned upstream February 2026 snapshot is used only as a bootstrap;
the Android app checks for a newer *complete* public snapshot weekly.
"""

import argparse
import csv
import hashlib
import os
from pathlib import Path
import sqlite3
import tempfile
from urllib.request import urlopen


SOURCE_URL = (
    "https://raw.githubusercontent.com/dhanyyudi/onemap-building-sg/"
    "7ff07ccb7699ea38979d1e865bf9ae7fbce62c32/data/onemap_15022026.csv"
)
SOURCE_SHA256 = "674d1167697029029691438f44a495c59925b2c006e05e49248c82bf16594797"
VERSION = 20260215
SCHEMA = """
CREATE TABLE postals (
    postal TEXT PRIMARY KEY NOT NULL,
    block TEXT NOT NULL,
    street TEXT NOT NULL,
    name TEXT NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL
);
CREATE TABLE metadata (version INTEGER NOT NULL, postal_count INTEGER NOT NULL);
"""


def read_source(path: str | None) -> bytes:
    if path:
        content = Path(path).read_bytes()
    else:
        with urlopen(SOURCE_URL, timeout=90) as response:
            content = response.read()
    digest = hashlib.sha256(content).hexdigest()
    if digest != SOURCE_SHA256:
        raise RuntimeError(f"Upstream postal source changed unexpectedly: {digest}")
    return content


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-file", help="locally verified copy of the pinned public CSV")
    parser.add_argument(
        "--output", default="app/src/main/assets/sg_postal_seed.db", help="generated SQLite seed"
    )
    options = parser.parse_args()
    content = read_source(options.source_file)
    destination = Path(options.output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    handle, temp_name = tempfile.mkstemp(prefix="sg_postal_seed_", suffix=".db", dir=destination.parent)
    os.close(handle)
    try:
        connection = sqlite3.connect(temp_name)
        connection.executescript(SCHEMA)
        connection.execute("PRAGMA journal_mode=OFF")
        connection.execute("PRAGMA synchronous=OFF")
        parsed = csv.DictReader(content.decode("utf-8-sig").splitlines())
        rows = []
        for row in parsed:
            code = row["postal_code"].strip().zfill(6)
            if len(code) != 6 or not code.isdecimal():
                continue
            try:
                lat, lon = float(row["lat"]), float(row["lon"])
            except (ValueError, TypeError):
                continue
            if not 1.1 <= lat <= 1.6 or not 103.5 <= lon <= 104.1:
                continue
            rows.append((code, row["blk_no"].strip(), row["street"].strip(),
                         row["name"].strip(), lat, lon))
        connection.executemany("INSERT OR IGNORE INTO postals VALUES (?,?,?,?,?,?)", rows)
        count = connection.execute("SELECT COUNT(*) FROM postals").fetchone()[0]
        if count < 100_000:
            raise RuntimeError(f"Insufficient postal coverage: {count}")
        for postal in ["791436", "822446", "822204", "521227", "520259", "520135",
                       "520868", "461051", "320035", "310160"]:
            if connection.execute("SELECT 1 FROM postals WHERE postal=?", (postal,)).fetchone() is None:
                raise RuntimeError(f"Required test postal missing: {postal}")
        connection.execute("INSERT INTO metadata VALUES (?,?)", (VERSION, count))
        connection.commit()
        if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("Seed integrity check failed")
        connection.close()
        os.replace(temp_name, destination)
        print(f"Generated {destination} with {count:,} Singapore postal codes (version {VERSION}).")
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


if __name__ == "__main__":
    main()
