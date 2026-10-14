import unittest
from verify_arc_domain_artifact_split import DOMAINS, verify


class ArtifactOwnershipTest(unittest.TestCase):
    def test_runtime_owner_contract_and_provider_duplicates(self):
        for name, (domain, entry, service, resources) in DOMAINS.items():
            with self.subTest(plugin=name):
                api = f"ru/arc/{domain}/api/{service}.class"
                host = {"ru/arc/ARC.class", api}
                child = {f"ru/arc/{domain}/{entry}.class", *resources}
                descriptor = f"name: {name}\ndepend: [ARC]"
                self.assertEqual([], verify(host, child, descriptor, name))
                self.assertEqual([], verify(host, child, f"name: {name}\ndepend:\n  - ARC", name))
                self.assertTrue(verify(host, child, f"name: {name}\nsoftdepend:\n  - ARC", name))
                self.assertTrue(verify(host | {f"ru/arc/{domain}/State.class"}, child, descriptor, name))
                self.assertTrue(verify(host, child | {api}, descriptor, name))
                self.assertTrue(verify(host, child | {"ru/arc/paper/sql/PaperSqlServiceProvider.class"}, descriptor, name))
                self.assertTrue(verify(host, child, f"name: {name}", name))


if __name__ == "__main__":
    unittest.main()
