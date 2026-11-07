import os
import time
import unittest

from fastapi.testclient import TestClient
import server


def road():
    return [{
        "id": "sg-test-way", "properties": {"highway": "primary", "oneway": "no"},
        "geometry": {"type": "LineString", "coordinates": [[103.8000, 1.3000], [103.8010, 1.3000]]},
    }]


class SharedTrafficTests(unittest.TestCase):
    def setUp(self):
        self.engine = server.TrafficEngine(road())
        self.base = int(time.time() * 1000)

    def observation(self, i, *, speed=10.0, direction=90.0, driving=True, delivery=False):
        return server.GPSObservation(
            latitude=1.3, longitude=103.80005 + i * 0.00006,
            speedKph=speed, accuracyM=5.0, bearingDeg=direction,
            timestampMs=self.base + i * 4_000,
            driving=driving, deliveryActivity=delivery,
        )

    def batch(self, session, i, **kwargs):
        return server.Batch(sessionId=session, observations=[self.observation(i, **kwargs)])

    def populate(self):
        for i in range(6):
            session = "runneraaaaaaaaaaaaa" if i % 2 == 0 else "runnerbbbbbbbbbbbbb"
            self.engine.receive(self.batch(session, i), self.base + i * 4_000)

    def test_two_real_runners_confirm_slow_road(self):
        self.populate()
        segments = self.engine.snapshot((103.79, 1.29, 103.82, 1.31), self.base + 23_000)
        assert len(segments) == 1
        assert segments[0]["level"] == "JAMMED"
        assert segments[0]["runnerCount"] == 2
        assert segments[0]["speedKph"] == 10.0

    def test_one_runner_cannot_mark_road_red(self):
        for i in range(6):
            self.engine.receive(self.batch("onlyonerunneraaaa", i), self.base + i * 4_000)
        segments = self.engine.snapshot((103.79, 1.29, 103.82, 1.31), self.base + 23_000)
        assert segments[0]["level"] == "UNKNOWN"

    def test_parked_and_delivery_activity_not_accepted(self):
        assert self.engine.receive(self.batch("runneraaaaaaaaaaaaa", 0, driving=False), self.base) == 0
        assert self.engine.receive(self.batch("runneraaaaaaaaaaaaa", 1, delivery=True), self.base + 4_000) == 0
        assert self.engine.snapshot((103.79, 1.29, 103.82, 1.31), self.base + 4_000) == []

    def test_wrong_direction_separated(self):
        forward = self.engine.match(self.observation(0))
        backward = self.engine.match(self.observation(0, direction=270))
        assert forward is not None and backward is not None
        assert forward.segment_id.endswith(":f")
        assert backward.segment_id.endswith(":r")

    def test_stale_observations_expire(self):
        self.populate()
        self.engine.prune(self.base + server.TTL_MS + 30_000)
        assert self.engine.snapshot((103.79, 1.29, 103.82, 1.31), self.base + server.TTL_MS + 30_000) == []

    def test_http_auth_validation_and_shared_snapshot(self):
        original = server.engine
        previous_token = os.environ.get("SG_TRAFFIC_PILOT_TOKEN")
        os.environ["SG_TRAFFIC_PILOT_TOKEN"] = "integration-pilot-only"
        server.engine = self.engine
        try:
            client = TestClient(server.app)
            data = {"sessionId": "runneraaaaaaaaaaaaa", "observations": [self.observation(0).model_dump()]}
            assert client.post("/v1/traffic/observations", json=data).status_code == 401
            ok = client.post("/v1/traffic/observations", json=data,
                             headers={"X-Pilot-Key": "integration-pilot-only"})
            assert ok.status_code == 200 and ok.json()["accepted"] == 1
            response = client.get("/v1/traffic/segments",
                                  params={"west": 103.79, "south": 1.29, "east": 103.82, "north": 1.31},
                                  headers={"X-Pilot-Key": "integration-pilot-only"})
            assert response.status_code == 200
            assert response.json()["segments"][0]["level"] == "UNKNOWN"
            assert client.get("/v1/traffic/segments",
                              params={"west": 103.82, "south": 1.29, "east": 103.79, "north": 1.31},
                              headers={"X-Pilot-Key": "integration-pilot-only"}).status_code == 400
        finally:
            server.engine = original
            if previous_token is None:
                os.environ.pop("SG_TRAFFIC_PILOT_TOKEN", None)
            else:
                os.environ["SG_TRAFFIC_PILOT_TOKEN"] = previous_token


if __name__ == "__main__":
    unittest.main()
