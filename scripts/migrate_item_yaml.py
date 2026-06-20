#!/usr/bin/env python3
"""Convert flat GUI item keys (*-display, *-lore, …) to nested item blocks."""

from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    from ruamel.yaml import YAML
except ImportError:
    from pip import main as pip_main

    pip_main(["install", "ruamel.yaml", "-q"])
    from ruamel.yaml import YAML

ITEM_SUFFIXES = (
    ("-display", "display"),
    ("-lore", "lore"),
    ("-model-data", "customModelData"),
    ("-material", "material"),
)

TITLE_LORE_SUFFIXES = (
    ("-title", "display"),
    ("-lore", "lore"),
)

MATERIAL_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")


def is_material(value: object) -> bool:
    return isinstance(value, str) and MATERIAL_RE.match(value) is not None


def merge_button_blocks(data: dict) -> None:
    keys = list(data.keys())
    for key in keys:
        if not key.endswith("-button"):
            continue
        material_value = data.get(key)
        display_key = f"{key}-display"
        lore_key = f"{key}-lore"
        model_key = f"{key}-model-data"
        if display_key not in data and lore_key not in data and model_key not in data:
            continue

        nested: dict = {}
        if isinstance(material_value, dict):
            nested.update(material_value)
        elif is_material(material_value):
            nested["material"] = material_value
        elif isinstance(material_value, str):
            nested["display"] = material_value

        if display_key in data:
            nested["display"] = data.pop(display_key)
        if lore_key in data:
            nested["lore"] = data.pop(lore_key)
        if model_key in data:
            nested["customModelData"] = data.pop(model_key)

        data[key] = nested


def migrate_level(data: dict) -> None:
    merge_button_blocks(data)

    keys = list(data.keys())
    grouped: dict[str, dict] = {}
    remove: list[str] = []

    for key in keys:
        matched = False
        for suffix, field in ITEM_SUFFIXES:
            if key.endswith(suffix):
                prefix = key[: -len(suffix)]
                grouped.setdefault(prefix, {})[field] = data[key]
                remove.append(key)
                matched = True
                break
        if matched:
            continue
        for suffix, field in TITLE_LORE_SUFFIXES:
            if key.endswith(suffix):
                prefix = key[: -len(suffix)]
                grouped.setdefault(prefix, {})[field] = data[key]
                remove.append(key)
                break

    for prefix, fields in grouped.items():
        nested: dict = {}
        existing = data.get(prefix)
        if isinstance(existing, dict):
            nested.update(existing)
            remove.append(prefix)
        elif isinstance(existing, str):
            if prefix.endswith("-button") or is_material(existing):
                nested["material"] = existing
            else:
                nested["display"] = existing
            remove.append(prefix)

        for field, value in fields.items():
            nested[field] = value
        data[prefix] = nested

    for key in set(remove):
        data.pop(key, None)

    for value in data.values():
        if isinstance(value, dict):
            migrate_level(value)
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    migrate_level(item)


def rename_default_display(data: dict) -> None:
    for key, value in list(data.items()):
        if isinstance(value, dict):
            if "default-display" in value and "display" not in value:
                value["display"] = value.pop("default-display")
            rename_default_display(value)
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    rename_default_display(item)


def migrate_file(path: Path) -> None:
    yaml = YAML()
    yaml.preserve_quotes = True
    yaml.width = 120
    with path.open("r", encoding="utf-8") as handle:
        data = yaml.load(handle)
    if not isinstance(data, dict):
        return
    migrate_level(data)
    rename_default_display(data)
    with path.open("w", encoding="utf-8") as handle:
        yaml.dump(data, handle)
    print(f"migrated {path}")


def main(argv: list[str]) -> int:
    for arg in argv:
        migrate_file(Path(arg))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
