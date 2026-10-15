# Independent ARC domain plugins

ARC hosts the domain APIs for three independent public plugin repositories:

| Plugin | Repository | Responsibilities |
| --- | --- | --- |
| ArcMounts 0.1.1 | https://github.com/alexey-va/ArcMounts | Mount ownership, riding, purchase recovery, transfers, care boosts and `/mount` |
| ArcOrigin 0.1.1 | https://github.com/alexey-va/ArcOrigin | Origin scenes, NPC routes, dining, workshops, training, portal anchors and mount yard |
| ArcContracts 0.1.2 | https://github.com/alexey-va/ArcContracts | Resource orders, submissions, journals, reconciliation, contract menus and existing season machinery |

The repositories own their Java API source modules. ARC embeds the published
`ru.ruscrafting.arc:arc-mounts-api`, `arc-origin-api`, and `arc-contracts-api`
artifacts at version 0.1.0. Children use `compileOnly` and never embed another
copy. Bukkit ServicesManager supplies the implementations. An implementation
update that preserves these contracts can be built independently of ARC.
Changing an API requires a new immutable API version and a compatible host.

Each child owns its shaded core implementation modules and lifecycle. The
shared API classes are the deliberate exception. SQL and Redis clients borrow
ArcCore's arc-core 2.7.23 providers; metrics use owner-scoped meters in ARC's registry.
Origin's chunk leases share ARC's existing ticket registry, and its NPC
holograms, auction operations, furniture inspection and payments call typed host
ports. This change makes no measured claim about heap or throughput savings.

## Shared provider host

Starting with ARC 1.4.395, `ArcCore 0.1.1` owns the SQL/Redis providers, the
consumer-metrics service and their `ArcSql*`, `ArcRedis*`, `ArcMetric*` API types.
Other Paper APIs, including visual effects and sidebars, stay in ARC. ArcCore
is built and published independently in the
[ArcCorePlugin repository](https://github.com/alexey-va/ArcCorePlugin). ARC
depends on its self-contained JAR and borrows the existing providers through
`ArcCoreServices`; shutdown of ARC releases its resources without closing the
other plugins' providers.

ARC retains Redis settings under `plugins/ARC/modules/redis.yml`, its Prometheus
registry/exporter and gameplay telemetry. ArcCore adds no configuration, ports,
commands or gameplay. Local framework lifecycle objects remain local. This
split requires no core-library API release or rebuild of existing consumers.

The first rollout installs ArcCore together with ARC 1.4.395 before restarting.
Subsequent compatible changes deploy only the changed plugin's JAR. Updating
ArcCore itself requires a normal server restart; hot-swapping its classloader
while borrowers retain service handles is unsupported. A rollback to older ARC
must also remove ArcCore from the active plugin root to avoid duplicate owners.

CI downloads the pinned ArcCore JAR for the existing archive ownership checks
and real-Paper scenarios. It does not build ArcCore or add a separate release
platform. ArcCore is a provider host, so the
generic feature-consumer manifest baseline (menus, locale and feature runtime)
does not apply; its manifest declares no unused consumer capabilities.

## Configuration and compatibility

All operator data stays under `plugins/ARC`. Configuration paths, database and
Redis keys, journal schemas, item namespaces and receipt formats are retained.
Each menu owner loads only its own layouts/templates from `guis/menus.yml`.
`/arc` retains command metadata and outer permission checks, then delegates the
five Origin command routes and the contracts command to their owners.
`/arc reload` reloads host modules and the registered domain services.

`ResourceContractCommittedEvent` keeps its existing class name, constructor,
getters and Bukkit handler API in ARC's shared API classloader. Existing
ArcRanks listeners therefore observe the same committed-submission event.
Trophy guards in host auction/shop integrations read the same `arc:` PDC
protocol even if the child is unavailable. Authenticated operations HTTP routes
retain their existing read/write gates and reconciliation wire structure.

## Build and release contract

Builds and JVM tests run in GitHub Actions. Each child workflow checks its
standalone tests, storage integration and pinned core-consumer rules, checks
the production archive, and retains the tested JAR. Successful main builds
publish immutable API and runtime artifacts to the existing public Maven
repository. Publication compares every existing coordinate before uploading
and verifies checksums through anonymous readback. It does not create GitHub
releases.

ARC's workflow resolves the tested runtime artifacts, rejects duplicate API,
provider and domain implementation classes, and runs the production combination
on real Paper. ArcContracts additionally publishes separate **test-only**
`ArcContractsContractTestFixture` and `ArcContractsContractCrashFixture` JARs for
the existing integration and seven crash-recovery scenarios. These fixtures
have their own immutable release version, independent of the runtime and API,
and must never be deployed to production. Their private fault-injection runners
execute against ArcContracts' implementation classloader so that another
plugin's Kotlin or core implementation cannot substitute incompatible types.

For the first rollout, deliver ARC 1.4.392, ArcMounts 0.1.0, ArcOrigin 0.1.0,
and ArcContracts 0.1.1 before
activation. Keep ArcDungeons and existing consumers installed. Rollback restores
the matching previous ARC and removes these three child JARs together; mixing
an older monolithic ARC with new children would duplicate handlers. Record
source commit, successful CI, delivered hashes, activation and observed runtime
behavior separately. Protocol smoke checks do not establish native-client
visual acceptance.

## Economy assessment

The move preserves prices, payouts, reward pools, chances, quantities, limits,
cooldowns, multipliers and existing readiness flags. For an identical accepted
operation, intended before/after deltas are separately **0 vault**, **0 tokens**,
**0 EliteMobs crystals**, **0 XP** and **0 items**. Resource contract submissions
retain their current readiness flag; season mutations retain their disabled
readiness flag. The existing EconomyShopGUI SELL-to-contract transition is
unchanged. Source tests and production acceptance must verify provider failure,
payment evidence and recovery behavior rather than treating a successful build
as an economy check.

ArcContracts 0.1.2 requires the arc-core 2.7.23 API/provider bundled in ARC
1.4.393. Deploy them in the same restart batch. Contract gauges use one bounded
2048-series scope and replace that scope when snapshot keys change, so removed
contracts and previous status labels do not remain registered. ArcDungeons
0.1.1 publishes sidebar frames through the full Java-shaped constructor; its
packaged bytecode is checked to avoid a cross-loader Kotlin default marker.
