#!/usr/bin/env python3
"""Check that ARC and ArcDungeons keep their runtime and API classes separate."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


PAPER_API_PACKAGE = "ru/arc/paper/api/"
ARC_CORE_API_PREFIXES = ("ArcSql", "ArcRedis", "ArcMetric", "ArcSchematic")
PAPER_API_VISUAL_CLASS = "ru/arc/paper/api/VisualPacketAdmission.class"
DOMAIN_API_PACKAGES = (
    "ru/arc/dungeons/api/",
    "ru/arc/staffspells/api/",
)
DOMAIN_API_CLASSES = (
    "ru/arc/dungeons/api/ArcDungeonsService.class",
    "ru/arc/staffspells/api/ArcStaffSpellService.class",
)
PROVIDER_CLASSES = (
    "ru/arc/paper/redis/PaperRedisServiceProvider",
    "ru/arc/paper/redis/SharedPaperRedisService",
    "ru/arc/paper/sql/PaperSqlServiceProvider",
    "ru/arc/paper/sql/SharedPaperSqlService",
    "ru/arc/paper/metrics/PaperArcMetricsProvider",
)
ARC_CORE_RUNTIME_CLASS_PREFIXES = (
    "ru/arc/redis/RedisTelemetrySink",
    "ru/arc/redis/NoOpRedisTelemetrySink",
    "ru/arc/redis/RedisOperation",
    "ru/arc/redis/RedisOperationResult",
    "ru/arc/redis/RedisReconnectPath",
    "ru/arc/redis/RedisReconnectResult",
)
ARC_CORE_RUNTIME_PACKAGES = (
    "io/micrometer/core/",
    "io/micrometer/common/",
    "io/micrometer/observation/",
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


def class_entries(entries: set[str], prefix: str) -> set[str]:
    return {entry for entry in entries if entry.startswith(prefix) and entry.endswith(".class")}


def dependency_names(descriptor: str | None, key: str) -> set[str]:
    lines = (descriptor or "").splitlines()
    for index, line in enumerate(lines):
        name, separator, value = line.strip().partition(":")
        if not separator or name != key:
            continue
        value = value.strip()
        if value.startswith("[") and value.endswith("]"):
            return {item.strip().strip("'\"") for item in value[1:-1].split(",") if item.strip()}
        dependencies = set()
        for following in lines[index + 1:]:
            if not following.startswith((" ", "\t")):
                break
            item = following.strip()
            if item.startswith("-"):
                dependencies.add(item[1:].strip().strip("'\""))
            elif item:
                break
        return dependencies
    return set()


def verify_arc_core_ownership(
    arc: set[str],
    arc_core: set[str],
    children: tuple[tuple[str, set[str]], ...],
    arc_descriptor: str | None,
    arc_core_descriptor: str | None,
) -> list[str]:
    violations = []
    arc_core_api = class_entries(arc_core, PAPER_API_PACKAGE)
    arc_api = class_entries(arc, PAPER_API_PACKAGE)
    arc_core_owned_api = {
        entry
        for entry in arc_core_api
        if entry.rsplit("/", 1)[-1].startswith(ARC_CORE_API_PREFIXES)
    }
    for prefix in ARC_CORE_API_PREFIXES:
        if not any(entry.rsplit("/", 1)[-1].startswith(prefix) for entry in arc_core_api):
            violations.append(f"ArcCore JAR is missing shared Paper API family {prefix}*")
    for entry in arc_core_api - arc_core_owned_api:
        violations.append(f"ArcCore JAR embeds ARC-owned Paper API class {entry}")
    for entry in arc_api:
        if entry.rsplit("/", 1)[-1].startswith(ARC_CORE_API_PREFIXES):
            violations.append(f"ARC JAR embeds ArcCore-owned Paper API class {entry}")
    if PAPER_API_VISUAL_CLASS not in arc_api:
        violations.append(f"ARC JAR is missing ARC-owned Paper API class {PAPER_API_VISUAL_CLASS}")
    if PAPER_API_VISUAL_CLASS in arc_core_api:
        violations.append(f"ArcCore JAR embeds ARC-owned Paper API class {PAPER_API_VISUAL_CLASS}")
    for name, child in children:
        for entry in class_entries(child, PAPER_API_PACKAGE):
            violations.append(f"{name} JAR embeds shared Paper API class {entry}")

    for class_name in PROVIDER_CLASSES:
        if not class_exists(arc_core, class_name):
            violations.append(f"ArcCore JAR is missing shared provider implementation {class_name}")
        if class_exists(arc, class_name):
            violations.append(f"ARC JAR embeds ArcCore provider implementation {class_name}")
        for name, child in children:
            if class_exists(child, class_name):
                violations.append(f"{name} JAR embeds ArcCore provider implementation {class_name}")

    for prefix in ARC_CORE_RUNTIME_CLASS_PREFIXES:
        if not class_exists(arc_core, prefix):
            violations.append(f"ArcCore JAR is missing shared runtime class {prefix}*")
        if class_exists(arc, prefix):
            violations.append(f"ARC JAR embeds ArcCore runtime class {prefix}*")
    for package in ARC_CORE_RUNTIME_PACKAGES:
        if not class_entries(arc_core, package):
            violations.append(f"ArcCore JAR is missing shared runtime package {package}")
        if class_entries(arc, package):
            violations.append(f"ARC JAR embeds ArcCore runtime package {package}")

    if "name: ArcCore" not in (arc_core_descriptor or "").splitlines():
        violations.append("ArcCore plugin descriptor must declare exactly name ArcCore")
    if "ArcCore" not in dependency_names(arc_descriptor, "depend"):
        violations.append("ARC must declare a hard dependency on ArcCore")
    arc_core_dependencies = dependency_names(arc_core_descriptor, "depend")
    arc_core_dependencies.update(dependency_names(arc_core_descriptor, "softdepend"))
    if "ARC" in arc_core_dependencies:
        violations.append("ArcCore must not depend on ARC")
    return violations


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


def verify(
    arc: set[str],
    arc_core: set[str],
    dungeons: set[str],
    arc_descriptor: str | None,
    arc_core_descriptor: str | None,
    dungeons_descriptor: str | None,
) -> list[str]:
    violations = verify_arc_core_ownership(
        arc,
        arc_core,
        (("ArcDungeons", dungeons),),
        arc_descriptor,
        arc_core_descriptor,
    )

    if not class_exists(arc, ARC_ENTRYPOINT):
        violations.append("ARC JAR is missing ru/arc/ARC.class")
    if DUNGEONS_ENTRYPOINT not in dungeons:
        violations.append("ArcDungeons JAR is missing ru/arc/dungeons/ArcDungeonsPlugin.class")
    if class_exists(dungeons, ARC_ENTRYPOINT):
        violations.append("ArcDungeons JAR embeds the ARC plugin entrypoint")
    if class_entries(dungeons, "com/magmaguy/"):
        violations.append("ArcDungeons JAR embeds EliteMobs or its native dependency classes; the addon must use the stock plugin")
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

    for package in DOMAIN_API_PACKAGES:
        if not any(entry.startswith(package) and entry.endswith(".class") for entry in arc):
            violations.append(f"ARC JAR is missing shared API package {package}")
        if any(entry.startswith(package) and entry.endswith(".class") for entry in dungeons):
            violations.append(f"ArcDungeons JAR embeds shared API package {package}")
        if any(entry.startswith(package) and entry.endswith(".class") for entry in arc_core):
            violations.append(f"ArcCore JAR embeds ARC-owned domain API package {package}")

    for class_name in DOMAIN_API_CLASSES:
        if class_name not in arc:
            violations.append(f"ARC JAR is missing shared API class {class_name}")

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
    parser.add_argument("--arc-core-jar", required=True, type=Path, help="built ArcCore plugin JAR")
    parser.add_argument("--dungeons-jar", required=True, type=Path, help="built ArcDungeons plugin JAR")
    args = parser.parse_args(argv)

    try:
        arc_entries, arc_descriptor = load_archive(args.arc_jar)
        arc_core_entries, arc_core_descriptor = load_archive(args.arc_core_jar)
        dungeons_entries, dungeons_descriptor = load_archive(args.dungeons_jar)
        violations = verify(
            arc_entries,
            arc_core_entries,
            dungeons_entries,
            arc_descriptor,
            arc_core_descriptor,
            dungeons_descriptor,
        )
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
