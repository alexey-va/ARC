# Home and warp markers

`ru.arc.homemarkers` renders nearby owned HuskHomes homes and PlayerWarps warps.
Those providers remain the authority for ownership, positions and settings.
Only points on the current server and world are admitted; provider data is not copied to new storage.

- Each marker and label has `SceneAudience.Private(ownerId)` over `PaperPacketDisplays`.
- **Shift + right-click** on the light column or floor rune opens that point's existing settings.
- `PaperSpatialControls` checks the `EXTERNAL_RAY` hit, native interaction reach and block occlusion at the click.
  It creates no native Interaction entity, so ordinary block use and mining keep their targets.
- Gesture dispatch and scene activation require sneaking. The accepted click needs no continued Shift or aim.
  The provider callback rechecks ownership, the unchanged point, and the player's current session and world.
- Nearby selection keeps the closest 64 owned points, matching the shared control limit.

`HomeMarkersModule` owns the controller, source refresh and reload lifecycle.
Provider snapshots refresh every 100 ticks (five seconds at 20 TPS).
Visual reconciliation follows the controller; Core handles received chunks, connection replay,
range culling and ordered packet cleanup. Quit, death, world changes and module shutdown clear
owned state. Periodic refresh and click-time provider revalidation reject deleted or moved points.

`modules/home-markers.yml` controls `refresh-ticks`, `view-distance` (32 blocks),
`scale` (1.6), `label-scale` (0.6), `model-id`, colors and visible wording.
Reload recreates the scene with the new settings. The source default uses a vanilla beacon.
RusCrafting's runtime model override is `lemon_vfxdrop:lemon_loot_effect`, the existing elite-drop
POTION model with a tintable light column and floor rune. It is 1.6 blocks wide and 2.4 high
at the default scale; the model floor sits 0.05 blocks above the saved point.
The model's own three-tick interpolated texture supplies the motion without a server animation loop.
ItemsAdder load/reload events refresh the model and current scenes if its registry was initially unavailable.

Canonical visual/config source: `HomeMarkerScene.kt`, `HomeMarkerConfig.kt` and the module YAML.
Focused checks: `./gradlew test --tests 'ru.arc.homemarkers.*' --tests 'ru.arc.helpcenter.HelpCenterScreensTest'`.
For a source-driven visual fixture, set `HOME_MARKER_PREVIEW_OUTPUT=/tmp/home-marker-preview/geometry.json`
while running `HomeMarkerSceneTest`, then run
`python3 -B scripts/render-home-marker-preview.py --geometry /tmp/home-marker-preview/geometry.json --output /tmp/home-marker-preview`.
The adapter reuses the atelier renderer and Minecraft font renderer with the exact existing model/texture.
Preview geometry, texture playback and source checks are separate from active delivery and ordinary-client
acceptance. Native packet privacy, resource-pack availability, gesture feel and real-world obstruction
still require a bounded native client observation; an offline preview does not establish those outcomes.
