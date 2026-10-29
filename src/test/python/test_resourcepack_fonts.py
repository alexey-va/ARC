import io
import json
import struct
import sys
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

from PIL import Image


SCRIPT_DIR = Path(__file__).resolve().parents[2] / "main" / "resources" / "scripts"
sys.path.insert(0, str(SCRIPT_DIR))

import resourcepack_fonts as fonts  # noqa: E402


FONT = fonts.FONT_PATHS[0]


def png(image, **options):
    output = io.BytesIO()
    image.save(output, format="PNG", **options)
    return output.getvalue()


def bitmap(character, file="demo:font/cell.png", **metadata):
    return {"type": "bitmap", "file": file, "height": 8, "ascent": 7, "chars": [character], **metadata}


def pack(providers, textures=None, **root):
    files = {FONT: json.dumps({"providers": providers, **root}).encode()}
    files.update(textures or {"assets/demo/textures/font/cell.png": png(Image.new("RGBA", (3, 2), (50, 60, 70, 255)))})
    return files


def document(files, path=FONT):
    return json.loads(files[path])


def protected_png(source):
    output = bytearray(source)
    offset = 8
    while offset + 12 <= len(output):
        length = struct.unpack(">I", output[offset:offset + 4])[0]
        if output[offset + 4:offset + 8] == b"IDAT":
            output[offset + 8 + length - 1] ^= 1
        output[offset + 8 + length:offset + 12 + length] = b"\x00" * 4
        offset += length + 12
    return bytes(output)


