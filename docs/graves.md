# GravesX integration

ARC supplies `/graves`, native dialogs, a conditional first main-menu button,
personal bossbars and nearby owner-only block outlines. GravesX 2026.4.9.2
owns grave creation, records, inventories, expiry and safe teleportation.
Spawn's keep-inventory/death policy is not changed.

`modules/graves.yml` sets a positive `teleport.default-fee` (100 Vault coins).
Only `teleport.free-permission` (`arc.graves.teleport.free`, default false)
waives it. No group receives that node from this change. Walking to a grave
remains free. The confirmation displays the quote; destination permission,
ownership, grave lifetime and price are checked again before teleport and debit.
The balance assessment is `graves-economy-20261015.json`.

The compass defaults on, targets the selected or most recent local-world grave,
and can be disabled or retargeted in the same menu. Settings persist in Redis
across backend changes. The strip uses `arc-dungeons-api`'s `CompassStrip`,
also used by the EliteMobs compass. A remote/world target shows its server/world
instead of a misleading bearing. A menu reminder remains when tracking is off.
Glows are private packet displays inside the grave block's bounding box;
they use already-loaded chunks and disappear outside the configured radius.

## Cross-server and payment boundaries

GravesX H2 stores and API caches are process-local. ARC publishes leased full
read-only snapshots to `arc:graves:v1:servers` every two seconds. A missing node
expires after 15 seconds; an empty live node publishes an empty snapshot.
Names and collections are bounded; the current ceiling is 512 graves and
32,768 item summaries per server. This is a presentation capacity, not a
GravesX inventory or creation limit. A rejected publication is logged and
the previous lease expires; no records are deleted.

Remote visits use `arc:graves:v1:travel`: destination acknowledgement is
correlated to the player, grave, source, destination, quote and 10-second
request. A destination join has 30 seconds to arrive and pass validation.
When HuskSync is enabled, `BukkitSyncCompleteEvent` must succeed before the
native teleport; timeout never bypasses inventory sync. Only the destination
can charge. Source transfer failure does not charge.

Paid local visits write a durable intent under `data/grave-payments` before
the native teleport starts. GravesX's successful post-event permits one
Vault debit. A later canceled post-event refunds a known successful debit.
Canceled/unsafe/expired attempts and missing post-events are never charged;
admissions expire after 120 seconds. Old GravesX teleport entry points are
redirected to ARC's quote flow instead of bypassing payment.

An ambiguous provider exception or unresolved journal record blocks further
paid visits for that player. It is never automatically retried or refunded.
Operators must reconcile the logged operation UUID against provider history
before any manual repair; preserve the original record as evidence. The
integration does not mutate inventories or extend grave lifetimes.

## Validation scope

Focused tests cover dialog navigation/stale quotes, conditional root placement,
compass projection, cross-server discovery/ACK/expiry, persistent tracking,
charge/refund/timeout and durable-write shutdown races. They do not prove
native Minecraft rendering or a real two-backend player transfer. Runtime
activation and ordinary-client acceptance are recorded separately.
