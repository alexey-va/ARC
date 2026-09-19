#!/usr/bin/env python3
"""Author compact forge silhouettes; emit a patch for ARC and its runtime mirror.

Run with --patch PATH... to update only the four solo Edgar cycles. The sword
and unrelated runtime overrides are never serialized. Without arguments print
the silhouettes and runtime entity/duration budgets. Requires PyYAML.
"""
import argparse
from pathlib import Path
import yaml


# Same grid for every material: touching cells share exact edges. Dots are air,
# including the open centre of the horseshoe. M=steel, E=polished edge,
# W=wood, C=copper fittings. Rows run from -Z to +Z on the anvil.
FIGURES = {
    "master-anvil": ("axe", "IRON_AXE", "Лезвие вытянуто, борода подрезана. Теперь топор готов.", [
        "...............",
        "......MMEE.....",
        "....MMMMMEE....",
        "..CMMMMMMEEE...",
        "..CMMMMMMEEE...",
        "..CWMMMMMEEE...",
        "...W..MMMEEE...",
        "...W...MMEE....",
        "...W....EE.....",
        "...W...........",
        "...C...........",
        "...W...........",
        "...W...........",
        "...C...........",
        "...W...........",
        "...W...........",
        "...C...........",
        "..CCC..........",
        "...............",
    ]),
    "master-spear": ("spear", "TRIDENT", "Ровное перо, крепкая втулка. Можно насаживать на древко.", [
        ".......E.......",
        "......EME......",
        ".....EMMME.....",
        ".....EMMME.....",
        "....EMMMMME....",
        "....EMMMMME....",
        ".....EMMME.....",
        "......EME......",
        "......MMM......",
        "......CCC......",
        ".......W.......",
        ".......W.......",
        ".......C.......",
        ".......W.......",
        ".......W.......",
        ".......C.......",
        ".......W.......",
        ".......W.......",
        ".......C.......",
    ]),
    "master-shield": ("shield", "SHIELD", "Обод замкнут, умбон посажен. Этот щит выдержит удар.", [
        "..EEEEEEEEEEE..",
        ".EWWWWWWWWWWWE.",
        ".EWWWWWCWWWWWE.",
        ".EWWWWWCWWWWWE.",
        ".EWWWWWCWWWWWE.",
        ".EWWWWMMMWWWWE.",
        ".EWWWMMMMMWWWE.",
        ".EWCCMMMMMCCWE.",
        ".EWWWMMMMMWWWE.",
        ".EWWWWMMMWWWWE.",
        ".EWWWWWCWWWWWE.",
        "..EWWWWCWWWWE..",
        "..EWWWWCWWWWE..",
        "...EWWWCWWWE...",
        "....EWWCWWE....",
        ".....EWCWE.....",
        "......EWE......",
        ".......E.......",
        "...............",
    ]),
    "master-horseshoe": ("horseshoe", "IRON_NUGGET", "Дуга ровная, зацепы готовы. Такая подкова ляжет как надо.", [
        "...............",
        "....EEEEEEE....",
        "...EMMMMMMME...",
        "..EMMMMMMMMME..",
        ".EMMM.....MMME.",
        ".EMM.......MME.",
        "EMMC.......CMME",
        "EMMM.......MMME",
        "EMMM.......MMME",
        "EMMC.......CMME",
        "EMMM.......MMME",
        "EMMM.......MMME",
        ".EMC.......CME.",
        ".EMM.......MME.",
        ".EEE.......EEE.",
        "...............",
        "...............",
        "...............",
        "...............",
    ]),
}
MATERIALS = {"M": "LIGHT_GRAY_CONCRETE", "E": "IRON_BLOCK", "W": "DARK_OAK_PLANKS", "C": "CUT_COPPER"}
CELL = 0.045
SURFACE = "master-work-surface"


def rectangles(rows):
    """Merge equal neighbouring cells into rectangles, without filling holes."""
    cells = {(x, z): c for z, row in enumerate(rows) for x, c in enumerate(row) if c != "."}
    result = []
    while cells:
        x, z = min(cells, key=lambda p: (p[1], p[0]))
        c = cells[x, z]
        width = 1
        while cells.get((x + width, z)) == c:
            width += 1
        depth = 1
        while all(cells.get((xx, z + depth)) == c for xx in range(x, x + width)):
            depth += 1
        for zz in range(z, z + depth):
            for xx in range(x, x + width):
                del cells[xx, zz]
        result.append((c, x, z, width, depth))
    return result


def vector(*values):
    return ",".join(f"{v:.4f}" for v in values)


def display(key, material, x=0, z=0, width=0.22, depth=0.25, height=0.07, y=0):
    return dict(type="BLOCK_DISPLAY", key=key, surface=SURFACE, material=material,
                origin="BOTTOM_CENTER", offset=vector(x, y, z),
                scale=vector(width, height, depth), **{"interpolation-ticks": 8})


