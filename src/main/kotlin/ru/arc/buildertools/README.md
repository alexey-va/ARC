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
  world preview, but repeated placement clicks never open build confirmation or
  mutate the world; they point the player to the quote step. After successful
  activation, an already open preview is refreshed to the registered instance,
  and a fresh confirmation click is still required. `/builder book activate` reads the
  current admin-shop material prices and shows material cost, configured
  construction markup, and total without charging. Only the separate clickable
  `/builder book confirm` pays and registers one single-use instance.
- `/builder book` prints the complete clickable seven-step workflow and then
  reports the player's current step. `/builder book status` reports only that
  contextual next action. For a registered physical book, status reads its
  instance and blueprint from MySQL before calling it active; owner, UUID,
  generation, state, title, and stored cost therefore come from the same
  authority used by build/copy/sale. Concurrent status spam is coalesced and a
  held-item change cannot receive the earlier lookup result. Selection,
  clipboard, draft, quote, delivery, and activated-book messages each lead
  directly into the next safe command.
- Holding a registered book, `/builder book copy` shows the same stored
  self-cost and `/builder book confirm` pays for a new instance with a new UUID.
  The registered book may be listed in zAuctionHouse at any seller-selected
  price through `/builder book sell <price>`. This protected sale path is also
  the ownership-transfer path: direct inventory/drop transfer does not update
  the authoritative owner and therefore cannot make the recipient's copy
  usable. Ordinary `/ah sell` paths reject player build books so the UUID lease
  cannot be bypassed by zAuctionHouse's sell GUI.
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
  purchase reads the current admin-shop quote again, and material or price
  drift outside the configured tolerance refreshes the estimate instead of
  spending. If one of several purchases fails, the world remains untouched and
  completed purchases stay in the player's inventory for a safe retry.
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

Region operations use the canonical `arc.builder.tools.*` namespace, while
book creation, use, editing, and sale retain the existing `arc.build.book.*`
permissions. The former `arc.buildertools.*`, `arc.deconstruction*`, and
`arc.crown` nodes, including their selection-size and hourly tiers, are not
accepted by the runtime.

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
each physical item carries a blueprint UUID, instance UUID, and positive
generation, while MySQL is the authority for its owner and generation. Before
the local journal is created, core's shared
`arc_one_time_uses` table claims the instance UUID under purpose
`arc.builder_book` with that generation in the fingerprint, while the domain
row verifies the owner and moves `AVAILABLE -> RESERVED`. A committed build
changes both ledgers to `COMMITTED`/`CONSUMED`; a proven
pre-mutation rollback releases the shared claim and returns the domain row to
`AVAILABLE`. Unknown outcomes retain both recovery identities. The core ledger
holds a MySQL advisory lock across the external operation, so two duplicated
items carrying the same instance UUID cannot both build, copy, or reserve
concurrently.

Auction listing adds a separate `AVAILABLE -> LISTED` compare-and-set and an
exact lease UUID copied into the auction ItemStack. The zAuctionHouse blacklist
rule blocks drafts and registered books from every ordinary sell path; the
protected command authorizes only the exact token during synchronous listing
validation. The guard also inspects bounded shulker and bundle contents, so a
book cannot bypass the safe route inside a container. Listed, purchased, and
expired storage retain the lease. A return or buyer claim first moves the row
to `TRANSFER_PENDING`, atomically changes its owner, increments the generation,
stages that generation on the one exact tokenized item, and only then returns
the row to `AVAILABLE` and removes the token. Every older physical duplicate is
permanently stale after that transition. Online recovery rechecks unresolved
tokenized items every five seconds with rate-limited retries; timeouts and
missing delivery evidence remain fail-closed instead of making a second
physical copy usable.

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
The `book-contracts.mysql` pool owns the three `arc_builder_book_*` domain
tables and the single shared `arc_one_time_uses` table. It must not introduce a
feature-local replay table. Shop pricing is read-only; ARC never changes
EconomyShopGUI prices.
The authenticated ARC runtime-health surface publishes only bounded aggregate
state for Builder Tools: lifecycle state, recovery backlog, active leases, and
Lands/CoreProtect/book-registry readiness. It never exposes player identities,
book UUIDs, SQL errors, or credentials.
