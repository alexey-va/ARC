import hashlib
import json
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[2] / "main" / "resources" / "scripts"
sys.path.insert(0, str(SCRIPT_DIR))

import resourcepack_variants as variants  # noqa: E402


def pack_metadata():
    return {
        "pack": {
            "pack_format": 75,
            "supported_formats": [32, 99],
            "min_format": [32, 0],
            "max_format": [99, 9],
            "description": "fixture pack",
        },
        "overlays": {
            "entries": [
                {"directory": "legacy", "formats": [84, 99]},
                {
                    "directory": "modern",
                    "min_format": {"major": 84, "minor": 0},
                    "max_format": [97, 1],
                },
                {"directory": "inactive", "formats": {"min": [88, 0], "max": [96, 0]}},
            ]
        },
    }


def make_atlas(sources):
    return json.dumps({"sources": sources, "future_root_field": {"kept": True}}, separators=(",", ":")).encode()


def write_pack(path, files):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class ResourcePackVariantsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.input_zip = self.root / "input.zip"
        self.catalog = self.root / "catalog.json"

    def tearDown(self):
        self.temporary.cleanup()

    def write_catalog(self, clients=None):
        if clients is None:
            clients = [{"version": "26.3", "protocol": 777, "format": [97, 1], "textures": ["minecraft:block/stone"]}]
        self.catalog.write_text(json.dumps({"clients": clients}), encoding="utf-8")

    def build(self, output="out", upload_name="RusCraftingResource.zip"):
        return variants.build_variants(self.input_zip, self.root / output, self.catalog, upload_name)

    def test_flattens_exact_overlay_stack_and_expands_with_filter_equivalence(self):
        atlas = make_atlas([
            {"type": "directory", "source": "items", "prefix": "custom/"},
            {"type": "directory", "source": "absent", "prefix": "unused/"},
            {"type": "filter", "pattern": {"namespace": "^demo$", "path": "^custom/a$"}},
            {"type": "minecraft:single", "resource": "demo:items/b", "sprite": "demo:custom/a"},
        ])
        write_pack(self.input_zip, {
            "pack.mcmeta": json.dumps(pack_metadata()).encode(),
            "README.txt": b"root readme",
            "modern/README.txt": b"active readme",
            "assets/demo/textures/items/base.png": b"base",
            "legacy/assets/demo/textures/items/a.png": b"legacy a",
            "legacy/assets/demo/textures/items/override.png": b"legacy override",
            "modern/assets/demo/textures/items/a.png": b"modern a",
            "modern/assets/demo/textures/items/b.png": b"modern b",
            "modern/assets/demo/textures/items/override.png": b"modern override",
            "modern/assets/demo/atlases/items.json": atlas,
            "inactive/assets/demo/textures/items/inactive.png": b"must omit",
        })
        input_hash = sha256(self.input_zip)
        self.write_catalog([
            {"version": "26.3", "protocol": 777, "format": [97, 1], "textures": ["minecraft:block/stone"]},
            {"version": "26.4", "protocol": 778, "format": [99, 0], "textures": []},
            {"version": "1.20.6", "protocol": 766, "format": 41, "textures": []},
        ])

        manifest = self.build()

        self.assertEqual([pack["file"] for pack in manifest["packs"]], [
            "RusCraftingResource.zip", "RusCraftingResource-26.3.zip",
        ])
        variant_path = self.root / "out" / manifest["packs"][1]["file"]
        with zipfile.ZipFile(variant_path) as archive:
            self.assertIsNone(archive.testzip())
            names = set(archive.namelist())
            self.assertNotIn("legacy/assets/demo/textures/items/a.png", names)
            self.assertNotIn("inactive/assets/demo/textures/items/inactive.png", names)
            self.assertNotIn("modern/assets/demo/textures/items/a.png", names)
            self.assertNotIn("modern/README.txt", names)
            self.assertEqual(archive.read("assets/demo/textures/items/a.png"), b"modern a")
            self.assertEqual(archive.read("assets/demo/textures/items/override.png"), b"modern override")
            self.assertEqual(archive.read("README.txt"), b"root readme")
            metadata = json.loads(archive.read("pack.mcmeta"))
            self.assertNotIn("overlays", metadata)
            self.assertEqual(metadata["pack"]["pack_format"], 97)
            self.assertEqual(metadata["pack"]["min_format"], [97, 1])
            self.assertEqual(metadata["pack"]["max_format"], [97, 1])
            self.assertNotIn("supported_formats", metadata["pack"])
            self.assertEqual(metadata["pack"]["description"], "fixture pack")
            atlas_json = json.loads(archive.read("assets/demo/atlases/items.json"))
            sources = atlas_json["sources"]
            self.assertEqual(atlas_json["future_root_field"], {"kept": True})
            self.assertEqual([source["type"] for source in sources], [
                "minecraft:single", "minecraft:single", "minecraft:single", "minecraft:single", "filter", "minecraft:single",
            ])
            self.assertEqual(
                [(source["resource"], source.get("sprite")) for source in sources[:4]],
                [
                    ("demo:items/a", "demo:custom/a"),
                    ("demo:items/b", "demo:custom/b"),
                    ("demo:items/base", "demo:custom/base"),
                    ("demo:items/override", "demo:custom/override"),
                ],
            )
        stats = manifest["packs"][1]["stats"]
        self.assertEqual(stats["activeOverlays"], ["legacy", "modern"])
        self.assertEqual(stats["layersBefore"], 3)
        self.assertEqual(stats["layersAfter"], 1)
        self.assertEqual(stats["directoriesExpanded"], 1)
        self.assertEqual(stats["emptyDirectories"], 1)
        self.assertEqual(stats["duplicateSourcesRemoved"], 0)
        self.assertEqual(sha256(self.input_zip), input_hash)

    def test_universal_deduplicates_only_exact_repeated_operation_blocks(self):
        directory = {"type": "directory", "source": "items", "prefix": "items/"}
        repeated_filter = {"type": "filter", "pattern": {"namespace": "^demo$", "path": "^items/a$"}}
        other_filter = {"type": "filter", "pattern": {"namespace": "^demo$", "path": "^items/b$"}}
        sources = [directory, repeated_filter, directory, repeated_filter, directory, other_filter, directory]
        write_pack(self.input_zip, {
            "pack.mcmeta": json.dumps({"pack": {"pack_format": 75, "min_format": 75, "max_format": 99}}).encode(),
            "assets/demo/textures/items/a.png": b"a",
            "assets/demo/textures/items/b.png": b"b",
            "assets/demo/atlases/blocks.json": make_atlas(sources),
        })
        self.write_catalog()

        manifest = self.build()

        with zipfile.ZipFile(self.root / "out" / "RusCraftingResource.zip") as archive:
            atlas_json = json.loads(archive.read("assets/demo/atlases/blocks.json"))
        self.assertEqual(len(atlas_json["sources"]), 5)
        self.assertEqual(manifest["packs"][0]["stats"]["duplicateSourcesRemoved"], 2)
        self.assertEqual(manifest["packs"][1]["stats"]["duplicateSourcesRemoved"], 2)

    def test_unknown_atlas_source_is_preserved_without_partial_rewrite(self):
        atlas = make_atlas([
            {"type": "directory", "source": "items", "prefix": "items/"},
            {"type": "future:procedural", "settings": {"mode": "future"}},
        ])
        write_pack(self.input_zip, {
            "pack.mcmeta": json.dumps({"pack": {"pack_format": 75, "min_format": 75, "max_format": 99}}).encode(),
            "assets/demo/textures/items/a.png": b"a",
            "assets/demo/atlases/blocks.json": atlas,
        })
        self.write_catalog()

        manifest = self.build()

        with zipfile.ZipFile(self.root / "out" / "RusCraftingResource-26.3.zip") as archive:
            self.assertEqual(archive.read("assets/demo/atlases/blocks.json"), atlas)
        self.assertEqual(manifest["packs"][1]["stats"]["atlasesSkipped"], 1)

    def test_pack_outputs_are_reproducible_and_catalog_formats_accept_tuple_objects(self):
        write_pack(self.input_zip, {
            "pack.mcmeta": json.dumps(pack_metadata()).encode(),
            "assets/demo/textures/items/a.png": b"a",
        })
        self.write_catalog([{
            "version": "26.3",
            "protocol": 777,
            "format": {"major": 97, "minor": 1},
            "textures": ["minecraft:block/stone"],
        }])
        original_hash = sha256(self.input_zip)

        first = self.build("out-one")
        second = self.build("out-two")

        self.assertEqual([p["sha256"] for p in first["packs"]], [p["sha256"] for p in second["packs"]])
        self.assertEqual(sha256(self.input_zip), original_hash)

    def test_missing_single_resource_does_not_replace_an_existing_sprite(self):
        textures = {"demo:items/present"}
        sources = [
            {"type": "single", "resource": "demo:items/present", "sprite": "demo:custom/shared"},
            {"type": "single", "resource": "demo:items/missing", "sprite": "demo:custom/shared"},
        ]
        self.assertEqual(
            variants._evaluate_sources(sources, textures),
            {"demo:custom/shared": "demo:items/present"},
        )

    def test_pack_range_rejects_unsupported_variant_format(self):
        write_pack(self.input_zip, {
            "pack.mcmeta": json.dumps({"pack": {"pack_format": 75, "supported_formats": [75, 84]}}).encode(),
        })
        self.write_catalog()

        with self.assertRaisesRegex(variants.PackError, "does not declare support"):
            self.build()

    def test_legacy_formats_range_dict_and_reversed_scalar_pair(self):
        value = {"min_inclusive": {"major": 97, "minor": 0}, "max_inclusive": [97, 1]}
        self.assertTrue(variants._overlay_is_active({"directory": "x", "formats": value}, (97, 1)))
        with self.assertRaisesRegex(variants.PackError, "reversed legacy formats range"):
            variants._legacy_formats_range([97, 1])

    def test_rejects_duplicate_zip_paths_before_transforming(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.input_zip, "w") as archive:
                archive.writestr("pack.mcmeta", b'{"pack":{"pack_format":75}}')
                archive.writestr("pack.mcmeta", b'{"pack":{"pack_format":76}}')
        self.write_catalog()

        with self.assertRaisesRegex(variants.PackError, "duplicate ZIP path"):
            self.build()


if __name__ == "__main__":
    unittest.main()
