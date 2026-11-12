#!/usr/bin/env python3
"""Render ChestPreviewPlacementTest's source export using the existing atelier and vanilla assets."""
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


BLOCKS = "stone dirt cobblestone oak_planks spruce_planks birch_planks bricks stone_bricks iron_block gold_block diamond_block emerald_block coal_block redstone_block lapis_block copper_block".split()
ITEMS = "diamond emerald coal charcoal iron_ingot gold_ingot copper_ingot netherite_ingot raw_iron raw_gold raw_copper redstone lapis_lazuli quartz amethyst_shard flint stick feather leather bone string gunpowder wheat wheat_seeds carrot potato apple bread rotten_flesh ender_pearl slime_ball blaze_rod blaze_powder paper book gold_nugget iron_nugget diamond_pickaxe".split()
COUNTS = [37, 1, 59, 2, 64, 21, 144, 1, 4, 64, 20, 4, 1, 2, 37, 10, 255, 198, 53, 31, 11, 4, 1, 52, 3, 28, 2] + list(range(1, 28))


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded
    spec.loader.exec_module(loaded)
    return loaded


def data_url(bitmap):
    stream = io.BytesIO()
    bitmap.save(stream, format="PNG")
    return "data:image/png;base64," + base64.b64encode(stream.getvalue()).decode()


def rotate(vector, quaternion):
    x, y, z, w = quaternion
    vx, vy, vz = vector
    tx, ty, tz = 2 * (y * vz - z * vy), 2 * (z * vx - x * vz), 2 * (x * vy - y * vx)
    return [vx + w * tx + y * tz - z * ty, vy + w * ty + z * tx - x * tz, vz + w * tz + x * ty - y * tx]


def axis(vector, index, degrees):
    angle = math.radians(degrees) / 2
    quaternion = [0, 0, 0, math.cos(angle)]
    quaternion[index] = math.sin(angle)
    return rotate(vector, quaternion)


def gui_transform(vertex, pose):
    result = [(value - .5) * scale for value, scale in zip(vertex, pose.get("scale", [1, 1, 1]))]
    # JOML rotationXYZ / native GUI context: the rightmost Z rotation acts first.
    for index in (2, 1, 0):
        result = axis(result, index, pose.get("rotation", [0, 0, 0])[index])
    return [value + move / 16 for value, move in zip(result, pose.get("translation", [0, 0, 0]))]


