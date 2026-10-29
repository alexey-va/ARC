"""Losslessly group single-cell bitmap font providers (Pillow >=9.0.1,<13)."""

from __future__ import annotations

import hashlib
import io
import json
import re
import struct
import zlib
from collections import Counter
from dataclasses import dataclass
from typing import Any

from PIL import Image, __version__ as PILLOW_VERSION


FONT_PATHS = ("assets/minecraft/font/default.json", "assets/minecraft/font/uniform.json")
MAX_SHEET_SIZE = 2048
# Largest supported RGBA16 cell, including a bound for Adam7 filter bytes.
MAX_PNG_DECODE_BYTES = 2048 * 2048 * 8 + 2048 * 8
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# These encodings have the same RGBA conversion as the native bitmap loader.
SUPPORTED_PNG_FORMATS = {(8, 6), (1, 3), (8, 3), (8, 2), (8, 4), (16, 6)}
PROVIDER_FIELDS = {"type", "file", "height", "ascent", "chars", "filter"}


class FontOptimizationError(ValueError):
    """A generated font sheet failed its losslessness checks."""


@dataclass(frozen=True)
class _Glyph:
    provider: dict[str, Any]
    texture: str
    width: int
    height: int
    key: str
    character: str


def _inflate(data: bytes, *, wbits: int = zlib.MAX_WBITS) -> bytes:
    decoder = zlib.decompressobj(wbits)
    raw = decoder.decompress(data, MAX_PNG_DECODE_BYTES + 1)
    if len(raw) > MAX_PNG_DECODE_BYTES or not decoder.eof:
        raise ValueError("PNG image data is truncated or exceeds the decoding limit")
    return raw


def _repair_png_checksums(source: bytes) -> bytes:
    """Repair ItemsAdder protection in a decoding copy; keep original assets.

    This is the same CRC/Adler repair used by the ops font renderer. Pillow
    remains the PNG decoder; the repair never interprets pixels or scanlines.
    """
    if not source.startswith(PNG_SIGNATURE):
        raise ValueError("bitmap is not a PNG")
    repaired = bytearray(source)
    offset = 8
    chunks: list[tuple[int, int, int, int]] = []
    idat_spans: list[tuple[int, int]] = []
    while offset + 12 <= len(repaired):
        length = struct.unpack(">I", repaired[offset:offset + 4])[0]
        data_start = offset + 8
        chunk_end = data_start + length
        crc_end = chunk_end + 4
        if crc_end > len(repaired):
            raise ValueError("PNG has a truncated chunk")
        chunks.append((offset, data_start, chunk_end, crc_end))
        if repaired[offset + 4:offset + 8] == b"IDAT":
            idat_spans.append((data_start, chunk_end))
        offset = crc_end
        if repaired[data_start - 4:data_start] == b"IEND":
            del repaired[offset:]
            break
    if offset != len(repaired):
        raise ValueError("PNG has trailing or truncated data")
    compressed = bytearray(b"".join(repaired[start:end] for start, end in idat_spans))
    if compressed:
        try:
            _inflate(compressed)
        except zlib.error:
            raw = _inflate(compressed[2:-4], wbits=-15)
            compressed[-4:] = struct.pack(">I", zlib.adler32(raw) & 0xFFFFFFFF)
            cursor = 0
            for start, end in idat_spans:
                length = end - start
                repaired[start:end] = compressed[cursor:cursor + length]
                cursor += length
    for chunk_start, _, chunk_end, crc_end in chunks:
        repaired[chunk_end:crc_end] = struct.pack(
            ">I", zlib.crc32(repaired[chunk_start + 4:chunk_end]) & 0xFFFFFFFF
        )
    return bytes(repaired)


def _decode_bitmap(source: bytes) -> Image.Image:
    with Image.open(io.BytesIO(_repair_png_checksums(source))) as image:
        if image.format != "PNG" or getattr(image, "is_animated", False):
            raise ValueError("unsupported bitmap format")
        return image.convert("RGBA")


def _texture_path(resource: Any) -> str | None:
    if not isinstance(resource, str):
        return None
    namespace, path = resource.split(":", 1) if ":" in resource else ("minecraft", resource)
    if (
        not re.fullmatch(r"[a-z0-9_.-]+", namespace)
        or not re.fullmatch(r"[a-z0-9_./-]+\.png", path)
        or any(part in ("", ".", "..") for part in path.split("/"))
    ):
        return None
    return f"assets/{namespace}/textures/{path}"


