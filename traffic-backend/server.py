"""SG Delivery runner-speed traffic pilot backend.

Only real, validated phone observations are accepted. No commercial traffic feed.
The process is single-instance and keeps samples in volatile memory for 15 minutes.
Production deployment still requires authenticated runner enrollment and road testing.
"""
from __future__ import annotations

import json
import math
import os
import secrets
import statistics
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from pathlib import Path
from typing import Deque

from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field

SINGAPORE = (103.55, 1.15, 104.15, 1.49)  # west, south, east, north
TTL_MS = 15 * 60_000
WINDOW_MS = 10 * 60_000
CELL_DEGREES = 0.002
BASELINE_KPH = {
    "motorway": 80.0, "trunk": 65.0, "primary": 55.0,
    "secondary": 45.0, "tertiary": 40.0, "residential": 30.0,
    "living_street": 20.0, "service": 20.0,
}


def distance_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat_scale = 111_195.0
    lon_scale = lat_scale * math.cos(math.radians((a[0] + b[0]) / 2))
    return math.hypot((b[0] - a[0]) * lat_scale, (b[1] - a[1]) * lon_scale)


def bearing(a: tuple[float, float], b: tuple[float, float]) -> float:
    latitude = math.radians((a[0] + b[0]) / 2)
    north = b[0] - a[0]
    east = (b[1] - a[1]) * math.cos(latitude)
    return (math.degrees(math.atan2(east, north)) + 360) % 360


def angular_difference(a: float, b: float) -> float:
    d = abs((a - b) % 360)
    return min(d, 360 - d)


def perpendicular_distance(
    position: tuple[float, float], a: tuple[float, float], b: tuple[float, float]
) -> float:
    lat_scale = 111_195.0
    lon_scale = lat_scale * math.cos(math.radians(position[0]))
    ax, ay = (a[1] - position[1]) * lon_scale, (a[0] - position[0]) * lat_scale
    bx, by = (b[1] - position[1]) * lon_scale, (b[0] - position[0]) * lat_scale
    vx, vy = bx - ax, by - ay
    projection = max(0.0, min(1.0, -(ax * vx + ay * vy) / (vx * vx + vy * vy))) if vx * vx + vy * vy > 0 else 0.0
    return math.hypot(ax + projection * vx, ay + projection * vy)


@dataclass(frozen=True)
class Edge:
    segment_id: str
    first: tuple[float, float]
    last: tuple[float, float]
    free_flow_kph: float
    direction: float

    def in_bbox(self, box: tuple[float, float, float, float]) -> bool:
        west, south, east, north = box
        return (min(self.first[1], self.last[1]) <= east
                and max(self.first[1], self.last[1]) >= west
                and min(self.first[0], self.last[0]) <= north
                and max(self.first[0], self.last[0]) >= south)


@dataclass(frozen=True)
class Sample:
    received_ms: int
    event_ms: int
    speed_kph: float
    session: str


class GPSObservation(BaseModel):
    latitude: float = Field(ge=1.10, le=1.55)
    longitude: float = Field(ge=103.5, le=104.2)
    speedKph: float = Field(ge=0, le=180)
    accuracyM: float = Field(ge=0, le=100)
    bearingDeg: float = Field(ge=0, lt=360)
    timestampMs: int
    driving: bool
    deliveryActivity: bool = False


class Batch(BaseModel):
    sessionId: str = Field(min_length=16, max_length=100, pattern=r"^[A-Za-z0-9_-]+$")
    observations: list[GPSObservation] = Field(min_length=1, max_length=50)


