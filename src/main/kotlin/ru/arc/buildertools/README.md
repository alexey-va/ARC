# ARC Builder Tools

`BuilderToolsModule` replaces the survival Denizen `deconstruction` and
`crown` scripts with one bounded transaction engine. It also owns material-backed
`fill`, temporary `copy`/`paste`, preview/confirmation, and conservative undo.

## Player contract

- `/builder wand`: binds one plain echo shard already owned by the player as
  the selector; left click sets position 1 and right click sets position 2.
  Each corner and the clipped particle outline refresh twice per second until
  `/builder clear`, even after the selector is put away. Moving to another world
  cannot join stale and current-world corners. Prepared plans refresh their
  bounded preview for the whole TTL.
  `/builder crown wand` similarly binds one owned plain brush. ARC never mints
  the tool materials. Particles are visual-only and never fake client-side blocks.
- `/builder fill [material]`: changes only configured replaceable blocks and
  consumes exact plain vanilla items.
- `/builder copy` and `/builder paste`: retain only safe non-air vanilla
  BlockData for 15 minutes. Paste uses position 1 as its minimum-corner anchor
  and consumes every placed block (including two items for a double slab).
- `/builder book draft [name]`: turns one owned ordinary book into a free,
  content-addressed draft. A draft can use the full transform GUI and continuous
  world preview, but cannot mutate the world. `/builder book activate` reads the
  current admin-shop material prices and shows material cost, configured
  construction markup, and total without charging. Only the separate clickable
  `/builder book confirm` pays and registers one single-use instance.
- `/builder book` prints the complete clickable seven-step workflow and then
  reports the player's current step. `/builder book status` reports only that
  contextual next action. Selection, clipboard, draft, quote, delivery, and
  activated-book messages each lead directly into the next safe command.
- Holding a registered book, `/builder book copy` shows the same stored
  self-cost and `/builder book confirm` pays for a new instance with a new UUID.
  ARC does not own a blueprint marketplace: the physical registered book is
  transferable and may be listed in zAuctionHouse at any seller-selected price
  through `/builder book sell <price>`. Ordinary `/ah sell` paths reject player
  build books so the UUID lease cannot be bypassed by zAuctionHouse's sell GUI.
- `/builder deconstruct`: requires one preferred held tool for the whole
  selection, checks worst-case remaining durability, calculates drops once,
  damages the real tool, and requires all exact drops to fit the inventory.
- `/builder crown [leaves] [radius]`: makes a deterministic organic crown from
  ordinary persistent leaves, replacing only configured vegetation/air and
  consuming every leaf. Its nested command surface provides bounded palettes,
  `natural|round|wide|tall` shapes, `airy|natural|dense` density,
  `smooth|natural|wild` edge noise, and an explicit `reroll`; palette weights
  are deterministic and every selected leaf type is charged exactly.
- Every mutation first creates an immutable particle preview. `/builder
  confirm` is required within 30 seconds. For `fill`, `paste`, and `crown`, the
  preview also quotes the complete build and the current inventory deficit from
  EconomyShopGUI's active admin-shop prices. `/builder confirm buy` explicitly
  buys only missing exact plain vanilla materials through the native shop API,
  then enters the same journaled confirmation path. A plain `/builder confirm`
  never spends money. Command products, custom items, composite currencies,
  and unavailable requirements fail closed. The estimate is informational;
  purchase uses the admin shop's current price without an extra confirmation
  for price drift. If one of several purchases fails, the world remains untouched
  and completed purchases stay in the player's inventory for a safe retry.
  `/builder undo` creates and confirms
  an inverse material transaction; deconstruction undo returns blocks only
  after the exact collected drops are surrendered, never repairs tool wear, and
  never buys replacement drops.
- Survival consumes and returns the exact material transaction described
  above. Creative is also supported for staff/build testing, but ordinary
  fill/paste/deconstruction/crown operations neither consume nor produce items
  and never damage a tool. A registered build book remains paid and single-use
  in every game mode; creative cannot bypass its UUID ledger. Changing game
  mode after preview invalidates the plan.

`/builder` is the only public command root. The former `/deconstruction`,
`/crown`, and `/buildtools` roots are deliberately not registered. Existing
named Denizen selector and crown-brush items are still recognized as migration
inputs; the brush anchors a crown in the block outside the clicked face and
confirms only on that same face, while all writes pass the ARC plan/confirm
path.

Permissions use only the canonical `arc.builder.tools.*` namespace. The former
`arc.buildertools.*`, `arc.deconstruction*`, and `arc.crown` nodes, including
their selection-size and hourly tiers, are not accepted by the runtime.

## Safety and recovery

The module is enabled only on survival nodes, world-allowlisted, range-bounded,
and one-operation per player. Every changed coordinate must be inside an active
Lands claim where the player has both place and break access, checked while
planning and immediately before mutation. WorldGuard is not a Builder Tools
dependency. Tile
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
CoreProtect API. Registered player books add a network-wide MySQL barrier:
each physical item carries a blueprint UUID and instance UUID, but MySQL is the
authority. Before the local journal is created, `AVAILABLE -> RESERVED` is an
atomic compare-and-set. A committed build changes it to `CONSUMED`; rollback
returns it to `AVAILABLE`. Two duplicated items carrying the same instance UUID
therefore cannot both build, copy, or reserve concurrently.

Auction listing adds a separate `AVAILABLE -> LISTED` compare-and-set and an
exact lease UUID copied into the auction ItemStack. The zAuctionHouse blacklist
rule blocks drafts and registered books from every ordinary sell path; the
protected command authorizes only the exact token during synchronous listing
validation. The guard also inspects bounded shulker and bundle contents, so a
book cannot bypass the safe route inside a container. Listed, purchased, and
expired storage retain the lease. A return or buyer claim releases only the
matching lease and removes its token from the delivered item. Online recovery
rechecks unresolved tokenized items every five seconds with rate-limited retries;
timeouts and missing delivery evidence remain `LISTED` and fail-closed instead
of making a second physical copy usable.

Book payment is separately journaled before touching RedisEconomy. Provider
calls are never blindly retried. Startup reconciles the exact transaction
reason and amount from RedisEconomy history, finishes paid issuance, refunds a
proven failed issue, restores pending delivery, and quarantines ambiguous money
states for manual review.

## Runtime ownership

The bundled `modules/builder-tools.yml` is disabled by default. The tracked
survival mirror enables `book-contracts` and its MySQL connection, while
`modules/builder-tools-runtime.yml` enables the module for `allowed-worlds:
["*"]`; spawn and parkour remain off. No code path rewrites shop prices.
Journal records live below `plugins/ARC/data/builder-tools-journal/` and are
server-owned runtime state, never configuration deployment input.
The `book-contracts.mysql` pool owns only the three `arc_builder_book_*`
tables. Shop pricing is read-only; ARC never changes EconomyShopGUI prices.
