# Optional external product telemetry

ARC owns collection, network transport, retention, reports and session timing.
Paper consumers declare `softdepend: [ARC]` (alongside existing dependencies),
compile against the dependency-free `arc-core-paper-api`, and resolve one
`ArcTelemetryProvider` from Bukkit's service registry. ARC registers the provider
after its modules initialize and unregisters it before shutdown. Consumers remain
independently usable when ARC is absent: a missing provider simply skips the signal.
A telemetry failure must never retry a payout, roll
back gameplay, or prevent a reward from being delivered.

## Product API

`ArcTelemetryProvider.record(UUID, source, feature,
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
| ArcEcoJobs | Boost purchase delivered; voucher activated; AFK/farm block notices |
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

## Profession work observations

`ArcTelemetryProvider.recordJobWork(UUID, job)` observes an accepted
EcoJobs XP event. `breakJobWork(UUID)` ends continuity without adding duration.
These are optional, failure-isolated APIs; neither method changes XP or money.
`JobWorkObservation`/`JobWorkClock` own interval semantics in ARC, while the
consumer owns native eligibility and AFK detection. There is no second ledger.

The pinned EcoJobs/libreforge 2026.33 counter invokes `JobXPAccumulator.accept`
only after its filters and conditions; the accumulator checks active membership,
AFK policy and game mode, then immediately emits `PlayerJobExpGainEvent`.
Observe non-cancelled positive finite XP at MONITOR, independently of whether
money paid on that action. Native `givexp` uses `giveExactJobExperience` and
bypasses this event. External `giveJobExperience` API calls and libreforge's
`give_job_xp` effect can also emit the event: this is an **accepted XP-event
interval proxy**, not proof of a physical action or human attention.

ARC accepts at most one observation per second per player/profession. The first
has zero duration; subsequent intervals connect observations only when their
gap is at most 30 seconds. Long gaps restart at zero. AFK/blocked work, world
change, logout, consumer shutdown and period reset break continuity. Sub-second
activity tails and sparse work are not reconstructed. No time is inferred from
menus, placeholders, selected jobs, ordinary movement or money amount.

The separate `arc-product-job-work-v1` topic preserves the external-event v1
contract during rolling upgrades. It uses the shared bounded codec, origin
checks and bounded replay suppression. Local known QA sessions are excluded
before publication. Daily `jobWork` rows use the existing product file, privacy,
retention, eviction, atomic save and reset-period lifecycle. Intervals split at
calendar midnight using the configured timezone, and clip at the measurement
boundary. Monotonic interval union avoids double counting; unseen late prefixes
are conservatively omitted and exposed as `lateObservations`. Network delivery
and crash recovery retain the existing best-effort telemetry limitations.

The authenticated product report returns `jobWork.professions` with participants,
`firstObservedAt`, `observedMillis`, sampled `observations`, and
`lateObservations`. `coversAllWorkingTime=false` is explicit regardless of the
parent product report completeness flag. `jobWorkLocalClock` separately exposes
this node’s bounded-cursor evictions and clock regressions since startup/reset.
`no_observations` does not mean zero potential income.
Profession intervals can overlap, so their times must not be summed into player
hours. A measured income rate still requires the same player set, profession,
currency, calendar/time window and observation coverage in the money ledger;
this report deliberately does not divide whole-day payouts by partial observed
time. Do not use it as an automatic pricing input.

Focused verification: `./gradlew test --tests ru.arc.metrics.JobWorkObservationTest`.
The native producer and actual optional bridge are exercised by ArcEcoJobs'
paired Paper suite with `-Pe2eArcJar=/absolute/path/to/ARC.jar`.

## Economy attribution

`ArcTelemetryProvider.markExternalReward` marks an expected
provider operation immediately before a reward. Supported source/action pairs
are `voting/vote_reward`, `ranks/contract_reward`, and `farms/farm_reward`.
Pass the real currency and durable component identity. Call `cancelAudit` only
on explicit failure or an exception; never repeat an ambiguous payment.
The existing provider listener records actual mint/burn and matches player,
source, amount and currency. Unknown callers cannot consume these markers.
Historical ledger rows are not relabelled. Vault coins and premium tokens
remain separate units.

## Packaging checks

Do not shade ARC itself or the shared API into consumers. Keep
`arc-core-paper-api` compile-only and declare ARC as a soft dependency so every
plugin resolves the provider interface from ARC's classloader when available.
Verify the packaged JAR, not just unshaded unit tests. Check load-order edges as
well as the soft dependency against the full server plugin graph.