def plane(texture):
    return {"vertices": [[0, 0, .5], [0, 1, .5], [1, 1, .5], [1, 0, .5]],
            "normal": [0, 0, 1], "uv": [[0, 1], [0, 0], [1, 0], [1, 1]], "texture": texture}


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("Atelier template seam changed: " + old[:80])
    return text.replace(old, new, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geometry", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ops-root", type=Path, required=True)
    parser.add_argument("--client-jar", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    exported = json.loads(args.geometry.read_text())
    settings = exported["settings"]
    atelier = args.ops_root / "scripts/location-atelier"
    surface = module("chest_surface", args.ops_root / ".agents/skills/design-ruscrafting-surfaces/scripts/render_plugin_surface_dump.py")
    vanilla = module("chest_vanilla", atelier / "vanilla_assets.py")
    manifest = yaml.safe_load((root / "visual-preview.yml").read_text())
    client = surface.resolve_client_jar(manifest, args.client_jar, output / ".surface-cache")
    font = surface.create_stack(client, 1, {})
    archive = vanilla.SafeArchive(str(client))
    baker = vanilla.Baker(archive)
    palette = {"version": manifest["minecraft-version"], "blocks": {}, "textures": {}}
    for block in ("minecraft:stone_bricks", "minecraft:barrel", "minecraft:oak_planks"):
        palette["blocks"][block] = baker.bake_block(block)
    models = []
    for name in BLOCKS:
        model = baker.models.load("minecraft:block/" + name)
        models.append((baker.bake_model("minecraft:" + name, {"model": "minecraft:block/" + name}, "minecraft:" + name), model.get("display", {}).get("gui", {}), True))
    for name in ITEMS:
        # These selected generated/handheld GUI items use their exact layer0 bitmap.
        texture = "minecraft:item/" + name
        baker.ensure_texture(texture)
        models.append(([plane(texture)], {}, False))
    assert len(models) == len(COUNTS) == 54
    palette["textures"].update(baker.textures)
    archive.close()
    states = []
    for state in exported["states"]:
        yaw, pitch = math.radians(state["yaw"]), math.radians(state["pitch"])
        right = [-math.cos(yaw), 0, -math.sin(yaw)]
        up = [-math.sin(yaw) * math.sin(pitch), math.cos(pitch), math.cos(yaw) * math.sin(pitch)]
        normal = [math.sin(yaw) * math.cos(pitch), math.sin(pitch), -math.cos(yaw) * math.cos(pitch)]

        def world(local):
            return [.5 + sum(local[index] * basis[dimension] for index, basis in enumerate((right, up, normal))) for dimension in range(3)]

        pieces = [dict(key="floor", material="minecraft:stone_bricks", x=.5, y=-.1, z=.5, width=8, height=.2, depth=8),
                  dict(key="container", material="minecraft:barrel", x=.5, y=.5, z=.5, width=1, height=1, depth=1),
                  dict(key="stacked-container", material="minecraft:barrel", x=.5, y=1.5, z=.5, width=1, height=1, depth=1),
                  dict(key="back-wall", material="minecraft:oak_planks", x=.5, y=1.5, z=1.1, width=4, height=3, depth=.2)]
        for index, offset in enumerate(state["offsets"]):
            source, gui, block = models[index]
            quads = []
            for quad in source:
                vertices = []
                for vertex in quad["vertices"]:
                    local = gui_transform(vertex, gui)
                    local = rotate(local, [0, 1, 0, 0])  # native ItemDisplay half-turn
                    local = rotate(local, exported["blockRotation" if block else "flatRotation"])
                    local = [v * settings["iconScale"] * settings["scale"] * (settings["depthScale"] if d == 2 else 1) for d, v in enumerate(local)]
                    local[0] += offset[0]
                    local[1] += offset[1]
                    vertices.append(world(local))
                quads.append(dict(quad, vertices=vertices, normal=normal))
            material = f"preview:{state['id']}-icon-{index}"
            palette["blocks"][material] = {"quads": quads, "occludes": False}
            pieces.append(dict(key=f"icon-{index}", material=material, x=state["anchor"][0], y=state["anchor"][1], z=state["anchor"][2], width=1, height=1, depth=1, fullBright=True))
            label = str(COUNTS[index])
            width = surface.CORE.measured_width(label, font.advances) + 2
            bitmap = Image.new("RGBA", (width, 10))
            surface.CORE.draw_text(bitmap, (1, 0), "&f" + label, font, shadow=True)
            texture = f"preview:count-{index}"
            palette["textures"][texture] = data_url(bitmap)
            quad = plane(texture)
            unit = .1 * settings["countScale"] * settings["scale"]
            quad["vertices"] = [world([offset[0] + (vertex[0] - .5) * width * unit,
                                      offset[1] + settings["countOffsetY"] * settings["scale"] + vertex[1] * 10 * unit,
                                      exported["depth"] + .01]) for vertex in quad["vertices"]]
            quad["normal"] = normal
            material = f"preview:{state['id']}-count-{index}"
            palette["blocks"][material] = {"quads": [quad], "occludes": False}
            pieces.append(dict(key=f"count-{index}", material=material, x=state["anchor"][0], y=state["anchor"][1], z=state["anchor"][2], width=1, height=1, depth=1, fullBright=True))
        count = len(state["offsets"])
        states.append(dict(id=state["id"], title=f"{count} предметов · {state['id']}",
                           role="Камера направлена на контейнер. Замороженная поза из runtime, текстуры и шрифт Minecraft; не нативный клиент.",
                           camera=dict(position=dict(zip("xyz", state["camera"])), target=dict(zip("xyz", state["target"])), fovDegrees=70), pieces=pieces))
    source_files = [root / "src/main/kotlin/ru/arc/chestpreview" / name for name in ("ChestPreviewSettings.kt", "ChestPreviewPlacement.kt", "ChestPreviewIcons.kt")]
    scene = dict(states=states, scope=exported["scope"], source={str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_files}, geometrySha256=hashlib.sha256(args.geometry.read_bytes()).hexdigest(), clientSha1=manifest["minecraft-client-sha1"],
                 approximation="Frozen native billboard poses; generated item front faces omit thin extrusion, lighting is approximate. Actual source coordinates and vanilla textures/font.")
    (output / "scene.json").write_text(json.dumps(scene, ensure_ascii=False, indent=2))
    (output / "palette.json").write_text(json.dumps(palette))
    template = (atelier / "workshop-preview.template.html").read_text()
    template = template.replace("Мебельная мастерская", "Превью контейнера").replace("Состояние мастерской", "Ракурс контейнера")
    template = template.replace("Техническое превью: станки, этапы и готовая мебель; пол условный, без мира и NPC", "54 предмета · 9 колонок · фиксированный центр. Офлайн-превью из исходной геометрии.")
    template = template.replace('.diagnostics-panel.ok{', '.diagnostics-panel.ok{display:none;')
    template = template.replace('data-view="front" aria-pressed="true"', 'data-view="front" aria-pressed="false"').replace('data-view="player" aria-pressed="false"', 'data-view="player" aria-pressed="true"')
    template = replace_once(template, 'const material = new THREE.MeshStandardMaterial({map:texture,vertexColors:true,roughness:0.96,alphaTest:0.1,side:THREE.DoubleSide});',
                            'const material = piece.fullBright ? new THREE.MeshBasicMaterial({map:texture,vertexColors:true,alphaTest:0.1,side:THREE.DoubleSide}) : new THREE.MeshStandardMaterial({map:texture,vertexColors:true,roughness:0.96,alphaTest:0.1,side:THREE.DoubleSide});')
    template = replace_once(template, 'mesh.castShadow = !fluid;', 'mesh.castShadow = !fluid && !piece.fullBright;')
    (output / "template.html").write_text(template)
    subprocess.run(["node", str(atelier / "build-workshop-preview.mjs"), "--scene", str(output / "scene.json"), "--palette", str(output / "palette.json"), "--template", str(output / "template.html"), "--output", str(output / "index.html")], check=True)
    print(output / "index.html")


if __name__ == "__main__":
    main()
