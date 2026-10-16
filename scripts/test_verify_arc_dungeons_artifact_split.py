#!/usr/bin/env python3
"""Small stdlib regression checks for the ArcDungeons artifact boundary."""

import unittest

from verify_arc_dungeons_artifact_split import (
    ARC_CORE_API_PREFIXES,
    ARC_CORE_RUNTIME_CLASS_PREFIXES,
    ARC_CORE_RUNTIME_PACKAGES,
    PAPER_API_VISUAL_CLASS,
    PROVIDER_CLASSES,
    verify,
)


ARC_ENTRIES = {
    "ru/arc/ARC.class",
    "ru/arc/redis/RedisOperations.class",
    "ru/arc/redis/ChannelListener.class",
    PAPER_API_VISUAL_CLASS,
    "ru/arc/paper/api/ArcSidebarService.class",
    "ru/arc/paper/api/ArcItemMaterializer.class",
    "ru/arc/dungeons/DungeonHostServices.class",
    "ru/arc/dungeons/api/ArcDungeonsService.class",
    "ru/arc/staffspells/api/ArcStaffSpellService.class",
}
ARC_CORE_ENTRIES = {
    "ru/arc/paper/api/ArcRedisService.class",
    "ru/arc/paper/api/ArcRedisSettings.class",
    "ru/arc/paper/api/ArcSqlService.class",
    "ru/arc/paper/api/ArcSqlSettings.class",
    "ru/arc/paper/api/ArcMetricsService.class",
    "ru/arc/paper/api/ArcMetricCounter.class",
    "ru/arc/paper/api/ArcSchematicService.class",
    *(f"{provider}.class" for provider in PROVIDER_CLASSES),
    *(f"{prefix}.class" for prefix in ARC_CORE_RUNTIME_CLASS_PREFIXES),
    *(f"{package}CoreType.class" for package in ARC_CORE_RUNTIME_PACKAGES),
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
ARC_DESCRIPTOR = """\
name: ARC
depend:
  - ArcCore
  - packetevents
"""
ARC_CORE_DESCRIPTOR = """\
name: ArcCore
main: ru.arc.coreplugin.ArcCorePlugin
"""


class ArtifactSplitTest(unittest.TestCase):
    def test_expected_split_passes(self) -> None:
        self.assertEqual(
            verify(
                ARC_ENTRIES,
                ARC_CORE_ENTRIES,
                DUNGEONS_ENTRIES,
                ARC_DESCRIPTOR,
                ARC_CORE_DESCRIPTOR,
                DUNGEONS_DESCRIPTOR,
            ),
            [],
        )

    def test_api_and_provider_duplicates_fail(self) -> None:
        child = DUNGEONS_ENTRIES | {
            "ru/arc/paper/api/ArcRedisService.class",
            "ru/arc/paper/api/ArcSchematicService.class",
            "ru/arc/paper/redis/PaperRedisServiceProvider$Shared.class",
        }
        violations = verify(
            ARC_ENTRIES,
            ARC_CORE_ENTRIES,
            child,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ArcDungeons JAR embeds shared Paper API class" in value for value in violations))
        self.assertTrue(any("ArcSchematicService.class" in value for value in violations))
        self.assertTrue(any("provider implementation ru/arc/paper/redis/PaperRedisServiceProvider" in value for value in violations))

    def test_provider_api_families_belong_to_arc_core(self) -> None:
        self.assertIn(PAPER_API_VISUAL_CLASS, ARC_ENTRIES)
        self.assertNotIn(PAPER_API_VISUAL_CLASS, ARC_CORE_ENTRIES)
        self.assertTrue(all(
            entry.rsplit("/", 1)[-1].startswith(ARC_CORE_API_PREFIXES)
            for entry in ARC_CORE_ENTRIES
            if entry.startswith("ru/arc/paper/api/")
        ))

        visual_in_core = verify(
            ARC_ENTRIES,
            ARC_CORE_ENTRIES | {PAPER_API_VISUAL_CLASS},
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ArcCore JAR embeds ARC-owned Paper API class" in value for value in visual_in_core))

        shared_api_in_arc = verify(
            ARC_ENTRIES | {"ru/arc/paper/api/ArcRedisSettings.class"},
            ARC_CORE_ENTRIES,
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ARC JAR embeds ArcCore-owned Paper API class" in value for value in shared_api_in_arc))

        visual_missing_from_arc = verify(
            ARC_ENTRIES - {PAPER_API_VISUAL_CLASS},
            ARC_CORE_ENTRIES,
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ARC JAR is missing ARC-owned Paper API class" in value for value in visual_missing_from_arc))

    def test_arc_core_owns_shared_apis_and_providers(self) -> None:
        arc = ARC_ENTRIES | {
            "ru/arc/paper/api/ArcRedisService.class",
            "ru/arc/paper/sql/SharedPaperSqlService.class",
        }
        violations = verify(
            arc,
            ARC_CORE_ENTRIES,
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ARC JAR embeds ArcCore-owned Paper API class" in value for value in violations))
        self.assertTrue(any("ARC JAR embeds ArcCore provider implementation ru/arc/paper/sql/SharedPaperSqlService" in value for value in violations))

    def test_arc_core_owns_micrometer_and_redis_runtime_types(self) -> None:
        for prefix in ARC_CORE_RUNTIME_CLASS_PREFIXES:
            with self.subTest(prefix=prefix):
                violations = verify(
                    ARC_ENTRIES,
                    ARC_CORE_ENTRIES - {f"{prefix}.class"},
                    DUNGEONS_ENTRIES,
                    ARC_DESCRIPTOR,
                    ARC_CORE_DESCRIPTOR,
                    DUNGEONS_DESCRIPTOR,
                )
                self.assertTrue(any(f"missing shared runtime class {prefix}*" in value for value in violations))
        for package in ARC_CORE_RUNTIME_PACKAGES:
            with self.subTest(package=package):
                violations = verify(
                    ARC_ENTRIES,
                    ARC_CORE_ENTRIES - {f"{package}CoreType.class"},
                    DUNGEONS_ENTRIES,
                    ARC_DESCRIPTOR,
                    ARC_CORE_DESCRIPTOR,
                    DUNGEONS_DESCRIPTOR,
                )
                self.assertTrue(any(f"missing shared runtime package {package}" in value for value in violations))

    def test_arc_core_dependency_direction_is_required(self) -> None:
        violations = verify(
            ARC_ENTRIES,
            ARC_CORE_ENTRIES,
            DUNGEONS_ENTRIES,
            "name: ARC\ndepend: [packetevents]",
            "name: ArcCore\ndepend: [ARC]",
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ARC must declare a hard dependency on ArcCore" in value for value in violations))
        self.assertTrue(any("ArcCore must not depend on ARC" in value for value in violations))

        wrong_identity = verify(
            ARC_ENTRIES,
            ARC_CORE_ENTRIES,
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            "name: OtherPlugin\ndepend: [ARC]",
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ArcCore plugin descriptor must declare exactly name ArcCore" in value for value in wrong_identity))

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
        violations = verify(
            arc,
            ARC_CORE_ENTRIES,
            child,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            descriptor,
        )
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
