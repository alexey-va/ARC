#!/usr/bin/env python3
"""Author the living stable without reserializing unrelated scene overrides.

Print a unified patch with --patch FILE... and apply it with apply_patch.
Requires PyYAML. Native NPC identities and support blocks are documented in ops.
"""
import argparse
import copy
import difflib
import json
from pathlib import Path

import yaml

from mount_yard_models import CARGO, SADDLE, Part, model_steps, remove_steps

EMMA, TOM, WIND = 369, 370, 371
HELPER, PACKER, DONKEY, CAT, DOG, GOAT, LLAMA = range(440, 447)
HOMES = {
    EMMA: "54.6336,70,-119.518,180,0",
    HELPER: "57.5,70,-133.5,180,0", PACKER: "66.5,70,-141.5,-90,0",
    DONKEY: "69.5,70,-141.5,180,0", CAT: "52.5,70,-121.5,90,0",
    DOG: "57.5,70,-125.5,180,0", GOAT: "45.5,70,-141.5,-90,0",
    LLAMA: "73.5,70,-141.5,90,0",
}
ANCHORS = {
    "saddler-stand": "54.6336,70,-119.518,180,0",
    "saddle-rack-stand": "52.5,70,-121.5,180,0",
    "helper-hay": "57.5,70,-123.5,90,0",
    "helper-barrels": "56.5,70,-134.5,90,0",
    "helper-feed": "61.5,70,-121.5,-90,0",
    "helper-water": "58.5,70,-121.5,90,0",
    "water-trough": "57.5,70.9,-121.5",
    "feed-floor": "63.5,70.05,-121.5",
    "hay-source": "55.5,71,-125.5",
    "cat-nap": "51.5,70,-122.5,90,0",
    "cat-watch": "53.5,70,-120.5,-90,0",
    "dog-patrol": "60.5,70,-131.5,180,0",
    "dog-watch": "61.5,70,-139.5,180,0",
    "goat-hay": "47.5,70,-142.5,0,0",
    "goat-turn": "45.5,70,-137.5,180,0",
    "llama-turn": "71.5,70,-143.5,90,0",
    "llama-watch": "73.5,70,-138.5,180,0",
    "donkey-graze": "68.5,70,-139.5,180,0",
    "packing-crates": "66.5,70,-139.5,180,0",
    "packing-stand": "68.0,70,-141.5,-90,0",
    "packing-target": "69.5,69.7,-141.5",
    "caravan-helper": "67.5,70,-143.5,0,0",
    "caravan-person-mid": "65.5,70,-134.5,0,0",
    "caravan-animal-mid": "68.5,70,-134.5,0,0",
    "caravan-person-front": "61.5,70,-123.5,0,0",
    "caravan-animal-front": "64.5,70,-123.5,0,0",
}
HAY = (Part("bale", "HAY_BLOCK", (0, .85, .55), (.54, .45, .42)),
       Part("binding", "BROWN_TERRACOTTA", (0, .84, .55), (.08, .48, .44)))
BUCKET = (
    Part("base", "IRON_BLOCK", (.38, .60, .20), (.26, .04, .26)),
    Part("front", "IRON_BLOCK", (.38, .64, .08), (.26, .23, .03)),
    Part("back", "IRON_BLOCK", (.38, .64, .32), (.26, .23, .03)),
    Part("left", "IRON_BLOCK", (.26, .64, .20), (.03, .23, .26)),
    Part("right", "IRON_BLOCK", (.50, .64, .20), (.03, .23, .26)),
    Part("water", "LIGHT_BLUE_STAINED_GLASS", (.38, .82, .20), (.20, .025, .20)),
    Part("handle", "IRON_BLOCK", (.38, 1.02, .20), (.025, .025, .28)),
)


def step(kind, actor=None, **fields):
    result = {"type": kind}
    if actor is not None:
        result["actor-id"] = actor
    result.update({key.replace("_", "-"): value for key, value in fields.items()})
    return result


def move(actor, anchor):
    return step("MOVE", actor, anchor=anchor, route_profile="yard", timeout_ticks=500)


def pause(ticks):
    return step("WAIT", ticks=ticks)


def say(actor, text):
    return step("SPEECH", actor, text=text)


def sound(actor, name, pitch=1.0):
    return step("SOUND", actor, sound=name, volume=.32, pitch=pitch)


def pose(actor, state):
    return step("POSE", actor, state=state)


def swing(actor, repetitions=8, period=12, **fields):
    return step("SWING", actor, repetitions=repetitions, period_ticks=period, **fields)


def entries(prefix, values):
    return {f"{prefix}-{key}": value for key, value in values.items()}


