#!/usr/bin/env python3
"""Render exported HelpCenter screens with their actual Dialogue font providers."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import yaml


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--states", default="commands,help,settings,interface,social,world,rules")
    parser.add_argument("--output", type=Path, default=root / "build/reports/help-center-decoration/preview")
    parser.add_argument("--ops-root", type=Path, required=True)
    parser.add_argument("--client-jar", type=Path)
    parser.add_argument("--resource-pack", type=Path)
    parser.add_argument("--checkpoint", action="store_true", help="Inspect an earlier compiled export while source editing continues")
    args = parser.parse_args()
    export = root / "build/reports/help-center-decoration"
    content_path = export / "content.json"
    components_path = export / "components.json"
    content = json.loads(content_path.read_text())
    components = json.loads(components_path.read_text())
    sources = [root / "src/main/kotlin/ru/arc/helpcenter" / name for name in (
        "HelpCenterController.kt", "HelpCenterHubController.kt", "HelpCenterSettingsController.kt",
        "HelpCenterPresentation.kt", "HelpCenterConfig.kt", "HelpCenterGuideCatalog.kt",
    )] + [root / "src/main/resources/modules/help-center.yml"]
    if not args.checkpoint and any(path.stat().st_mtime > min(content_path.stat().st_mtime, components_path.stat().st_mtime) for path in sources):
        raise SystemExit("Stale export: run :test --tests ru.arc.helpcenter.HelpCenterScreensTest")
    values, dialogs, providers = {}, [], {}

    def collect(node, font="minecraft:default"):
        if isinstance(node, list):
            for child in node:
                collect(child, font)
        elif isinstance(node, dict):
            font = node.get("font", font)
            raw_text = node.get("text", "")
            for character in raw_text if isinstance(raw_text, str) else "":
                point = ord(character)
                if 0xE000 <= point <= 0xF8FF or 0xF0000 <= point <= 0x10FFFD:
                    previous = providers.setdefault(character, font)
                    if previous != font:
                        raise ValueError(f"Conflicting providers for U+{point:04X}")
            for key, child in node.items():
                if key != "font" and not (key == "text" and isinstance(child, str)):
                    collect(child, font)

    for state in args.states.split(","):
        screen = content[state]
        collect(components[state])
        text = {"title": screen["title"]}
        body, buttons, inputs = [], [], []
        for index, part in enumerate(screen["body"]):
            key = f"body_{index}"
            text[key] = part["text"]
            body.append({"id": key, "text-key": f"{state}.{key}", "width": part["width"]})
        for item in screen.get("inputs", []):
            key = f"input_{item['id']}"
            text[key], text[f"{key}_initial"] = item["label"], item["initial"]
            inputs.append({"id": key, "label-key": f"{state}.{key}",
                           "initial-key": f"{state}.{key}_initial", "width": item["width"]})
        for item in screen["buttons"]:
            key = item["id"]
            text[f"{key}_label"], text[f"{key}_tip"] = item["text"], item["tooltip"]
            buttons.append({"id": key, "text-key": f"{state}.{key}_label",
                            "tooltip-key": f"{state}.{key}_tip", "width": item["width"]})
        dialog = {"id": state, "source": "rendered", "format": "reference-multi-action",
                  "name-key": f"{state}.title", "columns": screen["columns"], "body": body,
                  "inputs": inputs, "buttons": buttons, "viewport": [640, 360], "variants": [{"id": "default"}]}
        if screen["exit"]:
            text["exit"] = screen["exit"]["text"]
            text["exit_tip"] = ""
            dialog["exit-button"] = {"id": "exit", "text-key": f"{state}.exit", "tooltip-key": f"{state}.exit_tip", "width": screen["exit"]["width"]}
        values[state] = text
        dialogs.append(dialog)
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    resolved = output.parent / "resolved-preview.json"
    resolved.write_text(json.dumps(values, ensure_ascii=False, indent=2))
    baseline = yaml.safe_load((root / "visual-preview.yml").read_text())
    manifest = {key: baseline[key] for key in ("schema", "minecraft-version", "minecraft-client-sha1", "resource-pack")}
    # Official 26.1.2 metadata: piston-meta.mojang.com/v1/packages/8f9f3c548c892ce41b78561138aeba112cba3490/26.1.2.json
    asset_index = baseline.get("minecraft-asset-index-sha1")
    if asset_index is None and manifest["minecraft-client-sha1"] == "4e618f09a0c649dde3fdf829df443ce0b8831e65":
        asset_index = "f9db0e2e972fc061332c789d9f54a8d8fc7c9527"
    if asset_index is None:
        raise SystemExit("The selected client needs a verified minecraft-asset-index-sha1")
    manifest["minecraft-asset-index-sha1"] = asset_index
    manifest.update({"plugin": "ARC — поиск, справка и настройки", "gallery-accent": "#86dcf1",
                     "sources": {"rendered": {"path": str(resolved), "root": ""}},
                     "surfaces": {"dialogs": dialogs},
                     "glyphs": [{"character": character, "provider": provider} for character, provider in providers.items()],
                     "limitations": ["Compiled PaperDialogScreen composition over a sample Minecraft landscape; not a live client capture.",
                                     "Legacy preview serialization retains colors, text and measured glyphs; per-span shadow overrides are not modeled."]})
    if args.checkpoint:
        manifest["limitations"].append("Earlier compiled checkpoint; current working-tree edits may differ.")
    manifest_path = output.parent / "preview-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    command = ["python3", "-B", str(args.ops_root / ".agents/skills/design-ruscrafting-surfaces/scripts/render_plugin_surface_dump.py"),
               "--manifest", str(manifest_path), "--project-root", str(root), "--output", str(output)]
    for name, path in (("client-jar", args.client_jar), ("resource-pack", args.resource_pack)):
        if path:
            command += [f"--{name}", str(path)]
    result = subprocess.run(command, check=False).returncode
    if result == 0:
        def hashes(paths):
            return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
        evidence = {"checkpoint": args.checkpoint, "exports": hashes([content_path, components_path]),
                    "sources": {} if args.checkpoint else hashes(sources)}
        (output / "source-evidence.json").write_text(json.dumps(evidence, indent=2))
    return result


if __name__ == "__main__":
    raise SystemExit(main())
