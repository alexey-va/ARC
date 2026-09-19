import copy
from pathlib import Path
import unittest

import yaml

from mount_yard_riders import PATROLS, apply_rider_routes


class RiderRoutesTest(unittest.TestCase):
    def test_bundled_patrols_are_long_circuits_restricted_to_crops(self):
        source = Path(__file__).parents[1] / "src/main/resources/modules/origin-scenes.yml"
        scene = yaml.safe_load(source.read_text())["scenes"]["mount-yard"]
        regenerated = copy.deepcopy(scene)
        apply_rider_routes(regenerated)
        self.assertEqual(scene, regenerated)
        occupied = []
        for cycle_id, rider, vehicle, profile, points, _, _ in PATROLS:
            cycle = scene["cycles"][cycle_id]
            self.assertLessEqual(cycle["cooldown-max-seconds"], 3)
            moves = [step for step in cycle["steps"].values() if step["type"] == "MOVE"]
            self.assertEqual(len(moves), 2 * len(points))
            self.assertEqual({move["actor-id"] for move in moves}, {vehicle})
            self.assertEqual({move["route-profile"] for move in moves}, {profile})
            self.assertGreaterEqual(len({move["anchor"] for move in moves}), 8)
            self.assertEqual(scene["route-profiles"][profile]["allowed-support-materials"], ["FARMLAND"])
            self.assertEqual(scene["route-profiles"][profile]["surface-search-range"], 2)
            length = sum(abs(a[0] - b[0]) + abs(a[2] - b[2])
                         for a, b in zip(points, points[1:] + points[:1]))
            self.assertGreaterEqual(length, 40)
            self.assertEqual(scene["actors"][rider]["home"], scene["actors"][vehicle]["home"])
            self.assertEqual(scene["anchors"][moves[-1]["anchor"]], scene["actors"][vehicle]["home"])
            occupied.append({(point[0], point[2]) for point in points})
        self.assertFalse(occupied[0] & occupied[1])


if __name__ == "__main__":
    unittest.main()
