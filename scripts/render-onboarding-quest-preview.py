#!/usr/bin/env python3
"""Render the actual OnboardingQuestDialogTest export with the existing surface/atelier tools."""
import argparse
import base64
import hashlib
import importlib.util
import io
import json
import subprocess
import sys
from pathlib import Path

import yaml
from PIL import Image


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded
    spec.loader.exec_module(loaded)
    return loaded


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--states", default="offer,home")
    parser.add_argument("--marker", action="store_true", help="Also render the unchanged NPC marker")
    parser.add_argument("--output", type=Path, default=root / "build/reports/onboarding-quest/preview")
    parser.add_argument("--ops-root", type=Path, default=root.parent / "ruscrafting-ops")
    parser.add_argument("--client-jar", type=Path)
    parser.add_argument("--resource-pack", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    exports = root / "build/reports/onboarding-quest"
    content = json.loads((exports / "content.json").read_text())
    source_names = ["OnboardingQuestDialog.kt"] + (["OnboardingQuestNpc.kt"] if args.marker else [])
    sources = [root / "src/main/kotlin/ru/arc/onboarding" / name for name in source_names]
    if any(path.stat().st_mtime > (exports / "content.json").stat().st_mtime for path in sources):
        raise SystemExit("Preview export is stale; run :test --tests ru.arc.onboarding.OnboardingQuestDialogTest")
    source_text, dialogs = {}, []
    for state in args.states.split(","):
        screen = content[state]
        values = {"title": screen["title"], "close": screen["exit"]["text"], "close_tip": ""}
        body, buttons = [], []
        for index, part in enumerate(screen["body"]):
            key = f"body_{index}"
            values[key] = part["text"]
            body.append({"id": key, "text-key": f"{state}.{key}", "width": part["width"]})
        for button in screen["buttons"]:
            key = button["id"]
            values[f"{key}_label"] = button["text"]
            values[f"{key}_tip"] = button["tooltip"]
            buttons.append({"id": key, "text-key": f"{state}.{key}_label",
                            "tooltip-key": f"{state}.{key}_tip", "width": button["width"]})
        source_text[state] = values
        dialogs.append({"id": state, "source": "rendered", "format": "reference-multi-action",
                        "name-key": f"{state}.title", "columns": screen["columns"], "body": body,
                        "buttons": buttons, "exit-button": {"id": "close", "text-key": f"{state}.close",
                        "tooltip-key": f"{state}.close_tip", "width": screen["exit"]["width"]},
                        "viewport": [640, 360], "variants": [{"id": "default"}]})
    resolved = output.parent / "resolved-preview.json"
    resolved.write_text(json.dumps(source_text, ensure_ascii=False, indent=2))
    baseline = yaml.safe_load((root / "visual-preview.yml").read_text())
    manifest = {key: baseline[key] for key in ("schema", "minecraft-version", "minecraft-client-sha1", "resource-pack")}
    manifest.update({"plugin": "ARC · Первый дом", "gallery-accent": "#c4abff",
                     "sources": {"rendered": {"path": str(resolved), "root": ""}},
                     "surfaces": {"dialogs": dialogs},
                     "limitations": ["Actual compiled PaperDialogScreen export. Offline composition only; no native-client or runtime acceptance."]})
    characters = {char for values in source_text.values() for text in values.values() for char in text
                  if 0xE000 <= ord(char) <= 0xF8FF or 0xF0000 <= ord(char) <= 0xFFFFD}
    manifest["glyphs"] = [{"character": char, "provider": "minecraft:default"} for char in sorted(characters)]
    manifest_path = output.parent / "preview-manifest.yml"
    manifest_path.write_text(yaml.safe_dump(manifest, allow_unicode=True, sort_keys=False))
    surface = module("quest_surface", args.ops_root / ".agents/skills/design-ruscrafting-surfaces/scripts/render_plugin_surface_dump.py")
    render_args = ["--manifest", str(manifest_path), "--project-root", str(root), "--output", str(output), "--gui-scale", "3"]
    if args.client_jar:
        render_args += ["--client-jar", str(args.client_jar)]
    if args.resource_pack:
        render_args += ["--resource-pack", str(args.resource_pack)]
    if surface.main(render_args):
        raise SystemExit("Native dialog preview failed")
    (output / "source-evidence.json").write_text(json.dumps({str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                                                           for path in sources}, indent=2))
    if not args.marker:
        print(output / "index.html")
        return

    # The same renderer paints the actual text. The existing atelier supplies 3D views.
    marker = json.loads((exports / "marker.json").read_text())
    preview = output / "marker"
    preview.mkdir(exist_ok=True)
    client = surface.resolve_client_jar(manifest, args.client_jar, output.parent / ".surface-cache")
    stack = surface.create_stack(client, 1, {})
    text_width = surface.CORE.measured_width(marker["text"], stack.advances)
    width = text_width + 4
    height = 12
    texture = Image.new("RGBA", (max(width, height), max(width, height)))
    surface.CORE.draw_text(texture, ((texture.width - text_width) // 2, 1), marker["text"], stack, shadow=marker["shadow"])
    stream = io.BytesIO()
    texture.save(stream, format="PNG")
    image_url = "data:image/png;base64," + base64.b64encode(stream.getvalue()).decode()
    atelier = args.ops_root / "scripts/location-atelier"
    vanilla = module("quest_vanilla", atelier / "vanilla_assets.py")
    archive = vanilla.SafeArchive(str(client))
    baker = vanilla.Baker(archive)
    palette = {"version": baseline["minecraft-version"], "blocks": {}, "textures": {}}
    for block in ("minecraft:stone_bricks", "minecraft:gray_concrete"):
        palette["blocks"][block] = baker.bake_block(block)
    palette["textures"].update(baker.textures)
    archive.close()
    palette["textures"]["preview:quest"] = image_url
    palette["blocks"]["preview:quest"] = {"occludes": False, "quads": [{
        "texture": "preview:quest", "normal": [0, 0, -1],
        "vertices": [[0, 0, .5], [0, 1, .5], [1, 1, .5], [1, 0, .5]],
        "uv": [[0, 1], [0, 0], [1, 0], [1, 1]],
    }]}
    side = texture.width * .025 * marker["scale"]
    pieces = [dict(key="floor", material="minecraft:stone_bricks", x=0, y=-.1, z=0, width=6, height=.2, depth=5),
              dict(key="npc-scale-body", material="minecraft:gray_concrete", x=0, y=.7, z=0, width=.6, height=1.4, depth=.3),
              dict(key="npc-scale-head", material="minecraft:gray_concrete", x=0, y=1.6, z=0, width=.4, height=.4, depth=.4),
              dict(key="quest", material="preview:quest", x=0, y=marker["height"] + side / 2, z=0,
                   width=side, height=side, depth=.01, billboard=True, fullBright=True)]
    scene = {"camera": {"position": {"x": 0, "y": 1.62, "z": -6}, "target": {"x": 0, "y": 1.5, "z": 0}, "fovDegrees": 70},
             "states": [{"id": "offer", "title": "Первый дом · персональное приглашение",
                         "role": "Один знак !. Серый силуэт показывает масштаб NPC 1.8 м; окружение условное.", "pieces": pieces}],
             "evidence": marker, "scope": "Source transform and exact Minecraft font; schematic NPC and floor. No native appearance/visibility acceptance."}
    (preview / "scene.json").write_text(json.dumps(scene, ensure_ascii=False, indent=2))
    (preview / "palette.json").write_text(json.dumps(palette))
    template = (atelier / "workshop-preview.template.html").read_text()
    for old, new in [
        ("pieceGroup.rotation.copy(rotation);", "pieceGroup.rotation.copy(rotation); pieceGroup.userData.billboard = !!piece.billboard;"),
        ("const material = new THREE.MeshStandardMaterial({map:texture,vertexColors:true,roughness:0.96,alphaTest:0.1,side:THREE.DoubleSide});",
         "const material = piece.fullBright ? new THREE.MeshBasicMaterial({map:texture,vertexColors:true,alphaTest:0.1,side:THREE.DoubleSide}) : new THREE.MeshStandardMaterial({map:texture,vertexColors:true,roughness:0.96,alphaTest:0.1,side:THREE.DoubleSide});"),
        ("controls.update();\n        renderer.render(scene,camera);",
         "controls.update();\n        for (const child of work.children) if (child.userData.billboard) child.quaternion.copy(camera.quaternion);\n        renderer.render(scene,camera);"),
    ]:
        if template.count(old) != 1:
            raise SystemExit("Atelier template seam changed")
        template = template.replace(old, new, 1)
    template = template.replace("Мебельная мастерская", "Начальный квест")
    template = template.replace("Техническое превью: станки, этапы и готовая мебель; пол условный, без мира и NPC",
                                "Геометрия знака из кода; шрифт Minecraft. NPC и окружение условные. Это офлайн-превью.")
    (preview / "template.html").write_text(template)
    subprocess.run(["node", str(atelier / "build-workshop-preview.mjs"), "--scene", str(preview / "scene.json"),
                    "--palette", str(preview / "palette.json"), "--template", str(preview / "template.html"),
                    "--output", str(preview / "index.html")], check=True)
    print(output / "index.html")


if __name__ == "__main__":
    main()
