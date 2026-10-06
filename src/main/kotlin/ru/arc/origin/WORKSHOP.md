# Origin furniture workshop

The furniture terrace runs on the `classic` server in Minecraft world
`rc_origin_spawn`. Server names, world names and repository paths are separate.

## Owners

- `OriginWorkshopTablesModule.kt`: packet-only benches, materials, mechanisms,
  their local geometry and player-only highlight overlays.
- `OriginWorkshopGameRecipe.kt`: role-specific input and processing sequences.
- `OriginWorkshopWorkpiece.kt`: actual cut/drilled board geometry and shoulder pose.
- `OriginWorkshopGame.kt`: one player session, the selected station's production
  sequence, material handoffs, guidance, cancellation and finished-product view.
- `OriginFurnitureWorkshopModule.kt`: Citizens workers, bounded floor routes,
  decorative production and the shared sleeping place.
- `OriginWorkshopSleepShift.kt`: ordered sleep turns and exclusive player
  reservation. Taking a station pins the current turn until release.
- `OriginWorkshopCraftRewards.kt`: one furniture item through the existing
  asynchronous Redis claim, shared across every station for 24 hours. The
  administrator bypass remains `arc.origin.workshop.cooldown.bypass`.

The four workers are carpenter 430, upholsterer 458, assembler 459 and finisher
460. Their products come from the active NPC worker configuration. Stock and
carried materials are scene props; they never consume player inventory items.

The carpenter makes two cuts with a deliberate turn between them, then aligns
and drills three holes separately. Both offcuts remain on the bench; the board
contains progressive through-holes and retains them on the shoulder. Upholstery
uses fabric, press and a cushion; assembly uses vise and hammer; finishing uses
three separately coated panel regions and a return to the drying rack.

Carried props follow player movement and each server tick without additional
client interpolation; they use body-yaw shoulder coordinates instead of the old
eye ray in front of the camera. The native client's network delay still applies.
Placement highlights and hit targets share station-local recipe geometry. The
bench top is tiled into individual light planks to preserve texture density,
with dark framing and metallic mechanisms for contrast. The drill retracts clear
of the finished holes; two support rails keep the board on a visible drill bed.
The assembly jig is hidden while the finished chair occupies its space, and
the normal finish/cancel cleanup restores that fixture for the next session.

## Turn lifecycle

Only a worker with a confirmed sleeping pose opens its station to a game. At
startup the first available worker is seated immediately, even without viewers.
After the configured rest, the next available worker finishes its decorative
cycle and walks to the sofa approach. The current worker keeps sleeping during
that walk, route retries and any active player session. Only after the incoming
NPC's actual Bukkit pose is SLEEPING does the old worker wake and walk home.
Returning and incoming workers cannot start new production cycles. A missing
sleeper is replaced immediately by another available worker; a skin respawn's
lost sleeping pose is repaired before invalidating a current player's lease.

Both Citizens sleep and pose traits are applied before the readiness check;
setting a sleep target alone is not confirmation. The workshop resumes its
owned NPC navigators and restores their prior pause states on shutdown. Sleep
coverage cannot be maintained if all four actors are despawned or native pose
application fails; failures are logged, and an unconfirmed station stays closed.

The game calls `acquirePlayerTable` only after the asynchronous quota response
and fresh station/distance checks. `ownsPlayerTable` fences every active tick
and reward callback. The timeout measures inactivity since the last production step. Quit,
cancellation, timeout, module reload and shutdown
remove game displays and release the exact reservation. NPC runtime replacement
invalidates old reservations even if the same first station becomes available.

## Configuration

Bundled defaults are in `src/main/resources/modules/origin-workshop-game.yml`,
`origin-workshop-tables.yml` and `origin-furniture-workshop.yml`. Tracked active
overrides are in sibling `ruscrafting-ops/classic/plugins/ARC/modules/`.

`origin-furniture-workshop.sleep-rotation` replaces the old permanent per-worker
`sleeping` flag. Duration and sofa/approach coordinates load with the module's
normal reload; reload recreates routing and turn state. The authored default
is 2400 ticks, with approach `-56.5,71,-49.5` and seat `-56.5,71,-46.5`.
Initial placement, recovery and the seat transition use teleportation; normal
shift walking uses the existing
`CitizensNpcRouteController` and the configured safe floor bounds.
The route's cloned Citizens parameters cover the farthest planned point from
the final endpoint; a short inherited NPC range must not cancel a valid long
walk to the sofa. Persisted navigator defaults are preserved.

## Focused checks and evidence

Run the `ru.arc.origin.OriginWorkshopSleepShiftTest`,
`OriginFurnitureWorkshopConfigTest`, `OriginWorkshopGameTest`, and
`OriginWorkshopCraftRewardsTest` Kotest specs, plus recipe/geometry tests for
changed station models. A nonzero executed-case count with no skips is required.

