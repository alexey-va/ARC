# ARC Builder Tools

`BuilderToolsModule` replaces the survival Denizen `deconstruction` and
`crown` scripts with one bounded transaction engine. It also owns material-backed
`fill`, temporary `copy`/`paste`, preview/confirmation, and conservative undo.

## Player contract

- `/builder wand`: selector; left click sets position 1 and right click sets
  position 2. Particles are visual-only and never fake client-side blocks.
- `/builder fill [material]`: changes only configured replaceable blocks and
  consumes exact plain vanilla items.
- `/builder copy` and `/builder paste`: retain only safe non-air vanilla
  BlockData for 15 minutes. Paste uses position 1 as its minimum-corner anchor
  and consumes every placed block (including two items for a double slab).
- `/builder deconstruct`: requires one preferred held tool for the whole
  selection, checks worst-case remaining durability, calculates drops once,
  damages the real tool, and requires all exact drops to fit the inventory.
- `/builder crown [leaves] [radius]`: makes a deterministic organic crown from
  ordinary persistent leaves, replacing only configured vegetation/air and
  consuming every leaf.
- Every mutation first creates an immutable particle preview. `/builder
  confirm` is required within 30 seconds. `/builder undo` creates and confirms
  an inverse material transaction; deconstruction undo returns blocks only
  after the exact collected drops are surrendered and never repairs tool wear.

Legacy `/deconstruction` and `/crown` commands remain aliases, including
`/crown wand`. Existing named Denizen selector and crown-brush items are
recognized as migration inputs; the brush still anchors a crown in the block
outside the clicked face, but all writes pass the ARC plan/confirm path.

## Safety and recovery

The module is survival-only, world-allowlisted, range-bounded, and one-operation
per player. Every changed coordinate is checked against an active Lands claim
and WorldGuard both while planning and immediately before mutation. Tile
entities, Slimefun blocks, ItemsAdder custom blocks, ARC custom block data,
falling blocks, technical blocks, redstone controls, and multi-block hazards are
rejected. Waterlogged, lit, and powered states are never reproduced for free;
copied leaves are made persistent. Operations have an exact per-operation bound
and reuse the existing hourly and selection-size permissions.

Before any inventory or world mutation, ARC commits the complete player
inventory snapshot and immutable block plan through `DurableRecordJournal` on
an owned storage executor. A second durable `APPLYING` barrier precedes the
first block. The primary thread then applies bounded batches under inventory,
command, damage, explosion, piston, physics, and target-volume locks. Any
mismatch rolls back every applied block and restores/verifies the inventory
snapshot. `COMMITTED` is written only after world and inventory completion.

The `COMMITTED` transition performs an exact durable readback after any write
error. A confirmed `APPLYING` predecessor is safe to roll back; an actually
unknown outcome freezes further builder mutations until restart recovery can
reconcile the journal without guessing.

On startup, records are phase-sensitive and fail-closed. `PREPARED` permits
only the untouched `before` state and never restores an inventory; `APPLYING`
may restore exact `after` states to `before` plus the escrowed inventory.
Unexpected third-party states stop the module for operator review. Offline
player inventory restoration occurs at join before the record is acknowledged.
Every forward and rollback block change is also submitted to the public
CoreProtect API.

## Runtime ownership

The bundled `modules/builder-tools.yml` is disabled by default and refreshed
from the active JAR on startup so schema/locale additions cannot leave a stale
base file. Node policy never edits it: survival opts in through
`modules/builder-tools-runtime.yml`; spawn and parkour remain off.
Journal records live below `plugins/ARC/data/builder-tools-journal/` and are
server-owned runtime state, never configuration deployment input.
