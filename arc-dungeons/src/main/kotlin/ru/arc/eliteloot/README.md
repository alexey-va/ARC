# EliteLoot presentation and shared lost loot

`PickupListener` transforms native EM stacks when they appear in the world and
refreshes inventory lore. `EliteLootProcessor` copies visual components without
changing gameplay material, attributes, durability or native PDC. Ground effects
follow physical items, including manual throws; cases never invoke this effect.

ARC is the sole owner of RusCrafting's final EliteLoot lore and presentation.
Apply presentation changes here after the native EM renderer; keep the EliteMobs
fork limited to persistence and database work.

## Player flow

`LostLootModule` runs on every Paper backend, including parkour without EliteMobs.
The dungeon panel, global help center and `/loot` (`/добыча`) open the same SQL
mailbox. The configured `lost-elite-loot` chest menu pages through 45 items.
Left click delivers the original complete stack into one empty storage slot;
right click sells it for the displayed native EM resale price. Payload hash/Base64 preparation runs on the outbox worker, then one native item
is deserialized and decorated per shared queue step. The final inventory page
contains at most 45 cards. Superseded, closed or stopped preparations cannot open
a menu. The preview adds click instructions to a clone; original lore, models,
attributes and PDC survive.
No deposits, new rolls, partial extraction or additional price multiplier.

Eligibility uses native EliteMobs 10.1.1 PDC: canonical `elitemobs:soulbind`, a mob
`elitemobs:itemsource` matching the configured native template, and the EM item
marker. ARC case rewards and any entity with a player thrower or persistent
`arc:lost_loot_manual` marker are excluded. Manual throws still get the effect.

## Physical capture and SQL ownership

Collection starts when the owner disconnects, changes worlds or moves more than
64 blocks from the item. The periodic tracked-item sweep, loaded-chunk scan,
owner departure and mailbox settlement share the runtime's native queue
(128 steps / 2 ms per tick across features). A step inspects one entity or binds
one item; the clock cannot interrupt an individual native call.

The native step freezes eligible loot and captures its exact serialized bytes.
The single outbox worker loads the journal, encodes Base64, commits/readbacks
records, publishes SQL and writes export acknowledgements. The entity is removed
only after the UUID index reports a verified durable record. A failed or rejected
write retains the frozen physical item and logs the retained retry. An old
completion cannot mutate a replacement entity or a stopped generation.

Early tickets retain only already-loaded chunks containing eligible tracked
loot. Paper's ticket API may force-load a chunk, so the loaded check and ticket
acquisition occur together on the native thread. Tickets are released after
capture, pickup or other disappearance. LostLoot never adds a ticket from a late
entity unload event. `EntitiesUnloadEvent` only invalidates that chunk's native
capture epoch: vanilla keeps the physical copy and later load reconciliation uses
the journal UUID/tombstone to suppress duplicates. No unloaded entity object is
serialized or revisited asynchronously. A cancellable world unload is refused
until a sliced scan, durable capture and native removal have completed; the caller
may retry it before deleting an instance. Lava/void destruction, consumption and
administrator deletion are not undone.

The `LostLootProtection` listener is registered to the existing ArcCore host and
retains only the memory UUID index. Known outbox/tombstone IDs and capture markers
cannot enter inventory, hoppers, merges or despawn during consumer disable and
reload. Before the next journal cache is ready, canonical bound EliteMobs-source
drops are protected as well; ordinary vanilla loot, case rewards and deliberate
manual drops remain outside that startup hold. The next successful journal load
swaps the index after the previous generation's actual drain. This intentional
continuity listener owns no worker, SQL lease, chunk ticket or entity references.

`LostLootModule.ready()` includes local cache attachment, SQL preparation and
mailbox attachment. Cold configuration and SQL acquisition run on the captured
parent worker, independently of local journal captures. Shutdown seals admission
without waiting on Paper, drains accepted local work and SQL publications, then
closes accepted JDBC work before returning its SQL lease. Direct leases are
retained through provider shutdown as soon as acquisition succeeds; failed
retention closes the candidate before any JDBC work is admitted. Host-owned native
cleanup retires chunk tickets in slices; `drained()` observes both storage and
native completion. A host stop cannot promise future native ticks and fails that
cleanup observation; the journal and persistent physical marker remain recovery
authority on a full restart.

Capture moves an already generated stack: direct mint/burn is zero in vault,
tokens, EliteMobs crystals and XP. Drop rolls, quantities and resale prices are
unchanged; subsequent sale remains the existing native crystal credit flow.