Pure sequence and geometry checks establish ordering and ownership, not native
Minecraft appearance. Server verification separately checks the exact delivered
JAR/configuration, module readiness and `ORIGIN_WORKSHOP` sleep/route events.
Client smoothness, reachability and product appearance need actual client
observation; a successful package or health response does not establish them.

## Offline visual receipt

`./gradlew exportWorkshopPreview -PworkshopPreviewOutput=/absolute/path/scene.json`
exports the production table geometry, mechanism poses, progressive board holes,
and shoulder anchor. It includes all four stations and 19 representative states;
it does not instantiate Bukkit worlds/entities or claim native-client acceptance.
Bake its vanilla material palette with the sibling ops location-atelier's
`vanilla_assets.py` and the cached client JAR, then inspect textured renders from
front, side, top, overview, and the player's 1.62-block eye level. Use the actual
block model quads scaled by each display's dimensions. A human mannequin is only
an approximate silhouette; world context, NPCs and finished ItemsAdder furniture models are
not included in this receipt. Re-export after model/pose changes.

Carpenter boards use the `arc_workshop:board_*` ItemsAdder models from the ops
content tree. Their face UVs are anchored to a shared model coordinate system,
so drilling reveals holes without rescaling or repeating the grain per cuboid.
The same geometric pieces remain the analytic hitboxes. Missing board assets
block a new carpenter session before reservation or a reward claim.

### Board grounding receipt (2026-10-06)

The actual Spawn allocation cache assigns PAPER custom model data 12575–12581.
Source model hashes below are the analyzer's resolved-model SHA-256. Fixed item
context is neutral, scale is `(1,1,1)`, translation/right rotation are identity,
and left quaternion `(0,1,0,0)` cancels the native ItemDisplay Y+180 base once.
All seven reports are `grounded` with zero post-adjustment contact residual.
The exact production geometry defines support at table height `h + .13` for
saw feed, `h + .10` for drill rails and `h` for offcuts; board centers add `.04`.
The receipt uses `h=1.08`, with the same relative proof for any station height.

| Item suffix | PAPER CMD | Entity Y / support Y | Resolved model SHA-256 |
| --- | ---: | --- | --- |
| `board_raw` | 12575 | 1.25 / 1.21 | `044be60b62a52b306da160ea28b74efad5f2d9d1739e7f0c69609d5a25b07f45` |
| `board_cut_once` | 12576 | 1.25 / 1.21 | `c432b61e87e981d2fefccbccb94d71a2639b8584abf9b5ab7c180b1e95e24b7f` |
| `board_cut` | 12577 | 1.25 / 1.21 | `122992fc05ed1e2521e390a91e87286995f94f953fa644a0f2880794b79927b8` |
| `board_drilled_1` | 12578 | 1.22 / 1.18 | `70b207930eff71835e9d37fb23dc2ed280f3e3830a17c2457da50883a6bf0c2c` |
| `board_drilled_2` | 12579 | 1.22 / 1.18 | `5f1ad1cf39e4d5e156731c31f9c18b269ff9bc342ddf948572b5680602c3f8e7` |
| `board_drilled_3` | 12580 | 1.22 / 1.18 | `b13c560f6b47f5a3f436d3b467a7743bc7524d5e198ddbd09e497e4970694c84` |
| `board_offcut` | 12581 | 1.12 / 1.08 | `ef4b0e759aeae4aad7cf393f64fd4b1294e77073c8a7275abbb5cb1f3ac3e1d2` |

Reproduce with the installed `itemsadder-item-display-grounding` analyzer:

```sh
python3 -B <skill>/scripts/analyze_itemsadder_display.py \
  --itemsadder-root <source-contents-and-actual-allocation-cache> \
  --resource-pack <vanilla-1.21.11-client.jar> \
  --item-id arc_workshop:board_drilled_3 --context fixed \
  --left-quaternion 0,1,0,0 --scale 1,1,1 \
  --entity-position 0,1.22,0 --surface-y 1.18 --output <report.json> --report
```

The generated pack preserves all seven model geometries, UVs and fixed transforms.
ItemsAdder creates separate vanilla-oak sprite aliases for block and modern item
atlases; both resolve to `minecraft:block/oak_planks`. All paper item-selection
overlays contain the seven models. The initial bucket-capacity failure was fixed
by removing dated archive uploads and clearing the existing archives. Publication
and the public pack manifest are checked before activating the game JAR; native
Minecraft rendering remains a separate client check.

There are no new textures. The seven source models contain 25 cuboids and 126
faces in total; the most complex state is ten cuboids / 48 faces. The 24 fully
internal rail-end faces are omitted. ItemsAdder generates two compatibility
copies of the model set; these do not represent additional gameplay states.