class ResourcePackFontsTest(unittest.TestCase):
    def test_copies_full_nonsquare_rgba_palette_rgb_and_la_cells_without_losing_hidden_rgb(self):
        rgba = Image.new("RGBA", (3, 2))
        rgba.putdata([(99, 88, 77, 0), (1, 2, 3, 127), (4, 5, 6, 255), (0, 0, 0, 0), (20, 30, 40, 255), (80, 70, 60, 0)])
        palette = Image.new("P", (3, 2))
        palette.putpalette([21, 22, 23, 31, 32, 33] + [0] * 762)
        palette.putdata([0, 1, 0, 1, 0, 1])
        palette.info["transparency"] = bytes([0, 128])
        sources = {
            "rgba": png(rgba), "palette1": png(palette, bits=1), "palette8": png(palette, bits=8),
            "rgb": png(Image.new("RGB", (3, 2), (12, 34, 56))),
            "la": png(Image.new("LA", (3, 2), (98, 0))),
        }
        textures = {f"assets/demo/textures/font/{name}.png": data for name, data in sources.items()}
        providers = [bitmap(chr(0xE000 + index), f"demo:font/{name}.png", height=12, ascent=-4) for index, name in enumerate(sources)]
        files = pack(providers, textures, future_root={"kept": True})
        before = dict(files)

        result, stats = fonts.optimize_fonts(files)

        self.assertEqual(files, before)
        for name, data in before.items():
            if name != FONT:
                self.assertEqual(result[name], data)
        provider = document(result)["providers"][0]
        self.assertEqual({key: value for key, value in provider.items() if key not in ("file", "chars")}, {"type": "bitmap", "height": 12, "ascent": -4})
        self.assertEqual(document(result)["future_root"], {"kept": True})
        self.assertEqual(provider["chars"], ["".join(item["chars"][0] for item in providers)])
        with Image.open(io.BytesIO(result[fonts._texture_path(provider["file"])])) as sheet:
            self.assertEqual(sheet.size, (15, 2))
            for index, data in enumerate(sources.values()):
                with Image.open(io.BytesIO(data)) as original:
                    self.assertEqual(sheet.crop((index * 3, 0, index * 3 + 3, 2)).tobytes(), original.convert("RGBA").tobytes())
            self.assertEqual(sheet.getpixel((0, 0)), (99, 88, 77, 0))
        self.assertEqual((stats["fontProvidersBefore"], stats["fontProvidersAfter"], stats["fontGroupsMerged"], stats["fontSheetsAdded"]), (5, 1, 1, 1))

    def test_compatible_metadata_includes_every_key_and_filter_value(self):
        providers = [
            bitmap("a", filter={"uniform": False}), bitmap("b", filter={"uniform": True}),
            bitmap("c", filter={"uniform": False}), bitmap("d", ascent=6),
            bitmap("e", height=9), bitmap("f", type="minecraft:bitmap"),
        ]
        files = pack(providers)

        result, stats = fonts.optimize_fonts(files)

        actual = document(result)["providers"]
        self.assertEqual([item["chars"] for item in actual], [["ac"], ["b"], ["d"], ["e"], ["f"]])
        self.assertEqual(actual[0]["filter"], {"uniform": False})
        self.assertEqual(actual[1:], providers[1:2] + providers[3:])
        self.assertEqual(stats["fontProvidersAfter"], 5)

    def test_duplicate_codepoints_reference_space_unknown_and_external_are_barriers(self):
        reference = {"type": "reference", "id": "minecraft:include/default"}
        space = {"type": "space", "advances": {" ": 4}}
        unknown = bitmap("i", future_field=True)
        external = bitmap("k", "minecraft:item/compass_00.png")
        providers = [
            bitmap("a"), bitmap("b"), bitmap("a"), bitmap("c"), bitmap("d"),
            reference, bitmap("e"), space, bitmap("f"), unknown, bitmap("g"), external, bitmap("h"),
        ]

        result, stats = fonts.optimize_fonts(pack(providers))

        actual = document(result)["providers"]
        self.assertEqual(actual[0]["chars"], ["ab"])
        self.assertEqual(actual[1], providers[2])
        self.assertEqual(actual[2]["chars"], ["cd"])
        self.assertEqual(actual[3:], providers[5:])
        self.assertEqual(stats["fontSkipReasons"]["duplicateCharacter"], 1)
        self.assertEqual(stats["fontGroupsMerged"], 2)

    def test_unknown_multiglyph_unproven_encoding_and_failed_decode_remain_exact(self):
        valid = png(Image.new("RGBA", (3, 2), (1, 2, 3, 0)))
        textures = {
            "assets/demo/textures/font/cell.png": valid,
            "assets/demo/textures/font/gray16.png": png(Image.new("I;16", (3, 2), 32768)),
            "assets/demo/textures/font/broken.png": valid[:-10],
        }
        providers = [
            bitmap("a"), bitmap("b", chars=["bc"]), bitmap("d"),
            bitmap("e", "demo:font/gray16.png"), bitmap("f"),
            bitmap("g", "demo:font/broken.png"), bitmap("h"),
            bitmap("i", "../unsafe.png"), bitmap("j"),
            bitmap("\x00"), bitmap("k"), bitmap("l", height=True), bitmap("m"),
        ]
        files = pack(providers, textures)

        result, stats = fonts.optimize_fonts(files)

        self.assertEqual(result, files)
        self.assertEqual(stats["fontSkipReasons"]["pngEncoding"], 1)
        self.assertEqual(stats["fontSkipReasons"]["decodeFailed"], 1)
        self.assertEqual(stats["fontGroupsMerged"], 0)

    def test_repairs_protected_decoding_copy_and_keeps_original_asset_bytes(self):
        original = png(Image.new("RGBA", (3, 2), (71, 72, 73, 0)))
        protected = protected_png(original)
        files = pack([bitmap("a"), bitmap("b")], {"assets/demo/textures/font/cell.png": protected})

        result, stats = fonts.optimize_fonts(files)

        provider = document(result)["providers"][0]
        with Image.open(io.BytesIO(result[fonts._texture_path(provider["file"])])) as sheet:
            self.assertEqual(sheet.tobytes(), bytes([71, 72, 73, 0]) * 12)
        self.assertEqual(result["assets/demo/textures/font/cell.png"], protected)
        self.assertEqual(stats["fontProvidersAfter"], 1)

    def test_deterministic_idempotent_and_reuses_same_sheet_for_default_and_uniform(self):
        providers = [bitmap("\U000F0000"), bitmap("\U000F0001")]
        files = pack(providers)
        files[fonts.FONT_PATHS[1]] = files[FONT]
        files["assets/demo/font/untouched.json"] = files[FONT]

        first, stats = fonts.optimize_fonts(files)
        second, _ = fonts.optimize_fonts(files)
        repeated, _ = fonts.optimize_fonts(first)

        self.assertEqual(first, second)
        self.assertEqual(first, repeated)
        self.assertEqual(first[FONT], first[fonts.FONT_PATHS[1]])
        self.assertEqual(first["assets/demo/font/untouched.json"], files[FONT])
        self.assertEqual((stats["fontGroupsMerged"], stats["fontSheetsAdded"], stats["fontsOptimized"]), (2, 1, 2))

    def test_splits_sheets_to_dimension_limit_and_keeps_last_single_provider(self):
        files = pack([bitmap(character) for character in "abcde"], {
            "assets/demo/textures/font/cell.png": png(Image.new("RGBA", (3, 5), (0, 255, 0, 255))),
        })

        with patch.object(fonts, "MAX_SHEET_SIZE", 8):
            result, stats = fonts.optimize_fonts(files)

        providers = document(result)["providers"]
        self.assertEqual([item["chars"] for item in providers], [["ab"], ["cd"], ["e"]])
        self.assertEqual(stats["fontGroupsMerged"], 2)
        for path, data in result.items():
            if "/font/optimized/" in path:
                with Image.open(io.BytesIO(data)) as image:
                    self.assertLessEqual(max(image.size), 8)

    def test_validation_aborts_if_copy_changes_a_cell(self):
        paste = Image.Image.paste

        def corrupt_copy(image, cell, box, *args, **kwargs):
            paste(image, cell, box, *args, **kwargs)
            paste(image, (0, 0, 0, 0), (box[0], box[1], box[0] + 1, box[1] + 1))

        with patch.object(Image.Image, "paste", corrupt_copy):
            with self.assertRaisesRegex(fonts.FontOptimizationError, "source RGBA cell"):
                fonts.optimize_fonts(pack([bitmap("a"), bitmap("b")]))

    def test_invalid_font_document_is_preserved(self):
        for data in (b"not json", b'{"providers":[],"providers":[]}', b'{"providers":{}}'):
            files = {FONT: data}
            result, stats = fonts.optimize_fonts(files)
            self.assertEqual(result, files)
            self.assertEqual(stats["fontFilesSkipped"], 1)

    def test_png_inflation_is_bounded(self):
        files = pack([bitmap("a"), bitmap("b")])
        with patch.object(fonts, "MAX_PNG_DECODE_BYTES", 8):
            result, stats = fonts.optimize_fonts(files)
        self.assertEqual(result, files)
        self.assertEqual(stats["fontSkipReasons"]["decodeFailed"], 2)

    def test_rejects_pillow_versions_outside_the_tested_api_range(self):
        for version in ("9.0.0", "13.0.0", "unrecognized"):
            with self.subTest(version=version), patch.object(fonts, "PILLOW_VERSION", version):
                with self.assertRaisesRegex(fonts.FontOptimizationError, "Pillow >=9.0.1,<13"):
                    fonts.optimize_fonts({})
        for version in ("9.0.1", "12.3.0"):
            with self.subTest(version=version), patch.object(fonts, "PILLOW_VERSION", version):
                self.assertEqual(fonts.optimize_fonts({})[0], {})


if __name__ == "__main__":
    unittest.main()
