# Controlled NPC routes

`CitizensNpcRouteController` is ARC's shared flat-floor router for scripted NPC
movement. Citizens executes the prepared vectors; ARC chooses every path cell.

Create one `NpcRouteProfile` for each navigable area:

- `bounds` keeps the NPC inside the scene;
- `forbidden` contains hard no-go rectangles;
- `preferred` contains low-cost corridors;
- `floorY` prevents climbing onto furniture or changing floors;
- `speedModifier`, margins and stall settings tune movement per scene.

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
