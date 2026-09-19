"""Mounted field patrols on surveyed full-height lanes; never on the yard road."""

PATROLS = (
    ("iney-patrol", 412, 413, "iney-field", (48, 51, -102), 2,
     "Иней, держим ровный шаг. До края поля и обратно."),
    ("bay-patrol", 414, 415, "bay-field", (52, 56, -106), 5,
     "Гнедой, разминаемся в поле. На дороге людям мешать не будем."),
)


def apply_rider_routes(scene):
    for cycle_id, rider, vehicle, route_id, (left, right, z), delay, line in PATROLS:
        home = f"{left + .5},69,{z + .5},-90,0"
        for actor in (rider, vehicle):
            scene["actors"][actor]["home"] = home
            scene["anchors"][f"home-{actor}"] = home
        scene["route-profiles"][route_id] = {
            "floor-y": 69, "min-block": f"{left},{z}", "max-block": f"{right},{z}",
            "forbidden-areas": [], "preferred-areas": [], "max-visited": 128,
            "snap-radius": 1, "poll-ticks": 2, "stall-polls": 30,
            "speed-modifier": .42, "distance-margin": .22, "path-distance-margin": .20,
            "maximum-step-height": .125,
        }
        far_anchor = f"{route_id}-turn"
        scene["anchors"][far_anchor] = f"{right + .5},69,{z + .5},90,0"
        steps = {"mount": {"type": "MOUNT", "rider-id": rider, "vehicle-id": vehicle}}
        for lap in range(3):
            for leg, anchor in (("out", far_anchor), ("back", f"home-{vehicle}")):
                steps[f"lap-{lap}-{leg}"] = {
                    "type": "MOVE", "actor-id": vehicle, "anchor": anchor,
                    "route-profile": route_id, "timeout-ticks": 180,
                }
            if lap == 0:
                steps["line"] = {"type": "SPEECH", "actor-id": rider, "text": line}
        scene["cycles"][cycle_id] = {
            "actor-ids": [str(rider), str(vehicle)], "initial-delay-seconds": delay,
            "cooldown-min-seconds": 1, "cooldown-max-seconds": 3,
            "step-ids": list(steps), "steps": steps,
        }
    scene["anchor-ids"] = list(scene["anchors"])
    scene["route-profile-ids"] = list(scene["route-profiles"])
