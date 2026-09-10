# EliteLoot presentation and lost loot

`PickupListener` prepares native EM stacks at spawn and refreshes inventory lore.
`EliteLootProcessor` copies visual components without changing gameplay material,
attributes, durability or native PDC. `EliteLootEffects` follows only physical
items, including manual throws; cases never invoke an acquisition effect.

`LostEliteLoot` is a separate backend-local mailbox, opened from the dungeon panel
inside or outside a dungeon. The configured `lost-elite-loot` chest menu pages
through 45 complete items. One click delivers one original stack into an empty
storage slot; no deposits, cursor transfer, partial extraction or new loot rolls.
The menu explicitly says that each server has its own mailbox.

Eligibility reads native EliteMobs 10.1.1 PDC: valid `elitemobs:soulbind`, a mob
`elitemobs:itemsource` matching the native configured template, and the EM item
marker. ARC case rewards and any entity with a player thrower or persistent
`arc:lost_loot_manual` marker are excluded. A manual drop still gets the visual
effect. Source text is not inferred from rendered lore or item price.

On natural `ItemDespawnEvent`, cancel despawn and freeze pickup, aging and damage.
One queued entity per tick passes a synchronous durability barrier before removal.
World unload also captures remaining eligible items before an instance is deleted;
a failed capture cancels unload and retains the frozen physical copy for retry.
This does not undo consumption, lava/void destruction, administrator deletion,
or intentionally discarded items. Chunk unload merely persists the physical item.

The shared `DurableRecordJournal` owns bounded atomic files and readback in
`plugins/ARC/data/lost-elite-loot/`. The feature owns `STORED -> CLAIMING -> CLAIMED`.
A native `Player.saveData()` persists the delivered item and a unique receipt in
the same player data before the durable `CLAIMED` tombstone. That tombstone is kept
to suppress old copies of the item entity when a saved chunk is loaded again.
Claims never replay after an unknown result. A matching loaded receipt completes
an interrupted claim; no receipt means retained `CLAIMING` and operator review.
The record keeps the original serialized item, owner and claim UUID for diagnosis.
Do not delete or change a pending record or issue compensation until inventory,
receipt and server persistence have been reconciled: that would bypass the guard.
Receipt cleanup after a committed tombstone is intentionally lazy; a stale receipt
cannot grant another item and is overwritten by the next successful claim.

`capture` failures retain a frozen entity and retry after 30 seconds. Logs identify
operation, player and record, retain the first exception, and emit recovery once
healthy. Tombstones retain no item payload and are not pruned: deleting them without
proving absence of old entity copies would reintroduce duplication. Bootstrap reads
the local journal before registering the listener. Failed initialization disables
only this recovery feature, retains all records, and logs the exception.

Focused checks:

```sh
./gradlew test --tests 'ru.arc.eliteloot.*' --tests 'ru.arc.gui.ArcMenuConfigurationTest'
```

The persistence tests cover original metadata, owner/full-inventory guards, repeat
clicks, disk failure and retry, interrupted native save with/without a receipt,
and world unload. In-client appearance and cross-server routing still require
runtime activation and a player pass; a unit test is not that evidence.
