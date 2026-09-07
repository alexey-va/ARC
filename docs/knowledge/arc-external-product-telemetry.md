# Optional external product telemetry

ARC owns collection, network transport, retention, reports and session timing.
Most Paper consumers declare `softdepend: [ARC]` (alongside existing dependencies)
and call the public static bridges through reflection. They remain independently
usable when ARC is absent. ArcEvents is the load-order exception: its arena
generators require it before My_Worlds, while ARC loads after My_Worlds. It
resolves the enabled ARC plugin's own classloader at event completion and has
no ARC ordering edge. This preserves optional telemetry without a Paper cycle.
A telemetry failure must never retry a payout, roll
back gameplay, or prevent a reward from being delivered.

## Product API

`ru.arc.metrics.ExternalProductTelemetryBridge.record(UUID, source, feature,
outcome, action, operationId)` accepts existing closed product labels. An
outcome may name its feature; actions cannot be mixed with either. Final
operation IDs, including all prefixes, must match `[A-Za-z0-9_.:-]{1,80}`.
Ranks contract acceptance/completion and Builder durable build completion use this shared API.

`recordEvent(UUID, source, event, operationId)` accepts only matching pairs in
`ExternalProductSource` and `ExternalProductEvent`. Raw IDs match
`[A-Za-z0-9_.:-]{1,256}` and are hashed to SHA-256 before transport. Use the
durable claim, voucher, match or delivery identity; two legitimate events for
one voucher (purchase and activation) are distinct. Counters do not accept
arbitrary labels, maps, money amounts, currencies or duration.

| Consumer | Events emitted after successful gameplay |
| --- | --- |
| ArcFarms | Farm reward delivery completed |
| ArcVotes | Vote reward components completed and grant recorded |
| ArcRanks | Rank promoted; weekly kit claimed; shared contract signals |
| ArcBuilder | Durable build completed (shared autobuild outcome) |
| ArcEcoJobs | Boost purchase delivered; voucher activated |
| ArcDuels | Completed duel for each participant |
| ArcEvents | Event completion |
| ArcGiveaways | Item prize delivery completed |
| Trails | Trail preference changed from off to on |

The versioned `arc-product-external-v1` topic uses the canonical bounded JSON
codec and validated Redis topic. Player UUIDs are pseudonymized. Incoming
source/event pairs, origins, ID sizes and timestamps are validated; messages
older than 32 days or more than five minutes in the future are rejected.
Counters persist in the existing product-interest daily store and appear in
its report as `externalEvents`, and in Prometheus as
`arc_product_external_events` / `arc_product_external_players` with closed
window/source/event labels. Known QA sessions are excluded.

Replay suppression is bounded and best effort: 24 hours, 4096 recent identities
per bridge/topic process. It resets on restart and is not an exactly-once
financial journal. Disabled collection, a crash before persistence, unavailable
Redis or consumer downtime can lose observations. API success means local
acceptance, not confirmed delivery to every node. Compare financial volumes
with the economy ledger, never derive them by multiplying these counters by
catalog prices. `activeSeconds` and session boundaries remain owned by ARC's
existing `ProductInterestTelemetry`; consumers introduce no timers or sessions.

## Economy attribution

`ru.arc.audit.ExternalEconomyAuditBridge.markExternalReward` marks an expected
provider operation immediately before a reward. Supported source/action pairs
are `voting/vote_reward`, `ranks/contract_reward`, and `farms/farm_reward`.
Pass the real currency and durable component identity. Cancel the marker only
on explicit failure or an exception; never repeat an ambiguous payment.
The existing provider listener records actual mint/burn and matches player,
source, amount and currency. Unknown callers cannot consume these markers.
Historical ledger rows are not relabelled. Vault coins and premium tokens
remain separate units.

## Packaging checks

Do not shade ARC itself. If a consumer relocates its bundled `ru.arc` core,
Shadow also rewrites fully qualified string constants. Build the optional
external API name at runtime (as Trails does) and verify the packaged JAR,
not just unshaded unit tests. Default plugin classloader visibility requires the soft
dependency even when no compile-time API dependency is added. A plugin with a
conflicting early world-generator requirement must resolve ARC through ARC's
explicit classloader instead. Check loadbefore edges and provider aliases as
well as softdepend: the 2026-09-07 ArcEvents → My_Worlds → ARC cycle was only
visible with the full server plugin graph, not the isolated ARC Paper fixture.
