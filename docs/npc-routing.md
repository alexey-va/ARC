# Controlled NPC routes

`CitizensNpcRouteController` is ARC's shared flat-floor router for scripted NPC
movement. ARC chooses every path cell and supplies Citizens with a custom
`PathStrategy` that follows those cells through horizontal velocity. This avoids
Citizens handing each point back to Minecraft navigation, which may otherwise
choose a nearby stair or tabletop. Citizens still owns the navigation lifecycle
and client movement animation.

Create one `NpcRouteProfile` for each navigable area:

- `bounds` keeps the NPC inside the scene;
- `forbidden` contains hard no-go rectangles;
- `preferred` contains low-cost corridors;
- `floorY` prevents climbing onto furniture or changing floors;
- `speedModifier`, margins and stall settings tune movement per scene.

The heading controller follows cells ahead of the NPC instead of instantaneous
Citizens velocity. It keeps pitch at zero while moving and limits yaw change per
tick, so short velocity drops and right-angle grid corners do not jerk the head.
Tune `heading-look-ahead-cells`, `heading-update-ticks` and
`heading-max-turn-degrees-per-tick` per profile.

`corner-smoothing-distance` starts a small turn blend before a right-angle
cell, while `corner-smoothing-lead` limits how far the blend reaches into the
next cell. `maximum-step-height` caps the entity step attribute during a route;
the default permits thin carpets but prevents climbing stairs and furniture.

Pass ordered `via` locations to `navigate` when the route must cross particular
gates. Pass `extraBlocked` for obstacles known only to the current action. A
scene may inject an `NpcRouteObstacleSource` to translate furniture, doors or
other runtime objects into blocked cells without adding those dependencies to
the shared router.

```kotlin
val routes = CitizensNpcRouteController(::recordRouteEvent, obstacleSource)
routes.navigate(
    npc = npc,
    destination = destination,
    profile = courtyardProfile,
    via = listOf(archway, westAisle),
    extraBlocked = occupiedWorkCells,
)
```

Configuration owners should keep coordinates outside Kotlin. After the caller
reloads its YAML, build a new profile and use it for subsequent routes.
