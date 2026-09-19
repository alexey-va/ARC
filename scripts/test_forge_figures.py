import unittest

from forge_figures import FIGURES, rectangles, cycle


class ForgeFiguresTest(unittest.TestCase):
    def test_rectangle_packing_preserves_materials_and_open_spaces(self):
        for cid, (_, _, _, rows) in FIGURES.items():
            with self.subTest(cycle=cid):
                self.assertTrue(all(len(row) == len(rows[0]) for row in rows))
                expected = {(x, z): c for z, row in enumerate(rows) for x, c in enumerate(row) if c != "."}
                actual = {}
                for c, x, z, width, depth in rectangles(rows):
                    for zz in range(z, z + depth):
                        for xx in range(x, x + width):
                            self.assertNotIn((xx, zz), actual, "Overlapping display faces")
                            actual[xx, zz] = c
                self.assertEqual(expected, actual)
                # A single connected silhouette: no floating handle or detached spear tip.
                pending = [next(iter(actual))]
                reached = set(pending)
                while pending:
                    x, z = pending.pop()
                    for neighbour in ((x-1, z), (x+1, z), (x, z-1), (x, z+1)):
                        if neighbour in actual and neighbour not in reached:
                            reached.add(neighbour)
                            pending.append(neighbour)
                self.assertEqual(set(actual), reached)

    def test_authored_steps_finish_without_blank_and_clean_up_under_entity_budget(self):
        for cid in FIGURES:
            with self.subTest(cycle=cid):
                visible = {}
                ticks = 0
                for sid, step in cycle(cid)["steps"].items():
                    if step["type"] == "BLOCK_DISPLAY":
                        visible[step["key"]] = step
                        self.assertLessEqual(len(visible), 48)
                        x, y, z = map(float, step["offset"].split(","))
                        width, height, depth = map(float, step["scale"].split(","))
                        self.assertLessEqual(abs(x) + width / 2, 0.45)
                        self.assertLessEqual(abs(z) + depth / 2, 0.45)
                        self.assertGreaterEqual(y, 0)
                        self.assertGreater(height, 0)
                    elif step["type"] == "REMOVE_DISPLAY":
                        self.assertIn(step["key"], visible)
                        del visible[step["key"]]
                    elif step["type"] == "SWING":
                        self.assertLessEqual(step["repetitions"], 20, "Runtime clamps larger values")
                        self.assertTrue(1 <= step["period-ticks"] <= 100)
                        ticks += step["repetitions"] * step["period-ticks"]
                    if sid == "finish":
                        self.assertTrue(visible)
                        self.assertFalse(any("blank" in key for key in visible))
                self.assertFalse(visible)
                self.assertGreaterEqual(ticks, 60 * 20)
                self.assertLessEqual(ticks, 120 * 20)


if __name__ == "__main__":
    unittest.main()