def _metadata(provider: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in provider.items() if key not in ("file", "chars")}


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _glyph(
    provider: Any, files: dict[str, bytes], bitmaps: dict[str, tuple[int, int] | str]
) -> tuple[_Glyph | None, str]:
    if not isinstance(provider, dict) or provider.get("type") not in ("bitmap", "minecraft:bitmap"):
        return None, "providerType"
    if not set(provider) <= PROVIDER_FIELDS:
        return None, "unknownFields"
    chars = provider.get("chars")
    if (
        not isinstance(chars, list) or len(chars) != 1
        or not isinstance(chars[0], str) or len(chars[0]) != 1
        or chars[0] == "\x00" or 0xD800 <= ord(chars[0]) <= 0xDFFF
    ):
        return None, "characters"
    height, ascent = provider.get("height", 8), provider.get("ascent")
    if (
        isinstance(height, bool) or not isinstance(height, int) or height <= 0
        or isinstance(ascent, bool) or not isinstance(ascent, int) or ascent > height
        or ("filter" in provider and (
            not isinstance(provider["filter"], dict)
            or any(not isinstance(value, bool) for value in provider["filter"].values())
        ))
    ):
        return None, "metadata"
    path = _texture_path(provider.get("file"))
    if path is None or path not in files:
        return None, "externalTexture"
    if path not in bitmaps:
        data = files[path]
        if (
            len(data) < 33 or not data.startswith(PNG_SIGNATURE)
            or data[8:16] != b"\x00\x00\x00\rIHDR"
            or (data[24], data[25]) not in SUPPORTED_PNG_FORMATS
        ):
            bitmaps[path] = "pngEncoding"
        else:
            width, cell_height = struct.unpack(">II", data[16:24])
            if not (0 < width <= MAX_SHEET_SIZE and 0 < cell_height <= MAX_SHEET_SIZE):
                bitmaps[path] = "cellSize"
            else:
                try:
                    with _decode_bitmap(data) as image:
                        if image.size != (width, cell_height):
                            raise ValueError("PNG dimensions changed while decoding")
                    bitmaps[path] = (width, cell_height)
                except (OSError, ValueError, zlib.error, struct.error):
                    bitmaps[path] = "decodeFailed"
    dimensions = bitmaps[path]
    if isinstance(dimensions, str):
        return None, dimensions
    width, cell_height = dimensions
    key = json.dumps([_metadata(provider), width, cell_height], sort_keys=True, separators=(",", ":"))
    return _Glyph(provider, path, width, cell_height, key, chars[0]), ""