`plugins/ARC/data/lost-elite-loot/` is a capture outbox over arc-core's
`DurableRecordJournal`, not a separate server mailbox. `STORED` payloads publish
idempotently to SQL by entity UUID, owner and SHA-256 payload hash. Verified shared
publication changes the local record to `EXPORTED`, retaining a payload-free
entity tombstone against stale chunk copies. Network/SQL failures retain local
records and retry; failed local acknowledgement retries too. Do not prune these
tombstones without proving absence of old entity copies. Old local `CLAIMING`
records are quarantined, never automatically republished.

SQL reuses the existing network ARC connection in `modules/audit.yml` (the current
profile uses schema `common` and the existing shared account named `arcduels` on
all three backends). No account or credentials are created/copied. Independent
InnoDB tables are `arc_shared_lost_loot`, `arc_shared_lost_loot_owner_gate` and the
arc-core migration ledger. Async SQL owns these transitions:

- `AVAILABLE -> CLAIMING -> CLAIMED`: claim reservation then native delivery.
- `AVAILABLE -> SOLD`: mutually exclusive sale, immutable crystal receivable.
- `SOLD -> CREDITING -> PAID`: reservation then native crystal delivery.

An owner gate serializes native item and currency operations across servers.
Exact owner, record, state and random operation token guard every transition.
Known failures before native mutation can release their reservation. Unknown
outcomes retain the operation and gate; no timeout may blindly reissue/recredit.

## Native delivery receipts

Claims put one intact item and `arc:lost_loot_receipt` into native player data,
call `Player.saveData()`, then acknowledge SQL. A matching loaded receipt can
finish an interrupted claim without granting another item. Missing receipt means
operator reconciliation. The original payload remains in a pending claim.

Sale price is captured with pinned EM `determineResaleWorth(stack, null)` times
stack amount; that method already applies the configured resale percentage
(currently 80%). Sale removes the SQL item and creates an equal crystal liability;
this is not a second reward or Vault/token flow. On parkour, sale is recorded
immediately and payout waits for a backend with native EM and ready `EmSync`.

Native `EconomyHandler.addCurrency` owns rounding and gambling-debt repayment.
Vault mode is rejected. `EmSync` snapshots currency plus a unique receipt together
into Redis, ordered behind earlier local snapshots. Only a confirmed snapshot
allows SQL `PAID`. The latest receipt remains with future snapshots; receipt-backed
snapshots are reapplied even on the same backend after restart, so EM's separate
asynchronous SQLite queue is not the only recovery source. A matching receipt
reconciles `CREDITING` without a second increment. Failure/missing proof retains
the immutable amount and token for operator review.

This is not a transaction spanning SQL, Redis and native EM storage. Existing
cross-backend EmSync snapshots still have no distributed CAS; overlapping/stale
sessions and loss of authoritative Redis data require operator reconciliation.
Do not compensate or reset pending states before comparing native inventory,
receipt, currency snapshot and SQL state. Terminal records retain operation IDs.

## Verification boundary

Purpur 1.21.11 (server JAR SHA-256
`34ad0d95aa1210df89def3b344bd57d09a2fe497156876772103baea96d8df21`,
inspected 2026-10-17) calls `EntitiesUnloadEvent` from `LevelChunk.unloadCallback`
before entity NBT serialization in `NewChunkHolder.unloadStage2`. The entity
section is already inaccessible at the event; `isValid` can be false for a
living item. `ChunkUnloadEvent` follows it and is not cancellable. This is why
capture must acquire a loaded chunk ticket earlier: moving the old event's
serialization to a worker would read stale native state and could duplicate the
saved physical copy. The late callback performs only an epoch change.
`World.loadedChunks`, `Chunk.entities`, native item serialization/deserialization,
and `Player.saveData()` remain indivisible native operations. Iteration after a
chunk snapshot is sliced; `saveData()` is still the required item/receipt durability
barrier before SQL acknowledgement and is not claimed to be asynchronous.

`./gradlew :arc-dungeons:test --tests 'ru.arc.eliteloot.*'` covers blocked cold
journal/write admission, FIFO persistence, explicit overload, terminal SQL ACK
ordering, host guard continuity, exact-stack durability, failed-write retry,
chunk epoch invalidation, world-unload barriers, 300-item native budgets, stale
mailbox preparations and native receipt reconciliation. The tests model
Paper entity state using API mocks; the unload ordering above was established
separately against the actual server artifact.

Focused tests cover capture provenance, exact stack metadata, disk failure and
publish retries, world unload, competing claim/sale service instances, foreign
owners/full inventory, interrupted native item/currency persistence, receipt
recovery, lifecycle cancellation, menu configuration and ordered sync snapshots.
The repository uses arc-core SQL primitives; local unit tests do not prove real
multi-node MySQL/Redis transactions or in-client visuals. Runtime activation and
player acceptance remain separate from source checks and deferred disk delivery.
