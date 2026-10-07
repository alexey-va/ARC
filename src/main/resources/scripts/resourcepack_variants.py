#!/usr/bin/env python3
"""Build deterministic, format-specific variants of an ItemsAdder resource pack."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SUPPORTED_TARGETS = ("1.21.11", "26.1", "26.2", "26.3")
MODERN_FORMAT_MAJOR = 75
MAX_ARCHIVE_ENTRIES = 100_000
MAX_ENTRY_BYTES = 128 * 1024 * 1024
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_REPEATED_BLOCK_WIDTH = 64


class PackError(ValueError):
    """The input cannot be transformed without risking pack contents."""


class UnsupportedAtlas(PackError):
    """An atlas source cannot be evaluated without changing its semantics."""


@dataclass(frozen=True)
class Client:
    version: str
    protocol: int | None
    format_value: Any
    format_key: tuple[int, int]
    textures: frozenset[str]


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PackError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load_json(data: bytes, label: str) -> Any:
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PackError(f"invalid UTF-8 JSON in {label}: {exc}") from exc


def _format_key(value: Any) -> tuple[int, int]:
    if isinstance(value, bool):
        raise PackError("boolean is not a pack format")
    if isinstance(value, int):
        key = (value, 0)
    elif isinstance(value, (list, tuple)) and 1 <= len(value) <= 2:
        numbers = list(value) + [0] * (2 - len(value))
        if any(isinstance(number, bool) or not isinstance(number, int) for number in numbers):
            raise PackError(f"invalid pack format tuple: {value!r}")
        key = (numbers[0], numbers[1])
    elif isinstance(value, dict):
        if "major" in value:
            major = value["major"]
            minor = value.get("minor", 0)
        elif "pack_format" in value:
            major = value["pack_format"]
            minor = value.get("minor", 0)
        else:
            raise PackError(f"invalid pack format object: {value!r}")
        if any(isinstance(number, bool) or not isinstance(number, int) for number in (major, minor)):
            raise PackError(f"invalid pack format object: {value!r}")
        key = (major, minor)
    else:
        raise PackError(f"invalid pack format: {value!r}")
    if key[0] < 0 or key[1] < 0:
        raise PackError(f"negative pack format: {value!r}")
    return key


def _legacy_formats_range(value: Any) -> tuple[tuple[int, int], tuple[int, int]]:
    if isinstance(value, dict):
        for low_name, high_name in (
            ("min_format", "max_format"),
            ("min", "max"),
            ("min_inclusive", "max_inclusive"),
        ):
            if low_name in value and high_name in value:
                return _format_key(value[low_name]), _format_key(value[high_name])
        exact = _format_key(value)
        return exact, exact
    if isinstance(value, int) and not isinstance(value, bool):
        exact = _format_key(value)
        return exact, exact
    if isinstance(value, (list, tuple)):
        if len(value) == 1:
            exact = _format_key(value[0])
            return exact, exact
        if len(value) != 2:
            raise PackError(f"invalid legacy formats value: {value!r}")
        first, second = value
        # In the legacy `formats` field, a two-element scalar list is a range.
        # Modern major/minor tuples belong in `min_format` / `max_format`.
        scalar_pair = all(isinstance(item, int) and not isinstance(item, bool) for item in (first, second))
        if scalar_pair:
            if first > second:
                raise PackError(f"reversed legacy formats range: {value!r}")
            return (first, 0), (second, 0)
        return _format_key(first), _format_key(second)
    raise PackError(f"invalid legacy formats value: {value!r}")


def _validate_resource_id(value: Any, *, catalog: bool = False) -> str:
    if not isinstance(value, str) or not value or value.strip() != value:
        raise PackError(f"invalid texture resource id: {value!r}")
    if catalog and (value.startswith("textures/") or value.endswith(".png")):
        raise PackError(f"catalog texture must omit textures/ and .png: {value!r}")
    if ":" in value:
        namespace, path = value.split(":", 1)
    else:
        namespace, path = "minecraft", value
    if not re.fullmatch(r"[a-z0-9_.-]+", namespace) or not re.fullmatch(r"[a-z0-9_./-]+", path):
        raise PackError(f"invalid texture resource id: {value!r}")
    if not path or "//" in path or any(part in (".", "..") for part in path.split("/")):
        raise PackError(f"invalid texture resource id: {value!r}")
    return f"{namespace}:{path}"


def _load_catalog(path: Path) -> tuple[list[Client], set[str]]:
    value = _load_json(path.read_bytes(), str(path))
    if not isinstance(value, dict) or not isinstance(value.get("clients"), list):
        raise PackError("catalog must contain a clients array")
    clients_by_version: dict[str, Client] = {}
    all_textures: set[str] = set()
    for row in value["clients"]:
        if not isinstance(row, dict):
            raise PackError("catalog clients must be objects")
        version = row.get("version")
        if version not in SUPPORTED_TARGETS:
            continue
        if version in clients_by_version:
            raise PackError(f"duplicate catalog client version: {version}")
        protocol = row.get("protocol")
        if protocol is not None and (isinstance(protocol, bool) or not isinstance(protocol, int)):
            raise PackError(f"invalid protocol for {version}: {protocol!r}")
        if "format" not in row:
            raise PackError(f"missing format for {version}")
        texture_values = row.get("textures", [])
        if not isinstance(texture_values, list):
            raise PackError(f"textures must be an array for {version}")
        textures = frozenset(_validate_resource_id(item, catalog=True) for item in texture_values)
        format_value = row["format"]
        client = Client(version, protocol, format_value, _format_key(format_value), textures)
        clients_by_version[version] = client
        all_textures.update(textures)
    return [clients_by_version[v] for v in SUPPORTED_TARGETS if v in clients_by_version], all_textures


def _safe_zip_path(name: str) -> bool:
    if not name or "\\" in name or "\x00" in name or name.startswith("/"):
        return False
    parts = name.split("/")
    return not any(part in ("", ".", "..") for part in parts) and ":" not in parts[0]


def _read_archive(path: Path) -> dict[str, bytes]:
    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        raise PackError(f"input ZIP exceeds {MAX_ARCHIVE_BYTES} bytes")
    try:
        archive = zipfile.ZipFile(path, "r")
    except (OSError, zipfile.BadZipFile) as exc:
        raise PackError(f"cannot open input ZIP: {exc}") from exc
    with archive as source:
        infos = source.infolist()
        if len(infos) > MAX_ARCHIVE_ENTRIES:
            raise PackError(f"archive has too many entries: {len(infos)}")
        seen: set[str] = set()
        file_names: set[str] = set()
        total = 0
        for info in infos:
            name = info.filename
            if getattr(info, "orig_filename", name) != name:
                raise PackError(f"ZIP path contains a null byte: {name!r}")
            normalized = name[:-1] if name.endswith("/") else name
            if not _safe_zip_path(normalized):
                raise PackError(f"unsafe ZIP path: {name!r}")
            if normalized in seen:
                raise PackError(f"duplicate ZIP path: {name}")
            seen.add(normalized)
            if info.flag_bits & 0x1:
                raise PackError(f"encrypted ZIP entry is not supported: {name}")
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise PackError(f"symlink ZIP entry is not supported: {name}")
            if info.file_size > MAX_ENTRY_BYTES:
                raise PackError(f"ZIP entry is too large: {name} ({info.file_size} bytes)")
            total += info.file_size
            if total > MAX_ARCHIVE_BYTES:
                raise PackError(f"archive expands beyond {MAX_ARCHIVE_BYTES} bytes")
            if not info.is_dir():
                file_names.add(name)
        for name in file_names:
            parts = name.split("/")
            if any("/".join(parts[:index]) in file_names for index in range(1, len(parts))):
                raise PackError(f"ZIP file path is also used as a parent directory: {name}")
        try:
            bad_member = source.testzip()
        except (OSError, RuntimeError, NotImplementedError, zipfile.BadZipFile, zlib.error) as exc:
            raise PackError(f"ZIP CRC validation failed: {exc}") from exc
        if bad_member is not None:
            raise PackError(f"ZIP CRC validation failed at {bad_member}")
        result: dict[str, bytes] = {}
        for info in infos:
            if not info.is_dir():
                result[info.filename] = source.read(info)
        return result


def _overlay_directory(entry: Any) -> str:
    if not isinstance(entry, dict) or not isinstance(entry.get("directory"), str):
        raise PackError("overlay entry must contain a directory string")
    directory = entry["directory"]
    if directory.endswith("/"):
        directory = directory[:-1]
    if not _safe_zip_path(directory):
        raise PackError(f"unsafe overlay directory: {entry['directory']!r}")
    return directory


def _overlay_entries(metadata: dict[str, Any]) -> list[dict[str, Any]]:
    overlays = metadata.get("overlays", {})
    if overlays is None:
        return []
    if not isinstance(overlays, dict):
        raise PackError("pack.mcmeta overlays must be an object")
    entries = overlays.get("entries", [])
    if not isinstance(entries, list):
        raise PackError("pack.mcmeta overlays.entries must be an array")
    result: list[dict[str, Any]] = []
    roots: list[str] = []
    for entry in entries:
        root = _overlay_directory(entry)
        if root in roots:
            raise PackError(f"duplicate overlay directory: {root}")
        roots.append(root)
        result.append(entry)
    for left in roots:
        if any(right.startswith(left + "/") or left.startswith(right + "/") for right in roots if right != left):
            raise PackError("nested overlay directories are ambiguous")
    return result


def _overlay_is_active(entry: dict[str, Any], target: tuple[int, int]) -> bool:
    if "min_format" in entry or "max_format" in entry:
        lower = _format_key(entry["min_format"]) if entry.get("min_format") is not None else None
        upper = _format_key(entry["max_format"]) if entry.get("max_format") is not None else None
    elif "formats" in entry:
        lower, upper = _legacy_formats_range(entry["formats"])
    else:
        raise PackError(f"overlay has no supported format range: {entry!r}")
    if lower is not None and upper is not None and lower > upper:
        raise PackError(f"overlay format range is inverted: {entry!r}")
    return (lower is None or lower <= target) and (upper is None or target <= upper)


def _active_layers(
    files: dict[str, bytes], metadata: dict[str, Any], target: tuple[int, int]
) -> tuple[dict[str, bytes], list[str], int]:
    overlays = _overlay_entries(metadata)
    roots = [_overlay_directory(entry) for entry in overlays]
    active = [(entry, root) for entry, root in zip(overlays, roots) if _overlay_is_active(entry, target)]
    effective = {
        name: data
        for name, data in files.items()
        if not any(name.startswith(root + "/") or name == root for root in roots)
    }
    for _, root in active:
        prefix = root + "/"
        for name, data in files.items():
            if name.startswith(prefix):
                relative = name[len(prefix):]
                if not relative.startswith("assets/"):
                    continue
                effective[relative] = data
    return effective, [root for _, root in active], len(active) + 1


def _texture_ids(files: dict[str, bytes]) -> set[str]:
    result: set[str] = set()
    for name in files:
        if not name.startswith("assets/") or not name.endswith(".png"):
            continue
        parts = name.split("/", 3)
        if len(parts) == 4 and parts[2] == "textures" and parts[3]:
            result.add(_validate_resource_id(parts[1] + ":" + parts[3][:-4]))
    return result


def _source_kind(source: dict[str, Any]) -> str | None:
    value = source.get("type")
    if value in ("directory", "minecraft:directory"):
        return "directory"
    if value in ("single", "minecraft:single"):
        return "single"
    if value in ("filter", "minecraft:filter"):
        return "filter"
    return None


def _source_is_pure(source: Any) -> bool:
    if not isinstance(source, dict):
        return False
    kind = _source_kind(source)
    if kind == "directory":
        return set(source) <= {"type", "source", "prefix"} and isinstance(source.get("source"), str) and isinstance(source.get("prefix"), str)
    if kind == "single":
        return set(source) <= {"type", "resource", "sprite"} and isinstance(source.get("resource"), str)
    if kind == "filter":
        pattern = source.get("pattern")
        return (
            set(source) <= {"type", "pattern"}
            and isinstance(pattern, dict)
            and set(pattern) <= {"namespace", "path"}
            and all(isinstance(value, str) for value in pattern.values())
        )
    return False


def _directory_singles(source: dict[str, Any], textures: set[str]) -> list[dict[str, str]]:
    if not _source_is_pure(source) or _source_kind(source) != "directory":
        raise UnsupportedAtlas("unsupported directory source fields")
    directory = source["source"].strip("/")
    prefix = source["prefix"]
    if "\\" in directory or any(part in (".", "..") for part in directory.split("/")):
        raise PackError(f"unsafe atlas directory source: {source!r}")
    singles: list[tuple[str, str, dict[str, str]]] = []
    for texture in textures:
        namespace, path = texture.split(":", 1)
        if directory:
            base = directory + "/"
            if not path.startswith(base):
                continue
            relative = path[len(base):]
        else:
            relative = path
        if not relative:
            continue
        sprite = f"{namespace}:{prefix}{relative}"
        item = {"type": "minecraft:single", "resource": texture}
        if sprite != texture:
            item["sprite"] = sprite
        singles.append((sprite, texture, item))
    singles.sort(key=lambda row: (row[0], row[1]))
    return [item for _, _, item in singles]


def _filter_regex(pattern: Any, key: str) -> re.Pattern[str] | None:
    if key not in pattern:
        return None
    try:
        return re.compile(pattern[key])
    except re.error as exc:
        raise PackError(f"invalid atlas {key} filter: {exc}") from exc


def _evaluate_sources(sources: list[Any], textures: set[str]) -> dict[str, str]:
    sprites: dict[str, str] = {}
    for source in sources:
        if not isinstance(source, dict):
            raise UnsupportedAtlas("atlas source is not an object")
        kind = _source_kind(source)
        if kind == "directory":
            for single in _directory_singles(source, textures):
                sprite = _validate_resource_id(single.get("sprite", single["resource"]))
                sprites[sprite] = single["resource"]
        elif kind == "single":
            resource = _validate_resource_id(source.get("resource"))
            sprite = _validate_resource_id(source.get("sprite", resource))
            if resource in textures:
                sprites[sprite] = resource
        elif kind == "filter":
            if not _source_is_pure(source):
                raise UnsupportedAtlas("unsupported atlas filter fields")
            pattern = source["pattern"]
            namespace_filter = _filter_regex(pattern, "namespace")
            path_filter = _filter_regex(pattern, "path")
            for sprite in list(sprites):
                namespace, path = sprite.split(":", 1)
                if namespace_filter is not None and namespace_filter.fullmatch(namespace) is None:
                    continue
                if path_filter is not None and path_filter.fullmatch(path) is None:
                    continue
                del sprites[sprite]
        else:
            raise UnsupportedAtlas(f"unknown atlas source type: {source.get('type')!r}")
    return sprites


def _repeated_block_width(sources: list[Any], start: int) -> tuple[int, int] | None:
    available = (len(sources) - start) // 2
    # ponytail: block search is capped at 64 sources; increase only if a real atlas repeats larger blocks.
    for width in range(min(available, MAX_REPEATED_BLOCK_WIDTH), 0, -1):
        block = sources[start:start + width]
        if all(_source_is_pure(source) for source in block) and block == sources[start + width:start + 2 * width]:
            repeats = 2
            while start + (repeats + 1) * width <= len(sources) and block == sources[start + repeats * width:start + (repeats + 1) * width]:
                repeats += 1
            return width, repeats
    return None


def _deduplicate_repeated_blocks(sources: list[Any]) -> tuple[list[Any], int]:
    result: list[Any] = []
    removed = 0
    index = 0
    while index < len(sources):
        match = _repeated_block_width(sources, index)
        if match is None:
            result.append(sources[index])
            index += 1
            continue
        width, repeats = match
        result.extend(sources[index:index + width])
        removed += width * (repeats - 1)
        index += width * repeats
    return result, removed


def _atlas_path(name: str, overlay_roots: list[str]) -> bool:
    relative = name
    for root in sorted(overlay_roots, key=len, reverse=True):
        prefix = root + "/"
        if name.startswith(prefix):
            relative = name[len(prefix):]
            break
    return relative.startswith("assets/") and "/atlases/" in relative and relative.endswith(".json")


def _rewrite_atlas(
    data: bytes,
    textures: set[str],
    *,
    expand_directories: bool,
    strict: bool,
) -> tuple[bytes, dict[str, int]]:
    stats = {"directoriesExpanded": 0, "emptyDirectories": 0, "duplicateSourcesRemoved": 0, "atlasesSkipped": 0}
    try:
        document = _load_json(data, "atlas")
    except PackError:
        if strict:
            raise
        stats["atlasesSkipped"] = 1
        return data, stats
    if not isinstance(document, dict) or not isinstance(document.get("sources"), list):
        if strict:
            raise PackError("atlas JSON must contain a sources array")
        stats["atlasesSkipped"] = 1
        return data, stats
    original = document["sources"]
    if any(not _source_is_pure(source) for source in original):
        stats["atlasesSkipped"] = 1
        return data, stats
    has_directories = any(_source_kind(source) == "directory" for source in original)
    if not has_directories and not original:
        return data, stats
    if not expand_directories and not any(_repeated_block_width(original, index) for index in range(len(original))):
        return data, stats
    try:
        before = _evaluate_sources(original, textures)
        sources, removed = _deduplicate_repeated_blocks(original)
        stats["duplicateSourcesRemoved"] = removed
        after_dedup = _evaluate_sources(sources, textures)
        if before != after_dedup:
            if strict:
                raise PackError("atlas source deduplication changed the sprite map")
            stats["duplicateSourcesRemoved"] = 0
            return data, stats
        if expand_directories:
            expanded: list[Any] = []
            for source in sources:
                if _source_kind(source) != "directory":
                    expanded.append(source)
                    continue
                singles = _directory_singles(source, textures)
                if singles:
                    stats["directoriesExpanded"] += 1
                    expanded.extend(singles)
                else:
                    stats["emptyDirectories"] += 1
            if _evaluate_sources(expanded, textures) != before:
                raise PackError("atlas directory expansion changed the sprite map")
            sources = expanded
    except UnsupportedAtlas:
        stats["atlasesSkipped"] = 1
        return data, stats
    except PackError:
        if strict:
            raise
        stats["duplicateSourcesRemoved"] = 0
        stats["atlasesSkipped"] = 1
        return data, stats
    if sources == original:
        return data, stats
    document["sources"] = sources
    encoded = (json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    return encoded, stats


def _universal_files(files: dict[str, bytes], metadata: dict[str, Any], textures: set[str]) -> tuple[dict[str, bytes], dict[str, int]]:
    result = dict(files)
    roots = [_overlay_directory(entry) for entry in _overlay_entries(metadata)]
    totals = {"directoriesExpanded": 0, "emptyDirectories": 0, "duplicateSourcesRemoved": 0, "atlasesOptimized": 0, "atlasesSkipped": 0}
    for name, data in files.items():
        if not _atlas_path(name, roots):
            continue
        rewritten, stats = _rewrite_atlas(data, textures, expand_directories=False, strict=False)
        result[name] = rewritten
        totals["duplicateSourcesRemoved"] += stats["duplicateSourcesRemoved"]
        totals["atlasesSkipped"] += stats["atlasesSkipped"]
        totals["atlasesOptimized"] += int(rewritten != data)
    return result, totals


def _variant_files(
    files: dict[str, bytes],
    metadata: dict[str, Any],
    client: Client,
) -> tuple[dict[str, bytes], dict[str, Any]]:
    source_pack = metadata.get("pack")
    if not isinstance(source_pack, dict):
        raise PackError("pack.mcmeta must contain a pack object")
    _validate_declared_pack_range(source_pack, client.format_key)
    effective, active_overlays, layers_before = _active_layers(files, metadata, client.format_key)
    files_before = len(effective)
    textures = set(client.textures)
    textures.update(_texture_ids(effective))
    totals = {"directoriesExpanded": 0, "emptyDirectories": 0, "duplicateSourcesRemoved": 0, "atlasesOptimized": 0, "atlasesSkipped": 0}
    for name, data in list(effective.items()):
        if not _atlas_path(name, []):
            continue
        rewritten, stats = _rewrite_atlas(data, textures, expand_directories=True, strict=True)
        effective[name] = rewritten
        for key in ("directoriesExpanded", "emptyDirectories", "duplicateSourcesRemoved", "atlasesSkipped"):
            totals[key] += stats[key]
        totals["atlasesOptimized"] += int(rewritten != data)
    metadata_copy = json.loads(json.dumps(metadata))
    metadata_copy.pop("overlays", None)
    pack = metadata_copy.get("pack")
    if not isinstance(pack, dict):
        raise PackError("pack.mcmeta must contain a pack object")
    pack["pack_format"] = client.format_key[0]
    if client.format_key[0] >= MODERN_FORMAT_MAJOR:
        pack["min_format"] = client.format_value
        pack["max_format"] = client.format_value
        pack.pop("supported_formats", None)
        metadata_copy.pop("supported_formats", None)
    effective["pack.mcmeta"] = (json.dumps(metadata_copy, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    stats: dict[str, Any] = {
        "activeOverlays": active_overlays,
        "layersBefore": layers_before,
        "layersAfter": 1,
        "filesBefore": files_before,
        "filesAfter": len(effective),
        **totals,
    }
    return effective, stats


def _validate_declared_pack_range(pack: dict[str, Any], target: tuple[int, int]) -> None:
    if "min_format" in pack or "max_format" in pack:
        lower = _format_key(pack["min_format"]) if pack.get("min_format") is not None else None
        upper = _format_key(pack["max_format"]) if pack.get("max_format") is not None else None
    elif "supported_formats" in pack:
        lower, upper = _legacy_formats_range(pack["supported_formats"])
    elif "pack_format" in pack:
        lower = upper = _format_key(pack["pack_format"])
    else:
        raise PackError("pack.mcmeta does not declare a supported format range")
    if lower is not None and upper is not None and lower > upper:
        raise PackError("pack.mcmeta declares an inverted format range")
    if (lower is not None and target < lower) or (upper is not None and target > upper):
        raise PackError(f"input pack does not declare support for format {target}")


def _write_reproducible_zip(path: Path, files: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=True) as archive:
        archive.comment = b""
        for name in sorted(files):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.flag_bits = 0
            archive.writestr(info, files[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def _hash_file(path: Path) -> tuple[str, str, int]:
    sha1 = hashlib.sha1()
    sha256 = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            size += len(chunk)
            sha1.update(chunk)
            sha256.update(chunk)
    return sha1.hexdigest(), sha256.hexdigest(), size


def _valid_upload_name(name: str) -> bool:
    return name == Path(name).name and name not in ("", ".", "..") and name.lower().endswith(".zip") and "\\" not in name


def build_variants(input_path: Path, output_dir: Path, catalog_path: Path, upload_name: str) -> dict[str, Any]:
    if not _valid_upload_name(upload_name):
        raise PackError("upload name must be a simple .zip filename")
    input_path = input_path.resolve()
    output_dir = output_dir.resolve()
    if not input_path.is_file():
        raise PackError(f"input ZIP is missing: {input_path}")
    if input_path == output_dir / upload_name:
        raise PackError("output would overwrite the input ZIP")
    files = _read_archive(input_path)
    if "pack.mcmeta" not in files:
        raise PackError("input ZIP is missing root pack.mcmeta")
    metadata = _load_json(files["pack.mcmeta"], "pack.mcmeta")
    if not isinstance(metadata, dict) or not isinstance(metadata.get("pack"), dict):
        raise PackError("pack.mcmeta must contain a pack object")
    overlay_entries = _overlay_entries(metadata)
    catalog_clients, catalog_textures = _load_catalog(catalog_path)
    universal_textures = set(catalog_textures)
    universal_textures.update(_texture_ids(files))
    universal_files, universal_stats = _universal_files(files, metadata, universal_textures)

    output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".resourcepack-variants-", dir=output_dir) as temporary:
        stage = Path(temporary)
        pack_specs: list[tuple[str, str | None, int | None, dict[str, bytes], dict[str, Any]]] = [
            (upload_name, None, None, universal_files, {
                "overlayDirectories": [
                    _overlay_directory(entry) for entry in overlay_entries
                ],
                "layersBefore": len(overlay_entries) + 1,
                "layersAfter": len(overlay_entries) + 1,
                "filesBefore": len(files),
                "filesAfter": len(universal_files),
                **universal_stats,
            })
        ]
        for client in catalog_clients:
            variant_name = f"{Path(upload_name).stem}-{client.version}.zip"
            variant_files, stats = _variant_files(files, metadata, client)
            pack_specs.append((variant_name, client.version, client.protocol, variant_files, stats))
        packs: list[dict[str, Any]] = []
        for name, version, protocol, pack_files, stats in pack_specs:
            staged_path = stage / name
            _write_reproducible_zip(staged_path, pack_files)
            sha1, sha256, size = _hash_file(staged_path)
            packs.append({
                "file": name,
                "version": version,
                "protocol": protocol,
                "sha1": sha1,
                "sha256": sha256,
                "bytes": size,
                "stats": stats,
            })
        manifest = {"schema": 1, "uploadName": upload_name, "packs": packs}
        manifest_path = stage / "variants.json"
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for pack in packs:
            os.replace(stage / pack["file"], output_dir / pack["file"])
        os.replace(manifest_path, output_dir / "variants.json")
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="normalized universal input ZIP")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--upload-name", default="RusCraftingResource.zip")
    args = parser.parse_args(argv)
    try:
        manifest = build_variants(args.input, args.output_dir, args.catalog, args.upload_name)
    except (OSError, PackError, zipfile.BadZipFile) as exc:
        print(f"resourcepack_variants: error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