def _make_sheet(glyphs: list[_Glyph], files: dict[str, bytes]) -> tuple[dict[str, Any], str, bytes]:
    first = glyphs[0]
    columns = min(MAX_SHEET_SIZE // first.width, len(glyphs))
    rows = (len(glyphs) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * first.width, rows * first.height))
    characters = ["\x00"] * (columns * rows)
    try:
        source_cells: list[bytes] = []
        for index, glyph in enumerate(glyphs):
            x, y = index % columns * first.width, index // columns * first.height
            with _decode_bitmap(files[glyph.texture]) as cell:
                # No mask: transparent pixels must retain their original RGB too.
                sheet.paste(cell, (x, y))
                source_cells.append(cell.tobytes())
            characters[index] = glyph.character
        sheet_pixels = sheet.tobytes()
        stride = sheet.width * 4
        for index, source_pixels in enumerate(source_cells):
            x, y = index % columns * first.width, index // columns * first.height
            for row in range(first.height):
                start = (y + row) * stride + x * 4
                if sheet_pixels[start:start + first.width * 4] != source_pixels[row * first.width * 4:(row + 1) * first.width * 4]:
                    raise FontOptimizationError("font sheet changed a source RGBA cell")
        output = io.BytesIO()
        sheet.save(output, format="PNG", optimize=False, compress_level=9)
        data = output.getvalue()
        with Image.open(io.BytesIO(data)) as decoded:
            if decoded.mode != "RGBA" or decoded.size != sheet.size or decoded.tobytes() != sheet_pixels:
                raise FontOptimizationError("font PNG encoding changed RGBA pixels")
    finally:
        sheet.close()
    digest = hashlib.sha256(data).hexdigest()
    resource = f"arc:font/optimized/{digest}.png"
    path = f"assets/arc/textures/font/optimized/{digest}.png"
    provider = dict(first.provider)
    provider["file"] = resource
    provider["chars"] = ["".join(characters[row * columns:(row + 1) * columns]) for row in range(rows)]
    if (
        _metadata(provider) != _metadata(first.provider)
        or any(glyph.key != first.key for glyph in glyphs)
        or len({glyph.character for glyph in glyphs}) != len(glyphs)
        or any(provider["chars"][index // columns][index % columns] != glyph.character for index, glyph in enumerate(glyphs))
    ):
        raise FontOptimizationError("font sheet changed provider metadata or character placement")
    return provider, path, data


def optimize_fonts(files: dict[str, bytes]) -> tuple[dict[str, bytes], dict[str, Any]]:
    """Group the effective default/uniform fonts, preserving all original assets.

    Unknown providers, absent textures and unproven encodings are run barriers.
    A repeated codepoint also ends the run, preserving provider precedence.
    """
    version = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", PILLOW_VERSION)
    if version is None or not (9, 0, 1) <= tuple(map(int, version.groups())) < (13, 0, 0):
        raise FontOptimizationError(f"Pillow >=9.0.1,<13 is required; found {PILLOW_VERSION}")
    result = dict(files)
    stats: dict[str, Any] = {
        "fontProvidersBefore": 0, "fontProvidersAfter": 0, "fontGroupsMerged": 0,
        "fontSheetsAdded": 0, "fontProvidersSkipped": 0, "fontFilesSkipped": 0,
        "fontsOptimized": 0, "fontSkipReasons": {},
    }
    reasons: Counter[str] = Counter()
    bitmaps: dict[str, tuple[int, int] | str] = {}
    for font_path in FONT_PATHS:
        if font_path not in files:
            continue
        try:
            document = json.loads(files[font_path], object_pairs_hook=_unique_object)
        except (UnicodeDecodeError, ValueError):
            stats["fontFilesSkipped"] += 1
            continue
        if not isinstance(document, dict) or not isinstance(document.get("providers"), list):
            stats["fontFilesSkipped"] += 1
            continue
        original = document["providers"]
        stats["fontProvidersBefore"] += len(original)
        providers: list[Any] = []
        run: list[_Glyph] = []
        seen: set[str] = set()

        def flush() -> None:
            groups: dict[str, list[_Glyph]] = {}
            for glyph in run:
                groups.setdefault(glyph.key, []).append(glyph)
            for group in groups.values():
                capacity = (MAX_SHEET_SIZE // group[0].width) * (MAX_SHEET_SIZE // group[0].height)
                for start in range(0, len(group), capacity):
                    chunk = group[start:start + capacity]
                    if len(chunk) == 1:
                        providers.append(chunk[0].provider)
                        continue
                    provider, path, data = _make_sheet(chunk, files)
                    if path in result and result[path] != data:
                        raise FontOptimizationError(f"generated font texture conflicts with {path}")
                    stats["fontSheetsAdded"] += int(path not in result)
                    result[path] = data
                    providers.append(provider)
                    stats["fontGroupsMerged"] += 1
            run.clear()
            seen.clear()

        for provider in original:
            glyph, reason = _glyph(provider, files, bitmaps)
            if glyph is None:
                flush()
                providers.append(provider)
                stats["fontProvidersSkipped"] += 1
                reasons[reason] += 1
            elif glyph.character in seen:
                flush()
                providers.append(provider)
                stats["fontProvidersSkipped"] += 1
                reasons["duplicateCharacter"] += 1
            else:
                run.append(glyph)
                seen.add(glyph.character)
        flush()
        stats["fontProvidersAfter"] += len(providers)
        if providers != original:
            document["providers"] = providers
            result[font_path] = (json.dumps(document, separators=(",", ":")) + "\n").encode("utf-8")
            stats["fontsOptimized"] += 1
    stats["fontSkipReasons"] = dict(sorted(reasons.items()))
    return result, stats
