#!/usr/bin/env python3
"""Check that ARC and ArcDungeons keep their runtime and API classes separate."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


API_PACKAGES = (
    "ru/arc/paper/api/",
    "ru/arc/dungeons/api/",
    "ru/arc/staffspells/api/",
)
API_CLASSES = (
    "ru/arc/paper/api/ArcRedisService.class",
    "ru/arc/dungeons/api/ArcDungeonsService.class",
    "ru/arc/staffspells/api/ArcStaffSpellService.class",
)
PROVIDER_CLASSES = (
    "ru/arc/paper/redis/PaperRedisServiceProvider",
    "ru/arc/paper/sql/PaperSqlServiceProvider",
    "ru/arc/paper/metrics/PaperArcMetricsProvider",
)
MOVED_PACKAGE = "ru/arc/hooks/elitemobs/"
MOVED_CLASS = "ru/arc/hooks/elitemobs/EMHook.class"
MOVED_IMPLEMENTATIONS = (
    ("ru/arc/eliteloot/", "ru/arc/eliteloot/EliteLootModule.class"),
    ("ru/arc/staffspells/", "ru/arc/staffspells/StaffSpellsModule.class"),
)
MOVED_CLASS_PREFIXES = (
    ("ru/arc/sync/EmSync", "ru/arc/sync/EmSync.class"),
    ("ru/arc/enchanting/EliteEnchanting", "ru/arc/enchanting/EliteEnchantingModule.class"),
)
MOVED_RESOURCES = (
    "modules/elitemobs.yml",
    "modules/elitemobs-wormholes.yml",
)
ARC_ENTRYPOINT = "ru/arc/ARC"
DUNGEONS_ENTRYPOINT = "ru/arc/dungeons/ArcDungeonsPlugin.class"
DUNGEONS_SERVICE_PROVIDER = "ru/arc/dungeons/DungeonsServiceProvider"
DUNGEON_HOST_SERVICES = "ru/arc/dungeons/DungeonHostServices"


def class_exists(entries: set[str], class_name: str) -> bool:
    return f"{class_name}.class" in entries or any(
        entry.startswith(f"{class_name}$") and entry.endswith(".class") for entry in entries
    )


def load_archive(path: Path) -> tuple[set[str], str | None]:
    if not path.is_file():
        raise ValueError(f"artifact does not exist: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            entries = {entry for entry in archive.namelist() if not entry.endswith("/")}
            descriptor = archive.read("plugin.yml").decode("utf-8") if "plugin.yml" in entries else None
            return entries, descriptor
    except (OSError, zipfile.BadZipFile) as failure:
        raise ValueError(f"cannot read JAR {path}: {failure}") from failure


def verify(arc: set[str], dungeons: set[str], dungeons_descriptor: str | None) -> list[str]:
    violations: list[str] = []

    if not class_exists(arc, ARC_ENTRYPOINT):
        violations.append("ARC JAR is missing ru/arc/ARC.class")
    if DUNGEONS_ENTRYPOINT not in dungeons:
        violations.append("ArcDungeons JAR is missing ru/arc/dungeons/ArcDungeonsPlugin.class")
    if class_exists(dungeons, ARC_ENTRYPOINT):
        violations.append("ArcDungeons JAR embeds the ARC plugin entrypoint")
    if not class_exists(arc, DUNGEON_HOST_SERVICES):
        violations.append("ARC JAR is missing its DungeonHostServices implementation")
    if class_exists(dungeons, DUNGEON_HOST_SERVICES):
        violations.append("ArcDungeons JAR embeds ARC's DungeonHostServices implementation")
    if class_exists(arc, DUNGEONS_SERVICE_PROVIDER):
        violations.append("ARC JAR still contains the moved DungeonsServiceProvider")
    if not class_exists(dungeons, DUNGEONS_SERVICE_PROVIDER):
        violations.append("ArcDungeons JAR is missing DungeonsServiceProvider")

    if dungeons_descriptor is None:
        violations.append("ArcDungeons JAR is missing plugin.yml")
    else:
        descriptor_lines = {line.strip() for line in dungeons_descriptor.splitlines()}
        if "name: ArcDungeons" not in descriptor_lines:
            violations.append("ArcDungeons plugin.yml must declare exactly `name: ArcDungeons`")
        if "depend: [ARC]" not in descriptor_lines:
            violations.append("ArcDungeons plugin.yml must declare the hard dependency `depend: [ARC]`")

    for package in API_PACKAGES:
        if not any(entry.startswith(package) and entry.endswith(".class") for entry in arc):
            violations.append(f"ARC JAR is missing shared API package {package}")
        if any(entry.startswith(package) and entry.endswith(".class") for entry in dungeons):
            violations.append(f"ArcDungeons JAR embeds shared API package {package}")

    for class_name in API_CLASSES:
        if class_name not in arc:
            violations.append(f"ARC JAR is missing shared API class {class_name}")

    for class_name in PROVIDER_CLASSES:
        if not class_exists(arc, class_name):
            violations.append(f"ARC JAR is missing provider implementation {class_name}")
        if class_exists(dungeons, class_name):
            violations.append(f"ArcDungeons JAR embeds provider implementation {class_name}")

    if any(entry.startswith(MOVED_PACKAGE) and entry.endswith(".class") for entry in arc):
        violations.append(f"ARC JAR still contains moved package {MOVED_PACKAGE}")
    if MOVED_CLASS not in dungeons:
        violations.append(f"ArcDungeons JAR is missing moved implementation {MOVED_CLASS}")

    for package, required_class in MOVED_IMPLEMENTATIONS:
        implementation_entries = {
            entry
            for entry in arc
            if entry.startswith(package)
            and not entry.startswith("ru/arc/staffspells/api/")
            and entry.endswith(".class")
        }
        if implementation_entries:
            violations.append(f"ARC JAR still contains moved implementation package {package}")
        dungeons_implementation_entries = {
            entry
            for entry in dungeons
            if entry.startswith(package)
            and not entry.startswith("ru/arc/staffspells/api/")
            and entry.endswith(".class")
        }
        if required_class not in dungeons_implementation_entries:
            violations.append(f"ArcDungeons JAR is missing moved implementation {required_class}")

    for class_prefix, required_class in MOVED_CLASS_PREFIXES:
        if any(entry.startswith(class_prefix) and entry.endswith(".class") for entry in arc):
            violations.append(f"ARC JAR still contains moved implementation {class_prefix}*")
        if required_class not in dungeons:
            violations.append(f"ArcDungeons JAR is missing moved implementation {required_class}")

    for resource in MOVED_RESOURCES:
        if resource in arc:
            violations.append(f"ARC JAR still contains moved resource {resource}")
        if resource not in dungeons:
            violations.append(f"ArcDungeons JAR is missing moved resource {resource}")

    return violations


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arc-jar", required=True, type=Path, help="built ARC plugin JAR")
    parser.add_argument("--dungeons-jar", required=True, type=Path, help="built ArcDungeons plugin JAR")
    args = parser.parse_args(argv)

    try:
        arc_entries, _ = load_archive(args.arc_jar)
        dungeons_entries, dungeons_descriptor = load_archive(args.dungeons_jar)
        violations = verify(arc_entries, dungeons_entries, dungeons_descriptor)
    except ValueError as failure:
        print(f"ARC_DUNGEONS_ARTIFACT_SPLIT_ERROR {failure}", file=sys.stderr)
        return 2

    if violations:
        for violation in violations:
            print(f"ARC_DUNGEONS_ARTIFACT_SPLIT_ERROR {violation}")
        print(f"ARC_DUNGEONS_ARTIFACT_SPLIT_SUMMARY status=failed violations={len(violations)}")
        return 1

    print("ARC_DUNGEONS_ARTIFACT_SPLIT_SUMMARY status=ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