def cycle(actors, steps, delay, cooldown=(20, 35), **fields):
    return {"actor-ids": [str(a) for a in actors], "initial-delay-seconds": delay,
            "cooldown-min-seconds": cooldown[0], "cooldown-max-seconds": cooldown[1],
            **fields, "step-ids": list(steps), "steps": steps}


def saddler_cycle():
    steps = {"bench": move(EMMA, "saddler-stand"),
             "tool": step("EQUIP", EMMA, material="SHEARS"),
             "face": step("LOOK_AT_SURFACE", EMMA, surface="saddler-bench"),
             "start": say(EMMA, "Сначала кожа и основа. Хорошее седло не должно натирать.")}
    for stage, verb in enumerate(("cut", "shape", "stitch", "fit")):
        steps.update(entries(verb, model_steps(SADDLE, "saddle", surface="saddler-bench", stage=stage)))
        steps[f"{verb}-work"] = swing(EMMA, 12, 15, feedback_surface="saddler-bench",
                                     sound="BLOCK_WOOL_PLACE", sound_every=3, sound_volume=.25,
                                     sound_pitch=1.1 + stage * .15,
                                     particle="WAX_OFF", particle_count=2, particle_every=4)
        if stage == 2:
            steps["stitch-line"] = say(EMMA, "Стежок к стежку. Теперь ремни, пряжки и стремена.")
    steps.update({"ready": say(EMMA, "Мягкая посадка, крепкие ремни. Вот теперь можно в дорогу."),
                  "show": pause(100), "empty-hand": step("EQUIP", EMMA, material="AIR")})
    steps.update(entries("carry", model_steps(SADDLE, "saddle", follow_actor=EMMA, shift=(0,.8,.6), size=.8)))
    steps["rack"] = move(EMMA, "saddle-rack-stand")
    steps["rack-look"] = step("LOOK_AT_SURFACE", EMMA, surface="saddle-rack")
    steps.update(entries("hang", model_steps(SADDLE, "saddle", surface="saddle-rack")))
    steps["put"] = sound(EMMA, "ITEM_ARMOR_EQUIP_LEATHER")
    steps["back"] = move(EMMA, "saddler-stand")
    steps["rack-display"] = pause(260)
    steps.update(entries("done", remove_steps(SADDLE, "saddle")))
    return cycle([EMMA], steps, 3, (4, 9))


def caravan_cycle():
    steps = {"crate": move(PACKER, "packing-crates"),
             "call": say(PACKER, "Том, проверь Репея. Я пока уложу поклажу."),
             "donkey-stand": move(DONKEY, "home-442"),
             "animal-still": pose(DONKEY, "STAND"),
             "tom": move(TOM, "caravan-helper"),
             "packer": move(PACKER, "packing-stand"),
             "check": step("LOOK_AT_ACTOR", TOM, target_actor_id=DONKEY),
             "brush": step("EQUIP", TOM, material="BRUSH"),
             "groom": swing(TOM, 10, 12),
             "approve": say(TOM, "Копыта чистые. Только груз поровну, Тихон."),
             "face": step("LOOK_AT_ANCHOR", PACKER, anchor="packing-target")}
    for stage in range(4):
        steps.update(entries(f"load-{stage}", model_steps(CARGO, "cargo", follow_actor=DONKEY, stage=stage)))
        steps[f"work-{stage}"] = swing(PACKER, 8, 12, feedback_anchor="packing-target",
                                      sound="BLOCK_WOOL_PLACE", sound_every=3, sound_volume=.25)
    steps.update({"ready": say(PACKER, "Сумки закреплены, одеяло сверху. Репей, шагом!"),
                  "snort": sound(DONKEY, "ENTITY_DONKEY_AMBIENT", .9),
                  "show-load": pause(80)})
    for name, anchors in (("out-middle", ["caravan-person-mid", "caravan-animal-mid"]),
                          ("out-front", ["caravan-person-front", "caravan-animal-front"])):
        steps[name] = step("MOVE_GROUP", actor_ids=[str(PACKER), str(DONKEY)], anchors=anchors,
                           route_profile="yard", timeout_ticks=700)
    steps["inspect-load"] = step("LOOK_AT_ACTOR", PACKER, target_actor_id=DONKEY)
    steps["line"] = say(PACKER, "Ничего не гремит и не сползает. Ещё круг — и обоз готов.")
    steps["rest"] = pause(220)
    steps["return-middle"] = step("MOVE_GROUP", actor_ids=[str(PACKER), str(DONKEY)],
        anchors=["caravan-person-mid", "caravan-animal-mid"], route_profile="yard", timeout_ticks=700)
    steps["return"] = step("MOVE_GROUP", actor_ids=[str(PACKER), str(DONKEY)],
        anchors=["packing-stand", "home-442"], route_profile="yard", timeout_ticks=700)
    for stage in reversed(range(4)):
        steps[f"unload-work-{stage}"] = swing(PACKER, 3, 13)
        parts = [p for p in CARGO if p.stage == stage]
        steps.update(entries(f"unload-{stage}", remove_steps(parts, "cargo")))
    steps["finish"] = say(PACKER, "Отдыхай, Репей. До следующего выхода всё уложено.")
    return cycle([PACKER, DONKEY, TOM], steps, 17, (35, 55))


