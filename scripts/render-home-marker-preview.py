#!/usr/bin/env python3
"""Adapt exported HomeMarker geometry and the existing loot model to the atelier preview."""
import argparse
import base64
import importlib.util
import io
import json
import subprocess
import sys
from pathlib import Path

import yaml
from PIL import Image, ImageChops


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def image_url(image):
    stream = io.BytesIO()
    image.save(stream, format="PNG")
    return "data:image/png;base64," + base64.b64encode(stream.getvalue()).decode()


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"Atelier template seam changed: {old[:70]}")
    return text.replace(old, new, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geometry", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ops-root", type=Path, default=Path(__file__).resolve().parents[2] / "ruscrafting-ops")
    parser.add_argument("--client-jar", type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[1]
    atelier = args.ops_root / "scripts/location-atelier"
    surface = load_module("home_marker_surface", args.ops_root / ".agents/skills/design-ruscrafting-surfaces/scripts/render_plugin_surface_dump.py")
    vanilla = load_module("home_marker_vanilla", atelier / "vanilla_assets.py")
    exported = json.loads(args.geometry.read_text())
    manifest = yaml.safe_load((root / "visual-preview.yml").read_text())
    client = surface.resolve_client_jar(manifest, args.client_jar, out / ".surface-cache")
    stack = surface.create_stack(client, 1, {"gallery-accent": "#68D8FF"})
    archive = vanilla.SafeArchive(str(client))
    baker = vanilla.Baker(archive)
    palette = {"version": manifest["minecraft-version"], "blocks": {}, "textures": {}}
    for block in ("minecraft:stone_bricks", "minecraft:gray_concrete"):
        palette["blocks"][block] = baker.bake_block(block)
    palette["textures"].update(baker.textures)
    archive.close()

    pack = args.ops_root / "classic/plugins/ItemsAdder/contents/lemon_vfxdrop/resourcepack/assets"
    model_path = pack / "lemon_vfxdrop/models/lemon_loot_effect.json"
    texture_path = pack / "minecraft/textures/lemon_vfxdrop/lemon_loot_effect.png"
    model = json.loads(model_path.read_text())
    animation = json.loads(texture_path.with_suffix(".png.mcmeta").read_text())["animation"]
    atlas = surface.CORE.open_resource_pack_bitmap(texture_path.read_bytes()).convert("RGBA")
    count = atlas.height // atlas.width
    frames = [atlas.crop((0, index * atlas.width, atlas.width, (index + 1) * atlas.width)) for index in range(count)]
    color = exported["color"]
    tint = Image.new("RGBA", frames[0].size, ((color >> 16) & 255, (color >> 8) & 255, color & 255, 255))
    frame_ticks = animation.get("frametime", 1)
    names = []
    for index, frame in enumerate(frames):
        for tick in range(frame_ticks):
            sampled = Image.blend(frame, frames[(index + 1) % count], tick / frame_ticks) if animation.get("interpolate") else frame
            name = f"preview:loot-{index}-{tick}"
            palette["textures"][name] = image_url(ImageChops.multiply(sampled, tint))
            names.append(name)

    quads = []
    for element in model["elements"]:
        start = [value / 16 for value in element["from"]]
        end = [value / 16 for value in element["to"]]
        for face, face_spec in element["faces"].items():
            uv = face_spec["uv"]
            if uv[0] == uv[2] or uv[1] == uv[3]:
                continue
            # A zero-thickness model has two coincident faces; the atelier already draws both sides.
            if face in ("down", "west"):
                continue
            quads.append({"texture": names[0], "vertices": vanilla._face_vertices(face, start, end),
                          "normal": {"east": [1, 0, 0], "up": [0, 1, 0]}[face],
                          "uv": [[u / 16, v / 16] for u, v in vanilla._uv_corners(uv, 0)]})
    palette["blocks"]["preview:loot"] = {"quads": quads, "occludes": False}

    label_lines = exported["label"].splitlines()
    painted = [f"&#{color:06X}" + label_lines[0].replace(" · ", " · &f", 1), "&#E8DFD2" + label_lines[1]]
    widths = [surface.CORE.measured_width(line, stack.advances) for line in painted]
    label_width, label_height = max(widths) + 4, len(painted) * 10 + 2
    label = Image.new("RGBA", (label_width, label_width))
    label.paste((15, 23, 30, 140), (0, 0, label_width, label_height))
    for index, line in enumerate(painted):
        surface.CORE.draw_text(label, ((label_width - widths[index]) // 2, index * 10 + 1), line, stack, shadow=True)
    palette["textures"]["preview:label"] = image_url(label)
    palette["blocks"]["preview:label"] = {"occludes": False, "quads": [{
        "texture": "preview:label", "normal": [0, 0, -1],
        "vertices": [[0, 0, .5], [0, 1, .5], [1, 1, .5], [1, 0, .5]],
        "uv": [[0, label_height / label_width], [0, 0], [1, 0], [1, label_height / label_width]],
    }]}
    label_scale = .025 * exported["labelScale"]
    pieces = [dict(key="floor", material="minecraft:stone_bricks", x=0, y=-.1, z=0, width=6, height=.2, depth=5),
              dict(key="height-reference-body", material="minecraft:gray_concrete", x=-1.7, y=.7, z=0, width=.6, height=1.4, depth=.3),
              dict(key="height-reference-head", material="minecraft:gray_concrete", x=-1.7, y=1.6, z=0, width=.4, height=.4, depth=.4),
              dict(key="marker", material="preview:loot", x=0, y=exported["itemY"], z=0, width=exported["scale"], height=exported["scale"], depth=exported["scale"], rotationY=model["display"]["fixed"]["rotation"][1], billboard=True, fullBright=True, animatedLoot=True),
              dict(key="label", material="preview:label", x=0, y=exported["labelY"] + label_height * label_scale / 2, z=0, width=label_width * label_scale, height=label_height * label_scale, depth=.01, rotationY=180, billboard=True, fullBright=True)]
    scene = {"camera": {"position": {"x": 0, "y": 1.27, "z": -2.6}, "target": {"x": 0, "y": exported["floorY"] + exported["height"] / 2, "z": 0}, "fovDegrees": 70},
             "states": [{"id": "home", "title": "Дом · приватный маркер", "role": "1.6 × 2.4 блока; рядом масштаб игрока 1.8 м. Камера игрока: приседание, 2.6 м до знака.", "pieces": pieces}],
             "textureAnimation": names, "evidence": exported,
             "assets": [str(model_path), str(texture_path)], "scope": "Source geometry, exact model/texture and Minecraft font. Approximate lighting/alpha; no native-client or network acceptance."}
    (out / "scene.json").write_text(json.dumps(scene, ensure_ascii=False, indent=2))
    (out / "palette.json").write_text(json.dumps(palette))
    template = (atelier / "workshop-preview.template.html").read_text()
    template = template.replace("Мебельная мастерская · техническое превью", "Дом и варп · техническое превью")
    template = template.replace("Мебельная мастерская — техническое превью", "Дом и варп — техническое превью")
    template = template.replace("Техническое превью: станки, этапы и готовая мебель; пол условный, без мира и NPC", "Геометрия из кода, исходная модель дропа и шрифт Minecraft. Это офлайн-превью; освещение и прозрачность приблизительны.")
    template = replace_once(template, 'pieceGroup.rotation.copy(rotation);', 'pieceGroup.rotation.copy(rotation); pieceGroup.userData = {billboard:piece.billboard, baseYaw:rotation.y, animatedLoot:piece.animatedLoot};')
    template = replace_once(template, 'const material = new THREE.MeshStandardMaterial({map:texture,vertexColors:true,roughness:0.96,alphaTest:0.1,side:THREE.DoubleSide});', 'const material = piece.fullBright ? new THREE.MeshBasicMaterial({map:texture,vertexColors:true,alphaTest:0.1,side:THREE.DoubleSide}) : new THREE.MeshStandardMaterial({map:texture,vertexColors:true,roughness:0.96,alphaTest:0.1,side:THREE.DoubleSide});')
    template = replace_once(template, 'mesh.castShadow = !fluid;', 'mesh.castShadow = !fluid && !piece.fullBright;')
    template = replace_once(template, 'controls.update();\n        renderer.render(scene,camera);', '''controls.update();
        const direction = camera.getWorldDirection(new THREE.Vector3());
        const yaw = Math.atan2(direction.x, direction.z);
        const texture = vanillaTextures.get(PREVIEW_SCENE.textureAnimation[Math.floor(performance.now()/50)%PREVIEW_SCENE.textureAnimation.length]);
        for (const child of work.children) {
          if (child.userData.billboard) child.rotation.y = child.userData.baseYaw + yaw;
          if (child.userData.animatedLoot) child.traverse(mesh => { if(mesh.material && mesh.material.map !== texture){mesh.material.map=texture;mesh.material.needsUpdate=true;} });
        }
        renderer.render(scene,camera);''')
    (out / "template.html").write_text(template)
    subprocess.run(["node", str(atelier / "build-workshop-preview.mjs"), "--scene", str(out / "scene.json"), "--palette", str(out / "palette.json"), "--template", str(out / "template.html"), "--output", str(out / "index.html")], check=True)
    print(out / "index.html")


if __name__ == "__main__":
    main()
