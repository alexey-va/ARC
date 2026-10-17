import unittest
from verify_arc_dungeons_artifact_split import (
    PAPER_API_VISUAL_CLASS,
    ARC_CORE_RUNTIME_CLASS_PREFIXES,
    ARC_CORE_RUNTIME_PACKAGES,
    PROVIDER_CLASSES,
)
from verify_arc_domain_artifact_split import (
    DOMAINS,
    verify,
)

ARC_CORE_ENTRIES = {
    "ru/arc/paper/api/ArcRedisService.class",
    "ru/arc/paper/api/ArcRedisSettings.class",
    "ru/arc/paper/api/ArcSqlService.class",
    "ru/arc/paper/api/ArcSqlSettings.class",
    "ru/arc/paper/api/ArcMetricsService.class",
    "ru/arc/paper/api/ArcMetricCounter.class",
    "ru/arc/paper/api/ArcSchematicService.class",
    "ru/arc/paper/api/ArcNativeCleanupService.class",
    "ru/arc/paper/api/ArcBlockProtectionService.class",
    "ru/arc/paper/api/ArcBlockProtectionService$DefaultImpls.class",
    "ru/arc/paper/api/ArcVisualPacketBudget.class",
    "ru/arc/paper/api/ArcAfkProvider.class",
    PAPER_API_VISUAL_CLASS,
    *(f"{provider}.class" for provider in PROVIDER_CLASSES),
    *(f"{prefix}.class" for prefix in ARC_CORE_RUNTIME_CLASS_PREFIXES),
    *(f"{package}CoreType.class" for package in ARC_CORE_RUNTIME_PACKAGES),
}
ARC_DESCRIPTOR = "name: ARC\ndepend:\n  - ArcCore\n  - packetevents"
ARC_CORE_DESCRIPTOR = "name: ArcCore\nmain: ru.arc.coreplugin.ArcCorePlugin"


class ArtifactOwnershipTest(unittest.TestCase):
    def test_runtime_owner_contract_and_provider_duplicates(self):
        for name, (domain, entry, service, resources) in DOMAINS.items():
            with self.subTest(plugin=name):
                api = f"ru/arc/{domain}/api/{service}.class"
                host = {"ru/arc/ARC.class", api, "ru/arc/paper/api/ArcSidebarService.class"}
                child = {f"ru/arc/{domain}/{entry}.class", *resources}
                descriptor = f"name: {name}\ndepend: [ARC]"
                self.assertEqual([], verify(host, ARC_CORE_ENTRIES, child, ARC_DESCRIPTOR, ARC_CORE_DESCRIPTOR, descriptor, name))
                self.assertEqual(
                    [],
                    verify(
                        host,
                        ARC_CORE_ENTRIES,
                        child,
                        ARC_DESCRIPTOR,
                        ARC_CORE_DESCRIPTOR,
                        f"name: {name}\ndepend:\n  - ARC",
                        name,
                    ),
                )
                self.assertTrue(verify(host, ARC_CORE_ENTRIES, child, ARC_DESCRIPTOR, ARC_CORE_DESCRIPTOR,
                                       f"name: {name}\nsoftdepend: [ARC]", name))
                self.assertTrue(verify(host | {f"ru/arc/{domain}/State.class"}, ARC_CORE_ENTRIES, child,
                                       ARC_DESCRIPTOR, ARC_CORE_DESCRIPTOR, descriptor, name))
                self.assertTrue(verify(host, ARC_CORE_ENTRIES, child | {api}, ARC_DESCRIPTOR,
                                       ARC_CORE_DESCRIPTOR, descriptor, name))
                self.assertTrue(verify(host, ARC_CORE_ENTRIES,
                                       child | {"ru/arc/paper/sql/PaperSqlServiceProvider.class"},
                                       ARC_DESCRIPTOR, ARC_CORE_DESCRIPTOR, descriptor, name))
                self.assertTrue(verify(host, ARC_CORE_ENTRIES, child, ARC_DESCRIPTOR, ARC_CORE_DESCRIPTOR,
                                       f"name: {name}", name))
                self.assertTrue(verify(host, ARC_CORE_ENTRIES | {api}, child, ARC_DESCRIPTOR,
                                       ARC_CORE_DESCRIPTOR, descriptor, name))
                self.assertTrue(verify(host, ARC_CORE_ENTRIES, child, ARC_DESCRIPTOR,
                                       "name: ArcCore\ndepend: [ARC]", descriptor, name))


if __name__ == "__main__":
    unittest.main()
