import copy
from pathlib import Path
import unittest

import yaml

from mount_yard_scene import living_scene, replace_section, HOMES


class MountYardSceneTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = Path(__file__).resolve().parents[1] / "src/main/resources/modules/origin-scenes.yml"
        cls.text = cls.source.read_text()
        cls.document = yaml.safe_load(cls.text)
        cls.scene = cls.document["scenes"]["mount-yard"]

    def test_generation_is_idempotent_and_does_not_touch_forge(self):
        self.assertEqual(self.text, replace_section(self.text))
        self.assertEqual(self.text.split("  mount-yard:\n")[0],
                         replace_section(self.text).split("  mount-yard:\n")[0])

    def test_every_new_actor_has_work_and_declared_home(self):
        for actor in HOMES:
            self.assertIn(str(actor), self.scene["actor-ids"])
            self.assertTrue(any(str(actor) in c["actor-ids"] for c in self.scene["cycles"].values()))

    def test_props_follow_only_leased_actors_and_every_step_is_listed(self):
        for cycle in self.scene["cycles"].values():
            self.assertEqual(list(cycle["steps"]), cycle["step-ids"])
            for step in cycle["steps"].values():
                if "follow-actor-id" in step:
                    self.assertIn(str(step["follow-actor-id"]), cycle["actor-ids"])
                    self.assertIn("follow-offset", step)
                    self.assertNotIn("offset", step)
                if step["type"] == "MOVE_GROUP":
                    self.assertEqual(len(step["actor-ids"]), len(step["anchors"]))
                    self.assertTrue(set(step["actor-ids"]).issubset(cycle["actor-ids"]))

    def test_saddle_has_source_carry_and_real_rack_support(self):
        steps = self.scene["cycles"]["saddler-work"]["steps"].values()
        displays = [s for s in steps if s["type"] == "BLOCK_DISPLAY"]
        self.assertEqual(19, len({s["key"] for s in displays}))
        self.assertEqual({"saddler-bench", "saddle-rack"},
                         {s["surface"] for s in displays if "surface" in s})
        self.assertEqual(19, len([s for s in displays if "follow-actor-id" in s]))

    def test_native_poses_only_used_for_compatible_authored_animals(self):
        for cycle in self.scene["cycles"].values():
            for step in cycle["steps"].values():
                if step["type"] != "POSE":
                    continue
                allowed = {"SIT": {443, 444}, "CAT_LIE": {443},
                           "HORSE_GRAZE": {371, 422, 423, 442}}
                if step["state"] in allowed:
                    self.assertIn(step["actor-id"], allowed[step["state"]])

    def test_caravan_uses_clear_aisle_and_retraces_loading_bay_exit(self):
        steps = self.scene["cycles"]["caravan-preparation"]["steps"]
        groups = {key: value for key, value in steps.items() if value["type"] == "MOVE_GROUP"}
        self.assertEqual(["out-exit", "out-corner", "out-middle", "out-front", "return-middle", "return-corner", "return-exit", "return"],
                         list(groups))
        self.assertEqual({"caravan"}, {step["route-profile"] for step in groups.values()})
        self.assertEqual(groups["out-exit"]["anchors"], groups["return-exit"]["anchors"])
        forbidden = self.scene["route-profiles"]["caravan"]["forbidden-areas"]
        self.assertEqual(["66,-140,76,-125", "62,-134,76,-125", "43,-140,57,-123"], forbidden)
        for move in groups.values():
            for anchor in move["anchors"]:
                x, _, z, *_ = map(float, self.scene["anchors"][anchor].split(","))
                for area in forbidden:
                    min_x, min_z, max_x, max_z = map(int, area.split(","))
                    self.assertFalse(min_x <= x < max_x + 1 and min_z <= z < max_z + 1, anchor)

    def test_paddock_routes_stay_inside_their_own_pen(self):
        for cycle_id, profile_id in (("buran-paddock", "west-pen"), ("ryzhik-paddock", "east-pen")):
            profile = self.scene["route-profiles"][profile_id]
            self.assertEqual(1, profile["snap-radius"])
            min_x, min_z = map(int, profile["min-block"].split(","))
            max_x, max_z = map(int, profile["max-block"].split(","))
            for step in self.scene["cycles"][cycle_id]["steps"].values():
                if step["type"] != "MOVE":
                    continue
                self.assertEqual(profile_id, step["route-profile"])
                x, _, z, *_ = map(float, self.scene["anchors"][step["anchor"]].split(","))
                self.assertTrue(min_x <= x < max_x + 1 and min_z <= z < max_z + 1, step)
        self.assertEqual("home-415", self.scene["cycles"]["bay-patrol"]["steps"]["home"]["anchor"])

    def test_source_model_does_not_mutate_input(self):
        before = copy.deepcopy(self.scene)
        living_scene(self.scene)
        self.assertEqual(before, self.scene)


if __name__ == "__main__":
    unittest.main()
