# Back wings

`BackWingsModule` owns the cosmetic preference and one `PacketItemDisplay` per wearer.
The existing ItemsAdder meshes, textures, base items and model IDs are unchanged.
Core owns attachment, shared passenger lists, visibility, world changes and viewer replay.
The module never creates a native entity/passenger, teleports a display on each tick,
changes inventory, consumes items or changes attributes.

Plain **F**, with one of the exact IDs below in the **actual main hand**, cancels the
hand swap. The same selected wing turns off; another selects that wing on the same
display. Shift+F, already-cancelled input (including ArcBuilder's editor), ordinary
items and wings held only in the offhand keep their existing routes. The handler
runs at NORMAL with `ignoreCancelled = true`, after MenuShortcutController's LOWEST.

The matching item must remain in one of the 36 storage slots. The cursor, armor,
offhand and nested containers are not storage ownership. Inventory changes request
a check on the next pulse; an every-ten-tick check also catches direct plugin writes.
Loss clears the selection and removes the display. Death/quit hide it while keeping
the preference; respawn/rejoin restore only if the item is still owned. HuskSync joins
wait for `BukkitSyncCompleteEvent` before reading inventory. The preference uses
`arc:back-wings` in the native player's PDC, with no extra persistence service. Current
Spawn/Survival HuskSync configurations have `persistent_data: false`, so the preference
is local to each server; this module does not change that policy.

Configuration is `modules/back-wings.yml`. Normal module reload updates poses in place.
Shutdown unregisters listeners, cancels the lifecycle scope and closes the display
owner; `BackWingsModule.drained()` participates in ARC's native cleanup completion.
One wearer is processed per shared `BudgetedTaskQueue` step, with a fixed 36-slot
inventory bound. An unchanged transform produces no packet delta in Core.

## Model placement

All models use `ItemDisplayTransform.NONE`; the ItemDisplay item renderer centers
the model at JSON coordinate `(8, 8, 8)` before our scale/rotation. The configured
anchor is relative to feet in body axes: +X left, +Y up, +Z forward at yaw zero.
`backWingPose` rotates the anchor and mesh by the cosmetic body-yaw history, applies the
crouch torso lean, then subtracts the full native passenger origin `player.height`.
Display entity yaw and pitch are zero: the FIXED renderer applies those angles before
the transformation. Camera pitch is not used. `pauseWhenNotObserved = false` keeps the owner's third-person
view active. Transform interpolation lasts one tick with zero delay, so native quaternion
SLERP draws intermediate render frames between the 20 Hz poses. Core restarts interpolation
when a transform changes. Teleport interpolation stays zero; world motion belongs to the
native passenger relation rather than repeated world teleports. Quaternion SLERP chooses
the short arc across yaw wrapping; a longer duration would add unnecessary turn lag.

Paper's `bodyYaw` is only an initial baseline. Native 26.3 recording showed the server
remaining at 50° while the walking client's torso reached 1.4°, and at -40° while the
client reached -90°. The server resets its movement origin before its body calculation.
`BackWingBodyYaw` therefore uses observed X/Z displacement and head yaw with the native
1.21.11/26.3 movement threshold, backwards-facing correction, 0.3 smoothing, and 50°
head/body limit (15° while blocking). Small head-only turns retain the torso direction.
Nonwearers update lightweight history through move events, so equipping after a walk
keeps that pose; only wearers use the existing bounded tick pulse. Observations in one
tick revise one step. History survives toggle/switch/config reload, resets on teleport,
world change and respawn, and is discarded on quit/shutdown. Network timing still makes
this a reconstruction of the native pose, not access to each viewer's render state.

| Item ID | Existing base / CMD | Model | Rotated source bounds X / Y / Z (pixels) | Initial scale / Y rotation / anchor (blocks) |
| --- | --- | --- | --- | --- |
| `dreadknight:dreadknight_wings` | NETHERITE_SWORD / 10067 | `dreadknight:dreadknight/dreadknight_wings` | 4.75..11.25 / -1..37.5772 / -25.3706..41.3706 | .70 / 90° / (0, 1.02, -.31) |
| `galaxy_set:galaxy_wings` | PAPER / 11905 | `galaxy_set:galaxy_set/wings` | 6..9 / -1..30.2 / -15.4094..31.4094 | .90 / 90° / (0, 1.02, -.25) |
| `deceaseset:decease_wings` | PAPER / 11917 | `deceaseset:wings` | -13.725..29.725 / -.6231..17.3769 / 7.1..8.9 | .95 / 0° / (0, 1.10, -.22) |

Bounds include each original cuboid rotation. Dreadknight is the representative:
the initial standing span is about 2.92 blocks and its upper point is 2.31 blocks
above feet. Galaxy and Decease spans are about 2.63 and 2.58 blocks. Global crouch
adjustment is `(0, -.20, +.12)` with 28.64789° forward torso lean. Standing/crouching
are the authored poses; swimming, elytra flight, sleeping and unusual scaled player
models do not yet have independently authored pose variants.

## Focused checks and evidence boundary

```bash
./gradlew :test --tests ru.arc.backwings.BackWingPoseTest \
  --tests ru.arc.backwings.BackWingBodyYawTest \
  --tests ru.arc.backwings.BackWingsRuntimeTest \
  --tests ru.arc.backwings.BackWingsConfigTest
```

The pose tests cover full-height origin compensation, yaw and crouch. Runtime tests
use the real mock inventory/PDC with explicit native pose and packet-display doubles:
they cover both-hand invariance, toggle/switch identity, storage ownership, exclusions,
death/quit/replay, reload and asynchronous cleanup. Config checks cover merge-forward,
preserved operator tuning and invalid-model fallback. They do not prove packet wire
delivery, the Minecraft model renderer, native walking/turning or observer visibility.
The parent task performs the first Dreadknight fit and walking acceptance in the actual
26.3 client, then confirms or adjusts the three configurable poses. Source-preview
mesh PNGs establish model identity only, not player fitting or motion acceptance.