def cycle(cycle_id):
    name, item, speech, rows = FIGURES[cycle_id]
    parts = rectangles(rows)
    steps = {}
    keys = []

    def add(step_id, **step):
        steps[step_id] = step

    def hammer(step_id, pitch=1.0, hot=False):
        add(step_id, type="SWING", **{"actor-id": 349, "repetitions": 20,
            "period-ticks": 16, "feedback-surface": SURFACE,
            "particle": "LAVA" if hot else "ELECTRIC_SPARK", "particle-count": 3,
            "particle-every": 2, "sound": "BLOCK_ANVIL_USE", "sound-every": 2,
            "sound-volume": 0.28, "sound-pitch": pitch})

    add("face", type="LOOK_AT_SURFACE", **{"actor-id": 349, "surface": SURFACE})
    add("billet", type="EQUIP", **{"actor-id": 349, "material": "RAW_IRON"})
    add("place", **display(f"edgar-{name}-blank", "MAGMA_BLOCK"))
    add("tool", type="EQUIP", **{"actor-id": 349, "material": "MACE"})
    hammer("draw-billet", 0.88, hot=True)
    # Build the metal contours first, then fit the grip/board and decorations.
    groups = [c for c in "MEWC" if any(part[0] == c for part in parts)]
    for stage, group in enumerate(groups):
        hammer(f"forge-{group.lower()}", 0.96 + 0.12 * stage)
        if stage == len(groups) - 1:
            add("consume-blank", type="REMOVE_DISPLAY", key=f"edgar-{name}-blank")
        else:
            size = 0.18 / (stage + 1)
            add(f"consume-{stage}", **display(f"edgar-{name}-blank", "MAGMA_BLOCK", width=size, depth=size, height=0.04))
        for i, (material, x, z, width, depth) in enumerate(parts):
            if material != group:
                continue
            key = f"edgar-{name}-part-{i:02d}"
            keys.append(key)
            add(f"part-{i:02d}", **display(key, MATERIALS[material],
                (x + width / 2 - len(rows[0]) / 2) * CELL,
                (z + depth / 2 - len(rows) / 2) * CELL,
                width * CELL, depth * CELL,
                height=0.075 if material == "M" else 0.05))
    hammer("finish", 1.48)
    add("inspect", type="WAIT", ticks=60)
    for i, key in enumerate(keys):
        add(f"collect-{i}", type="REMOVE_DISPLAY", key=key)
    add("result", type="EQUIP", **{"actor-id": 349, "material": item})
    add("remark", type="SPEECH", **{"actor-id": 349, "text": speech})
    add("handoff", type="WAIT", ticks=10)
    return {"actor-ids": ["349"], "initial-delay-seconds": 1,
            "cooldown-min-seconds": 1, "cooldown-max-seconds": 2,
            "step-ids": list(steps), "steps": steps}


def fragment():
    # Flow style keeps each declarative step on one line, like the existing DSL.
    lines = ["      # Generated solo silhouettes: scripts/forge_figures.py (ARC)."]
    for cid in FIGURES:
        data = cycle(cid)
        lines.append(f"      {cid}:")
        for key, value in data.items():
            if key == "steps":
                lines.append("        steps:")
                for sid, step in value.items():
                    encoded = yaml.safe_dump(step, default_flow_style=True, sort_keys=False, allow_unicode=True, width=10000).strip()
                    lines.append(f"          {sid}: {encoded}")
            else:
                encoded = yaml.safe_dump({key: value}, default_flow_style=True, sort_keys=False, width=10000).strip()[1:-1]
                lines.append(f"        {encoded}")
    return "\n".join(lines) + "\n"


def patch(path):
    text = path.read_text()
    start = text.index("      # Generated solo silhouettes:") if "      # Generated solo silhouettes:" in text else text.index("      master-anvil:\n")
    end = text.index("      master-forging-showcase:\n", start)
    old, new = text[start:end], fragment()
    if old == new:
        return ""
    return f"*** Update File: {path.resolve()}\n@@\n" + "".join("-" + line + "\n" for line in old.splitlines()) + "".join("+" + line + "\n" for line in new.splitlines())


def preview(path):
    """Engineering top view from the actual emitted display transforms (not a client screenshot)."""
    from PIL import Image, ImageDraw
    image = Image.new("RGB", (1200, 410), "#24272b")
    draw = ImageDraw.Draw(image)
    colors = dict(zip(MATERIALS.values(), ("#a4a9ad", "#e2e9ed", "#694329", "#c28453")))
    for column, cid in enumerate(FIGURES):
        visible = {}
        for step in cycle(cid)["steps"].values():
            if step["type"] == "BLOCK_DISPLAY":
                visible[step["key"]] = step
            elif step["type"] == "REMOVE_DISPLAY":
                visible.pop(step["key"], None)
            if step["type"] == "WAIT":
                break
        cx, cz, zoom = column * 300 + 150, 210, 330
        draw.rounded_rectangle((cx - 108, 52, cx + 108, 371), radius=18, fill="#3c4045")
        draw.text((column * 300 + 30, 15), FIGURES[cid][0].upper(), fill="white")
        for step in visible.values():
            x, _, z = map(float, step["offset"].split(","))
            w, _, d = map(float, step["scale"].split(","))
            draw.rectangle((cx + (x-w/2)*zoom, cz + (z-d/2)*zoom,
                            cx + (x+w/2)*zoom, cz + (z+d/2)*zoom), fill=colors[step["material"]])
    draw.text((20, 391), "Exact display geometry / top view / schematic materials", fill="#cdd0d2")
    image.save(path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--patch", nargs="+", type=Path)
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    if args.preview:
        preview(args.preview)
    elif args.patch:
        print("*** Begin Patch\n" + "".join(patch(path) for path in args.patch) + "*** End Patch")
    else:
        for cid, (name, _, _, rows) in FIGURES.items():
            steps = cycle(cid)["steps"].values()
            ticks = sum(s["repetitions"] * s["period-ticks"] if s["type"] == "SWING" else s.get("ticks", 0) for s in steps)
            print(f"{name}: {len(rectangles(rows)) + 1} peak displays, {ticks / 20:.1f}s\n" + "\n".join(rows))