class TrafficEngine:
    def __init__(self, features: list[dict]):
        self.edges: dict[str, Edge] = {}
        self.grid: dict[tuple[int, int], set[str]] = defaultdict(set)
        self.samples: dict[str, Deque[Sample]] = defaultdict(deque)
        self.recent_fix: dict[str, tuple[int, tuple[float, float]]] = {}
        for number, feature in enumerate(features):
            geo = feature.get("geometry") or {}
            if geo.get("type") != "LineString":
                continue
            coords = geo.get("coordinates") or []
            props = feature.get("properties") or {}
            road_id = str(props.get("osm_id") or feature.get("id") or number)
            road_class = str(props.get("highway") or "residential").split("_link")[0]
            free_flow = float(props.get("freeflow_kph") or BASELINE_KPH.get(road_class, 30.0))
            if not 8.0 <= free_flow <= 110.0:
                continue
            oneway = str(props.get("oneway", "")).lower() in ("yes", "1", "true")
            for index, (c1, c2) in enumerate(zip(coords, coords[1:])):
                try:
                    a, b = (float(c1[1]), float(c1[0])), (float(c2[1]), float(c2[0]))
                except (IndexError, TypeError, ValueError):
                    continue
                if distance_m(a, b) < 3 or distance_m(a, b) > 400:
                    continue
                if not (SINGAPORE[0] <= a[1] <= SINGAPORE[2]
                        and SINGAPORE[1] <= a[0] <= SINGAPORE[3]):
                    continue
                for direction, start, end in (
                    [("f", a, b)] if oneway else [("f", a, b), ("r", b, a)]
                ):
                    identifier = road_id + ":" + str(index) + ":" + direction
                    edge = Edge(identifier, start, end, free_flow, bearing(start, end))
                    self.edges[identifier] = edge
                    # Expand spatial index one cell either side of endpoints.
                    x1, x2 = sorted((int(a[1] / CELL_DEGREES), int(b[1] / CELL_DEGREES)))
                    y1, y2 = sorted((int(a[0] / CELL_DEGREES), int(b[0] / CELL_DEGREES)))
                    for x in range(x1 - 1, x2 + 2):
                        for y in range(y1 - 1, y2 + 2):
                            self.grid[x, y].add(identifier)

    def match(self, obs: GPSObservation) -> Edge | None:
        if not (SINGAPORE[0] <= obs.longitude <= SINGAPORE[2]
                and SINGAPORE[1] <= obs.latitude <= SINGAPORE[3]):
            return None
        cell = (int(obs.longitude / CELL_DEGREES), int(obs.latitude / CELL_DEGREES))
        nearby = self.grid.get(cell, ())
        scored = (
            (perpendicular_distance((obs.latitude, obs.longitude), e.first, e.last), e)
            for key in nearby if (e := self.edges[key])
            and angular_difference(obs.bearingDeg, e.direction) <= 55
        )
        best = min(scored, key=lambda pair: pair[0], default=None)
        if best is None or best[0] > min(28.0, max(12.0, obs.accuracyM * 1.6)):
            return None
        return best[1]

    def receive(self, batch: Batch, now_ms: int) -> int:
        accepted = 0
        for obs in batch.observations:
            if (not obs.driving or obs.deliveryActivity or obs.accuracyM > 18
                    or not (0.9 <= obs.speedKph / 3.6 <= 45.0)
                    or (obs.speedKph < 7.2 and obs.accuracyM > 8.0)
                    or abs(now_ms - obs.timestampMs) > 120_000):
                continue
            current = (obs.latitude, obs.longitude)
            previous = self.recent_fix.get(batch.sessionId)
            if previous:
                elapsed = obs.timestampMs - previous[0]
                # No observations from stationary runners or rapid duplicate pings.
                if elapsed < 2_500 or elapsed > 45_000 or distance_m(previous[1], current) < 3:
                    self.recent_fix[batch.sessionId] = (obs.timestampMs, current)
                    continue
            self.recent_fix[batch.sessionId] = (obs.timestampMs, current)
            edge = self.match(obs)
            if edge is None:
                continue
            self.samples[edge.segment_id].append(Sample(
                now_ms, obs.timestampMs, obs.speedKph, batch.sessionId))
            accepted += 1
        self.prune(now_ms)
        return accepted

    def prune(self, now_ms: int) -> None:
        for key in list(self.samples):
            readings = self.samples[key]
            while readings and now_ms - readings[0].received_ms > TTL_MS:
                readings.popleft()
            if not readings:
                del self.samples[key]
        # Discard per-session position as well, never persist a GPS trail.
        self.recent_fix = {key: fix for key, fix in self.recent_fix.items()
                           if now_ms - fix[0] <= TTL_MS}

    def snapshot(self, bbox: tuple[float, float, float, float], now_ms: int) -> list[dict]:
        self.prune(now_ms)
        records = []
        for key, readings in self.samples.items():
            edge = self.edges.get(key)
            if edge is None or not edge.in_bbox(bbox):
                continue
            usable = [r for r in readings if now_ms - r.received_ms <= WINDOW_MS]
            sessions = {r.session for r in usable}
            enough = (len(usable) >= 5 and len(sessions) >= 2
                      and max(r.event_ms for r in usable) - min(r.event_ms for r in usable) >= 15_000)
            speed = statistics.median(r.speed_kph for r in usable) if enough else None
            ratio = speed / edge.free_flow_kph if speed is not None else None
            level = ("SMOOTH" if ratio is not None and ratio >= 0.70 else
                     "SLOW" if ratio is not None and ratio >= 0.35 else
                     "JAMMED" if ratio is not None else "UNKNOWN")
            records.append({
                "id": key, "coordinates": [[edge.first[0], edge.first[1]],
                                           [edge.last[0], edge.last[1]]],
                "level": level, "speedKph": round(speed, 1) if speed is not None else None,
                "freeFlowKph": edge.free_flow_kph,
                "sampleCount": len(usable), "runnerCount": len(sessions),
                "updatedAtMs": readings[-1].received_ms,
            })
        return records[:2000]


def load_features() -> list[dict]:
    filename = os.getenv("SG_ROADS_GEOJSON", "")
    if not filename or not Path(filename).is_file():
        return []
    with open(filename, encoding="utf-8") as handle:
        document = json.load(handle)
    return document.get("features", [])


engine = TrafficEngine(load_features())
app = FastAPI(title="SG Delivery Runner Traffic", version="0.1.0")


def authorize(token: str | None) -> None:
    secret = os.getenv("SG_TRAFFIC_PILOT_TOKEN", "")
    if not secret:
        raise HTTPException(status_code=503, detail="Pilot authentication not configured")
    if token is None or not secrets.compare_digest(token, secret):
        raise HTTPException(status_code=401, detail="Unauthorized")


@app.get("/health")
def health():
    return {"ready": bool(engine.edges), "loadedDirectedEdges": len(engine.edges),
            "feed": "runner-observations-only", "storage": "volatile"}


@app.post("/v1/traffic/observations")
def ingest(batch: Batch, x_pilot_key: str | None = Header(default=None)):
    authorize(x_pilot_key)
    if not engine.edges:
        raise HTTPException(status_code=503, detail="Road network not loaded")
    return {"accepted": engine.receive(batch, int(time.time() * 1000))}


@app.get("/v1/traffic/segments")
def read_segments(
    west: float = Query(ge=103.5, le=104.2),
    south: float = Query(ge=1.10, le=1.55),
    east: float = Query(ge=103.5, le=104.2),
    north: float = Query(ge=1.10, le=1.55),
    x_pilot_key: str | None = Header(default=None),
):
    authorize(x_pilot_key)
    if west >= east or south >= north or east - west > 0.15 or north - south > 0.15:
        raise HTTPException(status_code=400, detail="Invalid bounding box")
    now = int(time.time() * 1000)
    return {"generatedAtMs": now,
            "coverage": "runner-pilot-only",
            "segments": engine.snapshot((west, south, east, north), now)}
