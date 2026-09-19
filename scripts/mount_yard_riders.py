"""Long mounted circuits inside the crop fields, with road cells excluded."""

PATROLS = (
    ("iney-patrol", 412, 413, "iney-field", (
        (43.5, 68, -109.5), (47.5, 68, -113.5), (47.5, 68, -117.5),
        (47.5, 69, -121.5), (43.5, 69, -124.5), (39.5, 68, -121.5),
        (39.5, 68, -117.5), (43.5, 68, -113.5),
    ), 2, "Иней, проверим морковные грядки и дальний край поля."),
    ("bay-patrol", 414, 415, "bay-field", (
        (73.5, 68, -104.5), (81.5, 68, -105.5), (86.5, 67, -109.5),
        (87.5, 67, -118.5), (85.5, 67, -126.5), (81.5, 68, -125.5),
        (79.5, 68, -125.5), (77.5, 69, -117.5), (76.5, 69, -109.5),
    ), 5, "Гнедой, обходим пшеницу большим кругом. Дорогу оставим людям."),
)


def point_spec(point):
    return ",".join(str(value) for value in point) + ",-90,0"


def apply_rider_routes(scene):
    for cycle_id, rider, vehicle, route_id, points, delay, line in PATROLS:
        home = point_spec(points[0])
        for actor in (rider, vehicle):
            scene["actors"][actor]["home"] = home
            scene["anchors"][f"home-{actor}"] = home
        xs = [int(point[0]) for point in points]
        zs = [int(point[2] // 1) for point in points]
        scene["route-profiles"][route_id] = {
            "floor-y": 69,
            "min-block": f"{min(xs) - 2},{min(zs) - 2}",
            "max-block": f"{max(xs) + 2},{max(zs) + 2}",
            "forbidden-areas": [], "preferred-areas": [], "max-visited": 1024,
            "snap-radius": 1, "poll-ticks": 2, "stall-polls": 50,
            "speed-modifier": .42, "distance-margin": .22, "path-distance-margin": .20,
            "maximum-step-height": 1.0, "maximum-surface-drop": .0625,
            "surface-search-range": 2, "allowed-support-materials": ["FARMLAND"],
        }
        scene["anchors"].pop(f"{route_id}-turn", None)
        anchors = []
        for index, point in enumerate(points):
            anchor = f"{route_id}-point-{index}"
            scene["anchors"][anchor] = point_spec(point)
            anchors.append(anchor)
        steps = {"mount": {"type": "MOUNT", "rider-id": rider, "vehicle-id": vehicle}}
        for lap in range(2):
            circuit = anchors[1:] + anchors[:1] if lap == 0 else list(reversed(anchors[1:])) + anchors[:1]
            for index, anchor in enumerate(circuit):
                steps[f"lap-{lap}-{index}"] = {
                    "type": "MOVE", "actor-id": vehicle, "anchor": anchor,
                    "route-profile": route_id, "timeout-ticks": 600,
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
