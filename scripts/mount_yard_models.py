"""Small, staged BlockDisplay models for the living stable.

Coordinates are in blocks around the bottom-centre of a workstation or actor.
Model parts have stable keys: shaping and carrying reuse the same entities.
The scene authoring helper owns world anchors, NPC IDs and timings.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Part:
    key: str
    material: str
    offset: tuple[float, float, float]
    scale: tuple[float, float, float]
    stage: int = 0


# A riding saddle, with a recessed seat between a raised pommel and cantle,
# separate skirts, girth straps and open stirrup loops. Metal is deliberately
# limited to fittings so the silhouette reads as leather rather than a chair.
SADDLE = (
    Part("seat", "BROWN_TERRACOTTA", (0, .10, 0), (.52, .12, .64)),
    Part("front", "DARK_OAK_PLANKS", (0, .20, -.30), (.56, .18, .12), 1),
    Part("back", "DARK_OAK_PLANKS", (0, .20, .30), (.58, .22, .14), 1),
    Part("skirt-left", "BROWN_TERRACOTTA", (-.31, .02, .03), (.16, .09, .76), 1),
    Part("skirt-right", "BROWN_TERRACOTTA", (.31, .02, .03), (.16, .09, .76), 1),
    Part("stitch-left", "YELLOW_TERRACOTTA", (-.235, .221, 0), (.018, .01, .45), 2),
    Part("stitch-right", "YELLOW_TERRACOTTA", (.235, .221, 0), (.018, .01, .45), 2),
    Part("horn", "DARK_OAK_PLANKS", (0, .38, -.30), (.08, .14, .08), 2),
    Part("horn-cap", "BROWN_TERRACOTTA", (0, .50, -.30), (.14, .035, .12), 2),
    Part("strap-left", "DARK_OAK_PLANKS", (-.36, .10, 0), (.055, .30, .08), 3),
    Part("strap-right", "DARK_OAK_PLANKS", (.36, .10, 0), (.055, .30, .08), 3),
    Part("buckle-left", "CUT_COPPER", (-.39, .18, 0), (.035, .07, .12), 3),
    Part("buckle-right", "CUT_COPPER", (.39, .18, 0), (.035, .07, .12), 3),
    Part("stirrup-l-front", "IRON_BLOCK", (-.44, .015, -.055), (.035, .16, .035), 3),
    Part("stirrup-l-back", "IRON_BLOCK", (-.44, .015, .055), (.035, .16, .035), 3),
    Part("stirrup-l-base", "IRON_BLOCK", (-.44, .015, 0), (.035, .035, .145), 3),
    Part("stirrup-r-front", "IRON_BLOCK", (.44, .015, -.055), (.035, .16, .035), 3),
    Part("stirrup-r-back", "IRON_BLOCK", (.44, .015, .055), (.035, .16, .035), 3),
    Part("stirrup-r-base", "IRON_BLOCK", (.44, .015, 0), (.035, .035, .145), 3),
)

# Left/right packs leave the animal's head and legs unobstructed. A rolled
# blanket and two straps finish the load instead of a single floating cube.
CARGO = (
    Part("pad", "RED_WOOL", (0, 1.14, .12), (.60, .08, .82)),
    Part("left-pack", "SPRUCE_PLANKS", (-.47, .67, .12), (.34, .42, .48), 1),
    Part("right-pack", "SPRUCE_PLANKS", (.47, .67, .12), (.34, .42, .48), 1),
    Part("left-lid", "DARK_OAK_PLANKS", (-.47, 1.075, .12), (.37, .055, .51), 1),
    Part("right-lid", "DARK_OAK_PLANKS", (.47, 1.075, .12), (.37, .055, .51), 1),
    Part("blanket", "CYAN_WOOL", (0, 1.22, .27), (.69, .21, .24), 2),
    Part("strap-front", "BROWN_TERRACOTTA", (0, 1.21, -.15), (.74, .035, .06), 2),
    Part("strap-back", "BROWN_TERRACOTTA", (0, 1.435, .32), (.74, .025, .045), 2),
    Part("left-lock", "CUT_COPPER", (-.65, .93, .12), (.035, .10, .075), 3),
    Part("right-lock", "CUT_COPPER", (.65, .93, .12), (.035, .10, .075), 3),
)


def vector(values):
    return ",".join(f"{value:.4f}" for value in values)


def model_steps(parts, prefix, *, surface=None, anchor=None, follow_actor=None,
                stage=None, shift=(0, 0, 0), size=1.0):
    """Emit one stable-key update per selected part; exactly one parent required."""
    assert sum(value is not None for value in (surface, anchor, follow_actor)) == 1
    steps = {}
    for part in parts:
        if stage is not None and part.stage != stage:
            continue
        step = {"type": "BLOCK_DISPLAY", "key": f"{prefix}-{part.key}",
                "material": part.material, "origin": "BOTTOM_CENTER",
                "offset": vector(tuple(o * size + s for o, s in zip(part.offset, shift))),
                "scale": vector(tuple(v * size for v in part.scale)),
                "interpolation-ticks": 5}
        if surface is not None:
            step["surface"] = surface
        elif anchor is not None:
            step["anchor"] = anchor
        else:
            step["follow-actor-id"] = follow_actor
            step["follow-offset"] = step.pop("offset")
        steps[part.key] = step
    return steps


def remove_steps(parts, prefix):
    return {part.key: {"type": "REMOVE_DISPLAY", "key": f"{prefix}-{part.key}"}
            for part in parts}
