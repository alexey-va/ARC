#!/usr/bin/env python3
"""Small stdlib regression checks for the ArcDungeons artifact boundary."""

import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_arc_dungeons_artifact_split import (
    ARC_CORE_API_PREFIXES,
    ARC_CORE_RUNTIME_CLASS_PREFIXES,
    ARC_CORE_RUNTIME_PACKAGES,
    PAPER_API_VISUAL_CLASS,
    PROVIDER_CLASSES,
    verify,
    verify_shared_host_linkage,
)


ARC_ENTRIES = {
    "ru/arc/ARC.class",
    "ru/arc/redis/RedisOperations.class",
    "ru/arc/redis/ChannelListener.class",
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
    "ru/arc/paper/api/ArcBlockProtectionService.class",
    "ru/arc/paper/api/ArcBlockProtectionService$DefaultImpls.class",
    "ru/arc/paper/api/ArcVisualPacketBudget.class",
    PAPER_API_VISUAL_CLASS,
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


class SharedHostLinkageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        sources = {
            "kotlin/jvm/internal/DefaultConstructorMarker.java": """
                package kotlin.jvm.internal;
                public final class DefaultConstructorMarker {}
            """,
            "ru/arc/metrics/core/MetricPoint.java": """
                package ru.arc.metrics.core;
                public final class MetricPoint {
                    public MetricPoint(String name, String description, double value) {}
                    public MetricPoint(String name, String description, double value,
                        java.util.Map tags, int mask, kotlin.jvm.internal.DefaultConstructorMarker marker) {}
                }
            """,
            "ru/arc/paper/packet/PaperVisualPacketRuntime.java": """
                package ru.arc.paper.packet;
                public final class PaperVisualPacketRuntime {
                    public static PaperVisualPacketRuntime install() { return new PaperVisualPacketRuntime(); }
                    public void close() {}
                    public int snapshot() { return 0; }
                }
            """,
            "ru/arc/SafeConsumer.java": """
                package ru.arc;
                public final class SafeConsumer {
                    public Object sample(ru.arc.paper.packet.PaperVisualPacketRuntime runtime) {
                        return new ru.arc.metrics.core.MetricPoint("used", "used packets", runtime.snapshot());
                    }
                }
            """,
            "ru/arc/RuntimeOwner.java": """
                package ru.arc;
                public final class RuntimeOwner {
                    public void run() { ru.arc.paper.packet.PaperVisualPacketRuntime.install().close(); }
                }
            """,
            "ru/arc/LegacyMetricConsumer.java": """
                package ru.arc;
                public final class LegacyMetricConsumer {
                    public Object sample() {
                        return new ru.arc.metrics.core.MetricPoint("used", "used packets", 1, null, 8, null);
                    }
                }
            """,
        }
        files = []
        for relative, source in sources.items():
            path = cls.root / "src" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            files.append(str(path))
        subprocess.run(["javac", "-d", str(cls.root / "classes"), *files], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def verify_consumer(self, name: str) -> list[str]:
        path = self.root / f"{name}.jar"
        entry = f"ru/arc/{name}.class"
        with zipfile.ZipFile(path, "w") as archive:
            archive.write(self.root / "classes" / entry, entry)
        return verify_shared_host_linkage(path)

    def test_shared_runtime_read_and_explicit_metric_constructor_pass(self) -> None:
        self.assertEqual([], self.verify_consumer("SafeConsumer"))

    def test_consumer_cannot_install_or_close_host_runtime(self) -> None:
        violations = self.verify_consumer("RuntimeOwner")
        self.assertTrue(any("only ArcCore may install" in violation for violation in violations))
        self.assertTrue(any("only ArcCore may close" in violation for violation in violations))

    def test_private_kotlin_marker_cannot_cross_host_dto_boundary(self) -> None:
        self.assertTrue(any("DefaultConstructorMarker" in violation
                            for violation in self.verify_consumer("LegacyMetricConsumer")))


class ArtifactSplitTest(unittest.TestCase):
    def test_addon_cannot_embed_a_native_plugin_fork(self) -> None:
        violations = verify(
            ARC_ENTRIES,
            ARC_CORE_ENTRIES,
            DUNGEONS_ENTRIES | {"com/magmaguy/elitemobs/EliteMobs.class"},
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("addon must use the stock plugin" in value for value in violations))

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
        self.assertNotIn(PAPER_API_VISUAL_CLASS, ARC_ENTRIES)
        self.assertIn(PAPER_API_VISUAL_CLASS, ARC_CORE_ENTRIES)
        self.assertTrue(all(
            entry.rsplit("/", 1)[-1].startswith(ARC_CORE_API_PREFIXES)
            for entry in ARC_CORE_ENTRIES
            if entry.startswith("ru/arc/paper/api/")
        ))

        visual_in_arc = verify(
            ARC_ENTRIES | {PAPER_API_VISUAL_CLASS},
            ARC_CORE_ENTRIES,
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ARC JAR embeds ArcCore-owned Paper API class" in value for value in visual_in_arc))

        shared_api_in_arc = verify(
            ARC_ENTRIES | {"ru/arc/paper/api/ArcRedisSettings.class"},
            ARC_CORE_ENTRIES,
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("ARC JAR embeds ArcCore-owned Paper API class" in value for value in shared_api_in_arc))

        visual_missing_from_core = verify(
            ARC_ENTRIES,
            ARC_CORE_ENTRIES - {PAPER_API_VISUAL_CLASS},
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any("missing shared Paper API family VisualPacketAdmission" in value for value in visual_missing_from_core))

    def test_visual_budget_is_a_single_shared_host_contract(self) -> None:
        budget = "ru/arc/paper/api/ArcVisualPacketBudget.class"
        for api in (budget, PAPER_API_VISUAL_CLASS):
            with self.subTest(api=api):
                missing = verify(ARC_ENTRIES, ARC_CORE_ENTRIES - {api}, DUNGEONS_ENTRIES,
                                 ARC_DESCRIPTOR, ARC_CORE_DESCRIPTOR, DUNGEONS_DESCRIPTOR)
                self.assertTrue(any("missing shared Paper API family" in value for value in missing))
                duplicate = verify(ARC_ENTRIES, ARC_CORE_ENTRIES, DUNGEONS_ENTRIES | {api},
                                   ARC_DESCRIPTOR, ARC_CORE_DESCRIPTOR, DUNGEONS_DESCRIPTOR)
                self.assertTrue(any(f"ArcDungeons JAR embeds shared Paper API class {api}" in value for value in duplicate))
        for runtime in ("ru/arc/paper/packet/PaperVisualPacketRuntime", "ru/arc/metrics/core/MetricPoint"):
            duplicate = verify(ARC_ENTRIES | {f"{runtime}.class"}, ARC_CORE_ENTRIES, DUNGEONS_ENTRIES,
                               ARC_DESCRIPTOR, ARC_CORE_DESCRIPTOR, DUNGEONS_DESCRIPTOR)
            self.assertTrue(any(f"ARC JAR embeds ArcCore runtime class {runtime}" in value for value in duplicate))

    def test_block_protection_api_and_default_impls_are_host_owned(self) -> None:
        service = "ru/arc/paper/api/ArcBlockProtectionService.class"
        default_impls = "ru/arc/paper/api/ArcBlockProtectionService$DefaultImpls.class"
        self.assertEqual(
            [],
            verify(
                ARC_ENTRIES,
                ARC_CORE_ENTRIES,
                DUNGEONS_ENTRIES,
                ARC_DESCRIPTOR,
                ARC_CORE_DESCRIPTOR,
                DUNGEONS_DESCRIPTOR,
            ),
        )

        consumer_copy = DUNGEONS_ENTRIES | {service, default_impls}
        violations = verify(
            ARC_ENTRIES,
            ARC_CORE_ENTRIES,
            consumer_copy,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any(f"ArcDungeons JAR embeds shared Paper API class {service}" in value for value in violations))
        self.assertTrue(any(f"ArcDungeons JAR embeds shared Paper API class {default_impls}" in value for value in violations))

        unrelated_api = "ru/arc/paper/api/ArcSidebarService.class"
        violations = verify(
            ARC_ENTRIES,
            ARC_CORE_ENTRIES | {unrelated_api},
            DUNGEONS_ENTRIES,
            ARC_DESCRIPTOR,
            ARC_CORE_DESCRIPTOR,
            DUNGEONS_DESCRIPTOR,
        )
        self.assertTrue(any(f"ArcCore JAR embeds ARC-owned Paper API class {unrelated_api}" in value for value in violations))

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
