# Origin furniture workshop

The four workshop recipes use physical pickup, placement, machine controls and
visible processing on client-only packet displays. No game prop is a Bukkit
world entity. The existing `origin-workshop-game.workpiece-renderer` setting
selects `model` or `cubes` for the carpenter's progressive board models.

## Player interaction

A small solid, full-brightness blue cube marks the accepted click target, with
a cyan native glow silhouette. Both its filled body and glow turn white on
hover; picking the underlying workpiece or actual control also works. The press
and drying rack have low front handles. The hint does not draw
an outline around a machine or the whole table. Finishing cues are smaller so
they do not obscure the narrow panel. Two small cyan dust particles appear every
12 ticks around the current clickable cue, only for its nearby session player.
No dust is emitted while a machine is processing or after the session ends.
Sanded bands use light birch; coated bands use dark stripped oak to make progress
distinct from raw wood and the workbench.

- Carpenter: 21 actions, two cuts, three aligned drill holes, legs and clamps.
- Upholsterer: 15 actions, stretch cloth, sew both edges, transfer the cover,
  add padding, tuck both edges and fasten both sides.
- Assembler: 19 actions, fit two edges in the vise, join two positions on the
  anvil, transfer the top, fit two legs and tighten their fasteners.
- Finisher: 19 actions, lower the panel, sand three bands on each side, coat
  three bands on each side, hang it back on the rack and dry it.

Progressive packet geometry shows each completed seam, edge, join, sanding band
and coating band. Static machine samples remain hidden while player workpieces
occupy their places. Finishing a product removes temporary workpieces.

Completed furniture uses the ItemsAdder `NONE` world context at uniform scale
0.65 and the station yaw. It rests on a clear area of the tabletop, using
model-derived contact offsets and role-specific anchors verified against the
machine geometry. The carpenter's temporary assembly jig disappears while its
finished chair is displayed; the other machines remain visible around their
separate output area. No resource-pack rebuild or added model is required.

## Admin sleep command

`/arc workshop sleep <npc-id>` requires `arc.admin` (default OP) and also works
from console. IDs are tab-completed:

| NPC | Station |
| --- | --- |
| 430 | Carpenter |
| 458 | Upholsterer |
| 459 | Assembler |
| 460 | Finisher |

The request selects the exact next sleeper. It waits for the current player's
station lease and the selected worker's active cycle. The current sleeper wakes
only after the replacement is confirmed sleeping. Repeating a request for an
already sleeping worker does not modify the schedule. Unavailable NPCs and
unready/disabled workshops return an explicit response.

## Verification and balance boundary

Recipe, processing geometry, click geometry, sleep handoff, command parsing and
model contact/clearance checks have focused tests. `exportWorkshopPreview` exports the
same geometry factories and machine poses used at runtime, including the actual
finished-model transform contract. A source preview does not establish native
Minecraft client rendering or real-player gameplay acceptance.

The reward catalog, claim persistence, quantity and cooldown are unchanged:
ordinary players still receive one furniture item per successful eligible claim,
with the existing shared 24-hour cooldown. There is no currency payout change.
The additional actions increase work per claim and cannot increase the daily
ordinary-player reward ceiling; the existing administrative bypass is unchanged.
