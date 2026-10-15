#!/usr/bin/env python3
"""Verify one independently released ARC domain against its host JAR."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from verify_arc_dungeons_artifact_split import (
    class_entries,
    dependency_names,
    load_archive,
    verify_arc_core_ownership,
)

DOMAINS = {
    "ArcMounts": ("mounts", "ArcMountsPlugin", "ArcMountService", (
        "modules/mounts.yml", "modules/mount-care-boost.yml", "modules/mount-transfers.yml",
    )),
    "ArcOrigin": ("origin", "ArcOriginPlugin", "ArcOriginCommands", (
        "modules/origin-spawn.yml", "modules/origin-dining.yml", "modules/origin-scenes.yml",
        "modules/origin-furniture-workshop.yml", "modules/origin-workshop-game.yml",
        "modules/origin-workshop-tables.yml", "modules/origin-mount-yard.yml", "modules/origin-mount-care.yml",
        "origin-workshop-reward-pool.json",
    )),
    "ArcContracts": ("contracts", "ArcContractsPlugin", "ArcContractsService", ("modules/contracts.yml",)),
}


def verify(
    host: set[str],
    arc_core: set[str],
    child: set[str],
    host_descriptor: str | None,
    arc_core_descriptor: str | None,
    child_descriptor: str | None,
    name: str,
) -> list[str]:
    domain, entrypoint, service, resources = DOMAINS[name]
    package = f"ru/arc/{domain}/"
    api = f"{package}api/"
    errors = verify_arc_core_ownership(
        host,
        arc_core,
        ((name, child),),
        host_descriptor,
        arc_core_descriptor,
    )
    if f"{api}{service}.class" not in host:
        errors.append(f"ARC must own {api}{service}.class")
    if any(p.startswith(package) and not p.startswith(api) and p.endswith(".class") for p in host):
        errors.append(f"ARC still contains {domain} implementation")
    if class_entries(arc_core, package):
        errors.append(f"ArcCore embeds ARC-owned {domain} API or implementation")
    if f"{package}{entrypoint}.class" not in child:
        errors.append(f"{name} entrypoint is missing")
    if "ru/arc/ARC.class" in child:
        errors.append(f"{name} embeds the ARC entrypoint")
    for entry in child:
        if entry.endswith(".class") and entry.startswith("ru/arc/") and "/api/" in entry:
            errors.append(f"{name} embeds shared contract {entry}")
    for resource in resources:
        if resource in host:
            errors.append(f"ARC still owns moved resource {resource}")
        if resource not in child:
            errors.append(f"{name} is missing resource {resource}")
    lines = (child_descriptor or "").splitlines()
    if f"name: {name}" not in lines or "ARC" not in dependency_names(child_descriptor, "depend"):
        errors.append(f"{name} must declare its identity and a hard ARC dependency")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True, choices=DOMAINS)
    parser.add_argument("--arc-jar", required=True, type=Path)
    parser.add_argument("--arc-core-jar", required=True, type=Path)
    parser.add_argument("--child-jar", required=True, type=Path)
    args = parser.parse_args()
    try:
        host, host_descriptor = load_archive(args.arc_jar)
        arc_core, arc_core_descriptor = load_archive(args.arc_core_jar)
        child, descriptor = load_archive(args.child_jar)
        errors = verify(
            host,
            arc_core,
            child,
            host_descriptor,
            arc_core_descriptor,
            descriptor,
            args.name,
        )
    except ValueError as failure:
        errors = [str(failure)]
    for error in errors:
        print(f"ARC_DOMAIN_SPLIT_ERROR {error}", file=sys.stderr)
    print(f"ARC_DOMAIN_SPLIT name={args.name} status={'failed' if errors else 'ok'}")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