def helper_cycles():
    hay = {"source": move(HELPER, "helper-hay"),
           "look": step("LOOK_AT_ANCHOR", HELPER, anchor="hay-source"),
           "take": swing(HELPER, 4, 12), "hands": step("EQUIP", HELPER, material="AIR")}
    hay.update(entries("carry", model_steps(HAY, "hay", follow_actor=HELPER)))
    hay.update({"deliver": move(HELPER, "helper-feed"),
                "call": say(HELPER, "Ветер, свежее сено. Только не всё разом!"),
                "animal": move(WIND, "wind-approach"),
                "put-look": step("LOOK_AT_ANCHOR", HELPER, anchor="feed-floor"),
                "put": swing(HELPER, 3, 12)})
    # Move the carried bale to the actual floor beside the horse, then let it
    # shrink as the animal grazes. The same two entities are reused throughout.
    hay.update(entries("serve", model_steps(HAY, "hay", anchor="feed-floor", shift=(0,-.82,-.55))))
    hay.update({"graze": pose(WIND, "HORSE_GRAZE"), "eat": sound(WIND, "ENTITY_HORSE_EAT"),
                "meal": pause(150)})
    hay.update(entries("eaten", model_steps(HAY, "hay", anchor="feed-floor", size=.3, shift=(0,-.245,-.165))))
    hay["last-bites"] = pause(100)
    hay.update(entries("clean", remove_steps(HAY, "hay")))
    hay["stand"] = pose(WIND, "STAND")
    water = {"barrels": move(HELPER, "helper-barrels"), "fill": sound(HELPER, "ITEM_BUCKET_FILL"),
             "hands": step("EQUIP", HELPER, material="AIR")}
    water.update(entries("bucket", model_steps(BUCKET, "bucket", follow_actor=HELPER)))
    water.update({"deliver": move(HELPER, "helper-water"),
                  "face-trough": step("LOOK_AT_SURFACE", HELPER, surface="water-trough"),
                  "pour": swing(HELPER, 5, 16),
                  "splash": sound(HELPER, "ITEM_BUCKET_EMPTY"),
                  "drops": step("PARTICLE", anchor="water-trough", particle="SPLASH", count=12),
                  "line": say(HELPER, "Вода свежая. Теперь можно и мне передохнуть."),
                  "wait": pause(60)})
    water.update(entries("empty", remove_steps([BUCKET[5]], "bucket")))
    water["back"] = move(HELPER, "helper-barrels")
    water.update(entries("put", remove_steps([p for p in BUCKET if p.key != "water"], "bucket")))
    return {"helper-hay": cycle([HELPER, WIND], hay, 10, (25, 45)),
            "helper-water": cycle([HELPER], water, 42, (45, 65))}


def animal_cycles():
    result = {}
    for name, actor, first, second, noise, delay in (
        ("cat-round", CAT, "cat-watch", "cat-nap", "ENTITY_CAT_PURR", 5),
        ("dog-round", DOG, "dog-patrol", "dog-watch", "ENTITY_WOLF_AMBIENT", 9),
        ("goat-round", GOAT, "goat-hay", "goat-turn", "ENTITY_GOAT_AMBIENT", 13),
        ("llama-round", LLAMA, "llama-turn", "llama-watch", "ENTITY_LLAMA_AMBIENT", 21),
        ("donkey-rest", DONKEY, "donkey-graze", "home-442", "ENTITY_DONKEY_AMBIENT", 7),
    ):
        steps = {"stand": pose(actor, "STAND"), "out": move(actor, first),
                 "look": step("LOOK_AT_ANCHOR", actor, anchor=second),
                 "voice": sound(actor, noise), "observe": pause(100), "turn": move(actor, second)}
        if actor == CAT:
            steps.update({"sit": pose(actor, "SIT"), "watch": pause(120),
                          "lie": pose(actor, "CAT_LIE"), "nap": pause(440)})
        elif actor == DOG:
            steps.update({"home": move(actor, "home-444"), "sit": pose(actor, "SIT"),
                          "guard": pause(340)})
        elif actor == DONKEY:
            steps.update({"graze": pose(actor, "HORSE_GRAZE"), "eat": pause(220)})
        else:
            steps["rest"] = pause(160)
        steps["up"] = pose(actor, "STAND")
        result[name] = cycle([actor], steps, delay, (12, 25))
    result["cat-and-dog"] = cycle([CAT, DOG], {
        "cat": move(CAT, "cat-watch"), "dog": move(DOG, "home-444"),
        "cat-looks": step("LOOK_AT_ACTOR", CAT, target_actor_id=DOG),
        "dog-looks": step("LOOK_AT_ACTOR", DOG, target_actor_id=CAT),
        "cat-sits": pose(CAT, "SIT"), "meow": sound(CAT, "ENTITY_CAT_AMBIENT", 1.15),
        "pause": pause(70), "dog-sits": pose(DOG, "SIT"),
        "reply": sound(DOG, "ENTITY_WOLF_WHINE", 1.1), "truce": pause(180),
    }, 70, (75, 110))
    return result


