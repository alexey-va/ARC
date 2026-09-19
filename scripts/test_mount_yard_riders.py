import copy
from pathlib import Path
import unittest

import yaml

from mount_yard_riders import PATROLS, apply_rider_routes


class RiderRoutesTest(unittest.TestCase):
    def test_bundled_patrols_match_authoring_and_stay_on_disjoint_field_lanes(self):
        source = Path(__file__).parents[1] / "src/main/resources/modules/origin-scenes.yml"
        scene = yaml.safe_load(source.read_text())["scenes"]["mount-yard"]
        regenerated = copy.deepcopy(scene)
        apply_rider_routes(regenerated)
        self.assertEqual(scene, regenerated)
        occupied = []
        for cycle_id, rider, vehicle, profile, (left, right, z), _, _ in PATROLS:
            cycle = scene["cycles"][cycle_id]
            self.assertLessEqual(cycle["cooldown-max-seconds"], 3)
            moves = [step for step in cycle["steps"].values() if step["type"] == "MOVE"]
            self.assertEqual(len(moves), 6)
            self.assertEqual({move["actor-id"] for move in moves}, {vehicle})
            self.assertEqual({move["route-profile"] for move in moves}, {profile})
            self.assertEqual(len({move["anchor"] for move in moves}), 2)
            self.assertEqual(scene["actors"][rider]["home"], scene["actors"][vehicle]["home"])
            self.assertEqual(scene["anchors"][moves[-1]["anchor"]], scene["actors"][vehicle]["home"])
            occupied.append({(x, z) for x in range(left, right + 1)})
        self.assertFalse(occupied[0] & occupied[1])


if __name__ == "__main__":
    unittest.main()
