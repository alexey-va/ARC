#!/usr/bin/env python3
"""Render GraveAppearancePreview's production geometry with vanilla models and Minecraft text."""
import argparse
import base64
import hashlib
import importlib.util
import io
import json
import math
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


def image_url(image):
    output = io.BytesIO()
    image.save(output, format="PNG")
    return "data:image/png;base64," + base64.b64encode(output.getvalue()).decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geometry", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ops-root", type=Path, required=True)
    parser.add_argument("--client-jar", type=Path)
    parser.add_argument("--model", help="Optional one-model inspection, e.g. cairn or slate-engraved")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    atelier = args.ops_root / "scripts/location-atelier"
    surface = module("grave_surface", args.ops_root / ".agents/skills/design-ruscrafting-surfaces/scripts/render_plugin_surface_dump.py")
    vanilla = module("grave_vanilla", atelier / "vanilla_assets.py")
    exported = json.loads(args.geometry.read_text())
    manifest = yaml.safe_load((root / "visual-preview.yml").read_text())
    client = surface.resolve_client_jar(manifest, args.client_jar, out / ".surface-cache")
    stack = surface.create_stack(client, 1, {"gallery-accent": "#e8dfd2"})
    palette = {"version": manifest["minecraft-version"], "blocks": {}, "textures": {}}
    models = [model for model in exported["models"] if not args.model or
              model["id"] + ("-engraved" if model["engraved"] else "") == args.model]
    if not models:
        raise ValueError("No model matched --model")
    archive = vanilla.SafeArchive(str(client))
    baker = vanilla.Baker(archive)
    materials = {"minecraft:" + part["material"].lower() for model in models for part in model["parts"]}
    for material in materials | {"minecraft:grass_block", "minecraft:gray_concrete"}:
        palette["blocks"][material] = baker.bake_block(material)
    palette["textures"].update(baker.textures)
    archive.close()

    def plane(key, bitmap):
        palette["textures"][key] = image_url(bitmap)
        # Front is local -Z: bitmap left is +X when viewed from north, as in the native TextDisplay.
        palette["blocks"][key] = {"occludes": False, "quads": [{
            "texture": key, "normal": [0, 0, -1],
            "vertices": [[0, 0, .5], [0, 1, .5], [1, 1, .5], [1, 0, .5]],
            "uv": [[1, 1], [1, 0], [0, 0], [0, 1]],
        }]}

    plane("preview:engraving", Image.open(root / "src/main/resources/graves/engraving/witness.png").convert("RGBA"))
    states = []
    for model in models:
        identity = model["id"] + ("-engraved" if model["engraved"] else "")
        pieces = []
        for index, part in enumerate(model["parts"]):
            pieces.append(dict(key=f"body-{index}", material="minecraft:" + part["material"].lower(),
                               x=part["x"] + part["width"] / 2, y=part["y"] + part["height"] / 2,
                               z=part["z"] + part["depth"] / 2, **{key: part[key] for key in
                               ("width", "height", "depth", "rotationX", "rotationY", "rotationZ")}))
        if model["engraved"]:
            pieces.append(dict(key="engraving", material="preview:engraving", x=0, y=1, z=-.53125,
                               width=1, height=2, depth=.001))
        inscription = model["inscription"]
        for index, placement in enumerate(model["placements"]):
            lines = placement["lines"]
            assert len(lines) == placement["lineCount"]
            widths = [surface.CORE.measured_width(line, stack.advances) for line in lines]
            assert max(widths) <= placement["lineWidth"], (identity, widths, placement["lineWidth"])
            width, height = max(widths), len(lines) * 10 - 1
            bitmap = Image.new("RGBA", (width, height))
            for line_index, (line, line_width) in enumerate(zip(lines, widths)):
                surface.CORE.draw_text(bitmap, ((width - line_width) / 2, line_index * 10),
                                       f"&#{inscription['color']:06X}" + line, stack, shadow=False)
            key = f"preview:{identity}-text-{index}"
            plane(key, bitmap)
            pixel = .025 * placement["scale"]
            offset = placement["bottomY"] + height * pixel / 2 - inscription["centerY"]
            tilt = math.radians(inscription["rotationX"])
            pieces.append(dict(key=f"text-{index}", material=key, x=inscription["x"] - pixel,
                               y=inscription["centerY"] + math.cos(tilt) * offset,
                               z=inscription["z"] + math.sin(tilt) * offset,
                               width=width * pixel, height=height * pixel, depth=.001,
                               rotationX=inscription["rotationX"]))
        # Familiar scale and a non-uniform world background make the plaque's contrast visible.
        pieces.extend([
            dict(key="ground", material="minecraft:grass_block", x=0, y=-.15, z=0, width=4, height=.3, depth=4),
            dict(key="height-reference-body", material="minecraft:gray_concrete", x=-1.35, y=.7, z=.35, width=.6, height=1.4, depth=.3),
            dict(key="height-reference-head", material="minecraft:gray_concrete", x=-1.35, y=1.6, z=.35, width=.4, height=.4, depth=.4),
        ])
        states.append(dict(id=identity, title=identity, pieces=pieces,
                           role=f"{len(exported['epitaph'])} знаков и ник {exported['owner']}; фигура 1,8 м.",
                           camera=dict(position=dict(x=0, y=1.62, z=-2), target=dict(x=0, y=.8 if model["engraved"] else .5, z=0), fovDegrees=70)))
    owners = [root / "src/main/kotlin/ru/arc/graves" / name for name in ("GraveAppearance.kt", "GravesHud.kt")]
    scene = dict(states=states, evidence=dict(
        sources={str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in owners},
        geometrySha256=hashlib.sha256(args.geometry.read_bytes()).hexdigest(), client=str(client),
        scope="Production geometry and wrapped lines; vanilla textured offline renderer. Lighting, maps and packet/native-client visibility are not runtime acceptance."))
    (out / "scene.json").write_text(json.dumps(scene, ensure_ascii=False, indent=2))
    (out / "palette.json").write_text(json.dumps(palette))
    template = (atelier / "workshop-preview.template.html").read_text()
    template = template.replace("Мебельная мастерская · техническое превью", "Могилы · таблички и надписи")
    template = template.replace("Мебельная мастерская — техническое превью", "Могилы · таблички и надписи")
    template = template.replace("Техническое превью: станки, этапы и готовая мебель; пол условный, без мира и NPC",
                                "Размеры и переносы из кода. Текстуры и шрифт Minecraft; офлайн-превью без клиентской проверки.")
    (out / "template.html").write_text(template)
    subprocess.run(["node", str(atelier / "build-workshop-preview.mjs"), "--scene", str(out / "scene.json"),
                    "--palette", str(out / "palette.json"), "--template", str(out / "template.html"),
                    "--output", str(out / "index.html")], check=True)
    print(out / "index.html")


if __name__ == "__main__":
    main()
