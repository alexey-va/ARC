import unittest

from mount_yard_models import CARGO, SADDLE, model_steps, remove_steps


class MountYardModelTest(unittest.TestCase):
    def test_models_have_stable_unique_parts_and_bounded_entity_budget(self):
        for model in (SADDLE, CARGO):
            self.assertEqual(len(model), len({part.key for part in model}))
            self.assertLessEqual(len(model), 20)
            self.assertTrue(all(all(0 < axis < 2 for axis in part.scale) for part in model))

    def test_stages_build_complete_model_without_duplicate_entities(self):
        stages = {}
        for stage in range(4):
            stages.update(model_steps(SADDLE, "saddle", surface="bench", stage=stage))
        self.assertEqual(stages, model_steps(SADDLE, "saddle", surface="bench"))

    def test_handoff_reuses_keys_and_switches_parent(self):
        at_bench = model_steps(SADDLE, "saddle", surface="bench")
        carried = model_steps(SADDLE, "saddle", follow_actor=500, shift=(0, .9, .5))
        self.assertEqual([s["key"] for s in at_bench.values()], [s["key"] for s in carried.values()])
        self.assertTrue(all("surface" not in s and s["follow-actor-id"] == 500 for s in carried.values()))
        self.assertEqual({s["key"] for s in carried.values()},
                         {s["key"] for s in remove_steps(SADDLE, "saddle").values()})

    def test_attachment_needs_one_owner(self):
        with self.assertRaises(AssertionError):
            model_steps(CARGO, "cargo")
        with self.assertRaises(AssertionError):
            model_steps(CARGO, "cargo", anchor="rack", follow_actor=500)


if __name__ == "__main__":
    unittest.main()
