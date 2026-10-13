#!/usr/bin/env python3
"""Small stdlib regression checks for the ArcDungeons artifact boundary."""

import unittest

from verify_arc_dungeons_artifact_split import verify


ARC_ENTRIES = {
    "ru/arc/ARC.class",
    "ru/arc/dungeons/DungeonHostServices.class",
    "ru/arc/paper/api/ArcRedisService.class",
    "ru/arc/paper/redis/PaperRedisServiceProvider.class",
    "ru/arc/paper/sql/PaperSqlServiceProvider.class",
    "ru/arc/paper/metrics/PaperArcMetricsProvider.class",
    "ru/arc/dungeons/api/ArcDungeonsService.class",
    "ru/arc/staffspells/api/ArcStaffSpellService.class",
}
DUNGEONS_ENTRIES = {
    "ru/arc/dungeons/ArcDungeonsPlugin.class",
    "ru/arc/dungeons/DungeonsServiceProvider.class",
    "ru/arc/hooks/elitemobs/EMHook.class",
    "ru/arc/eliteloot/EliteLootModule.class",
    "ru/arc/staffspells/StaffSpellsModule.class",
    "ru/arc/sync/EmSync.class",
    "ru/arc/enchanting/EliteEnchantingModule.class",
    "modules/elitemobs.yml",
    "modules/elitemobs-wormholes.yml",
}
DUNGEONS_DESCRIPTOR = """\
name: ArcDungeons
main: ru.arc.dungeons.ArcDungeonsPlugin
depend: [ARC]
"""


class ArtifactSplitTest(unittest.TestCase):
    def test_expected_split_passes(self) -> None:
        self.assertEqual(verify(ARC_ENTRIES, DUNGEONS_ENTRIES, DUNGEONS_DESCRIPTOR), [])

    def test_api_and_provider_duplicates_fail(self) -> None:
        child = DUNGEONS_ENTRIES | {
            "ru/arc/paper/api/ArcRedisService.class",
            "ru/arc/paper/redis/PaperRedisServiceProvider$Shared.class",
        }
        violations = verify(ARC_ENTRIES, child, DUNGEONS_DESCRIPTOR)
        self.assertTrue(any("shared API package ru/arc/paper/api/" in value for value in violations))
        self.assertTrue(any("provider implementation ru/arc/paper/redis/PaperRedisServiceProvider" in value for value in violations))

    def test_moved_implementation_and_descriptor_regressions_fail(self) -> None:
        arc = ARC_ENTRIES | {
            "ru/arc/hooks/elitemobs/EMHook.class",
            "ru/arc/eliteloot/EliteLootModule.class",
            "ru/arc/staffspells/StaffSpellsModule.class",
            "ru/arc/sync/EmSync.class",
            "ru/arc/enchanting/EliteEnchantingModule.class",
            "ru/arc/dungeons/DungeonsServiceProvider.class",
        }
        child = (DUNGEONS_ENTRIES - {"ru/arc/dungeons/DungeonHostServices.class"}) | {
            "ru/arc/dungeons/DungeonHostServices.class",
        }
        descriptor = DUNGEONS_DESCRIPTOR.replace("depend: [ARC]", "softdepend: [ARC]")
        violations = verify(arc, child, descriptor)
        self.assertTrue(any("moved package ru/arc/hooks/elitemobs/" in value for value in violations))
        self.assertTrue(any("moved implementation package ru/arc/eliteloot/" in value for value in violations))
        self.assertTrue(any("moved implementation package ru/arc/staffspells/" in value for value in violations))
        self.assertTrue(any("ru/arc/sync/EmSync*" in value for value in violations))
        self.assertTrue(any("ru/arc/enchanting/EliteEnchanting*" in value for value in violations))
        self.assertTrue(any("DungeonsServiceProvider" in value for value in violations))
        self.assertTrue(any("DungeonHostServices" in value for value in violations))
        self.assertTrue(any("hard dependency `depend: [ARC]`" in value for value in violations))


if __name__ == "__main__":
    unittest.main()
