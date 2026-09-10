# EliteLoot presentation and shared lost loot

`PickupListener` transforms native EM stacks when they appear in the world and
refreshes inventory lore. `EliteLootProcessor` copies visual components without
changing gameplay material, attributes, durability or native PDC. Ground effects
follow physical items, including manual throws; cases never invoke this effect.

## Player flow

`LostLootModule` runs on every Paper backend, including parkour without EliteMobs.
The dungeon panel, global help center and `/loot` (`/добыча`) open the same SQL
mailbox. The configured `lost-elite-loot` chest menu pages through 45 items.
Left click delivers the original complete stack into one empty storage slot;
right click sells it for the displayed native EM resale price. The preview adds
click instructions to a clone; original lore, models, attributes and PDC survive.
No deposits, new rolls, partial extraction or additional price multiplier.

Eligibility uses native EliteMobs 10.1.1 PDC: canonical `elitemobs:soulbind`, a mob
`elitemobs:itemsource` matching the configured native template, and the EM item
marker. ARC case rewards and any entity with a player thrower or persistent
`arc:lost_loot_manual` marker are excluded. Manual throws still get the effect.

## Physical capture and SQL ownership

Natural despawn is cancelled and the entity is frozen against pickup, aging,
damage and movement. One queued entity per tick crosses a synchronous durable
local journal barrier before removal. World unload captures remaining eligible
items before instance deletion; failed capture cancels unload and keeps the item
for retry. Lava/void destruction, consumption and administrator deletion are not
undone. Chunk unload keeps the physical item in its chunk.

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

Focused tests cover capture provenance, exact stack metadata, disk failure and
publish retries, world unload, competing claim/sale service instances, foreign
owners/full inventory, interrupted native item/currency persistence, receipt
recovery, lifecycle cancellation, menu configuration and ordered sync snapshots.
The repository uses arc-core SQL primitives; local unit tests do not prove real
multi-node MySQL/Redis transactions or in-client visuals. Runtime activation and
player acceptance remain separate from source checks and deferred disk delivery.
