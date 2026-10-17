# Home and warp markers

`ru.arc.homemarkers` renders nearby owned HuskHomes homes and PlayerWarps warps.
Those providers remain the authority for ownership, positions and settings.
Only points on the current server and world are admitted; provider data is not copied to new storage.

- Each marker and label has `SceneAudience.Private(ownerId)` over `PaperPacketDisplays`.
- **Shift + main-hand right-click** on the light column, floor rune or caption opens that point's existing settings.
- `PaperSpatialControls` uses `ENTITY_RIGHT` with private native Interaction targets only while sneaking.
  Vanilla 1.21.11 sends no use-item packet for an air miss with both hands empty; a native target makes that click observable.
  This follows `Minecraft.startUseItem` in the official [1.21.11 client and mappings](https://piston-meta.mojang.com/v1/packages/718cd84b4912691fe5de829aab6876a4b7f6c5c4/1.21.11.json).
  ToggleSneak's new event state admits/removes targets immediately; ordinary standing interactions keep their targets.
- Separate body and caption boxes include a 0.2-block margin and stay stable when the player turns their head.
  Before activation, a temporary Core ray scene checks the current body/caption/rune planes with native reach and
  block occlusion. A box protruding before a wall cannot authorize the marker hidden behind it.
- Gesture dispatch and scene activation require sneaking. The accepted click needs no continued Shift or aim.
  The provider callback rechecks ownership, the unchanged point, and the player's current session and world.
- Nearby selection keeps 64 owned points; body and caption use separate viewer scenes within Core's 64-control limit.

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
