"""Local checks for the weekly data writer; no live network or API token."""

import csv
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from build_weekly_postals import valid, write_outputs


class WeeklyPostalTests(unittest.TestCase):
    def test_validation_accepts_only_singapore_postals_and_coordinates(self):
        self.assertTrue(valid("791436", 1.42, 103.84))
        self.assertFalse(valid("79143", 1.42, 103.84))
        self.assertFalse(valid("791436", 3.14, 101.69))

    def test_verified_compressed_csv_has_safe_one_line_records(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            records = {
                "791436": ("123", "Example, Street", 'HDB "Home"', 1.4234, 103.834, "base"),
                "822446": ("5", "Street\nWith line break", "New Building", 1.45, 103.90, "hdb"),
            }
            manifest = write_outputs(folder, records, {"base": 1, "hdb": 1},
                                     {"onemap_derived": "2026-11-01", "hdb": "HDB", "openstreetmap": "OSM"})
            archive = folder / "singapore-postals.csv.gz"
            self.assertEqual(manifest["postal_count"], 2)
            self.assertEqual(manifest["sha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
            self.assertEqual(json.loads((folder / "manifest.json").read_text())["sha256"], manifest["sha256"])
            with gzip.open(archive, mode="rt", encoding="utf-8", newline="") as source:
                rows = list(csv.DictReader(source))
            self.assertEqual([row["postal_code"] for row in rows], ["791436", "822446"])
            self.assertEqual(rows[0]["name"], 'HDB "Home"')
            self.assertEqual(rows[1]["street"], "Street With line break")


if __name__ == "__main__":
    unittest.main()