def living_scene(baseline):
    scene = copy.deepcopy(baseline)
    scene["max-concurrent-cycles"] = 7
    scene["retry-seconds"] = 3
    for actor, home in HOMES.items():
        if str(actor) not in scene["actor-ids"]:
            scene["actor-ids"].append(str(actor))
        scene["actors"][actor] = {"home": home, "denied-denizen-flags": []}
    scene["anchors"].update(ANCHORS)
    for actor, home in HOMES.items():
        scene["anchors"][f"home-{actor}"] = home
    scene["anchor-ids"] = list(scene["anchors"])
    scene["prop-surface-ids"] = ["saddler-bench", "saddle-rack", "water-trough"]
    scene["prop-surfaces"] = {
        "saddler-bench": {"near": "54.5,70,-121.5", "material-ids": ["SPRUCE_PLANKS"],
                          "search-radius": 0, "top-offset": 1.02, "look-target-offset-y": -1.15},
        "saddle-rack": {"near": "52.5,70,-122.5", "material-ids": ["SPRUCE_FENCE"],
                        "search-radius": 0, "top-offset": 1.51, "look-target-offset-y": -1.15},
        "water-trough": {"near": "57.5,70,-121.5", "material-ids": ["WATER_CAULDRON"],
                         "search-radius": 0, "top-offset": .9, "look-target-offset-y": -1.15},
    }
    for key in ("wind-paddock", "buran-paddock", "ryzhik-paddock"):
        current = scene["cycles"][key]
        actor = int(current["actor-ids"][0])
        steps = current["steps"]
        steps["pause"] = pause(170)
        reordered = {}
        for step_id, value in steps.items():
            if step_id == "pause":
                reordered["graze-pose"] = pose(actor, "HORSE_GRAZE")
            reordered[step_id] = value
            if step_id == "pause":
                reordered["stand-pose"] = pose(actor, "STAND")
        current["steps"], current["step-ids"] = reordered, list(reordered)
        current["cooldown-min-seconds"], current["cooldown-max-seconds"] = 16, 30
    scene["cycles"].update({"saddler-work": saddler_cycle(), "caravan-preparation": caravan_cycle(),
                             **helper_cycles(), **animal_cycles()})
    scene["cycle-ids"] = list(scene["cycles"])
    return scene


def replace_section(text):
    start = text.index("  mount-yard:\n")
    following = text.find("\n  ", start + len("  mount-yard:\n"))
    # Locate another top-level scene, not a nested field.
    while following >= 0 and text[following + 3:following + 4].isspace():
        following = text.find("\n  ", following + 1)
    end = len(text) if following < 0 else following + 1
    baseline = yaml.safe_load(text[start:end])["mount-yard"]
    scene = living_scene(baseline)
    return text[:start] + render_scene(scene) + text[end:]


def render_scene(scene):
    # Flow steps keep authored choreography readable; JSON is valid YAML.
    def scalar(v):
        return json.dumps(v, ensure_ascii=False)
    lines = ["  mount-yard:"]
    for key, value in scene.items():
        if key == "cycles":
            lines.append("    cycles:")
            for name, spec in value.items():
                lines.append(f"      {name}:")
                for field, content in spec.items():
                    if field == "steps":
                        lines.append("        steps:")
                        lines.extend(f"          {sid}: {scalar(s)}" for sid, s in content.items())
                    else:
                        lines.append(f"        {field}: {scalar(content)}")
        elif isinstance(value, dict):
            lines.append(f"    {key}:")
            lines.extend(f"      {k}: {scalar(v)}" for k, v in value.items())
        else:
            lines.append(f"    {key}: {scalar(value)}")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--patch", nargs="+", type=Path, required=True)
    args = parser.parse_args()
    print("*** Begin Patch")
    for path in args.patch:
        old = path.read_text()
        new = replace_section(old)
        if new == old:
            continue
        print(f"*** Update File: {path}")
        diff = list(difflib.unified_diff(old.splitlines(), new.splitlines(), n=3))
        for line in diff[2:]:
            print("@@" if line.startswith("@@") else line)
    print("*** End Patch")


if __name__ == "__main__":
    main()
