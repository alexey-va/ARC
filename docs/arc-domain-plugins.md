# Independent ARC domain plugins

ARC 1.4.392 hosts the shared APIs for three independent public plugin repositories:

| Plugin | Repository | Responsibilities |
| --- | --- | --- |
| ArcMounts 0.1.0 | https://github.com/alexey-va/ArcMounts | Mount ownership, riding, purchase recovery, transfers, care boosts and `/mount` |
| ArcOrigin 0.1.0 | https://github.com/alexey-va/ArcOrigin | Origin scenes, NPC routes, dining, workshops, training, portal anchors and mount yard |
| ArcContracts 0.1.0 | https://github.com/alexey-va/ArcContracts | Resource orders, submissions, journals, reconciliation, contract menus and existing season machinery |

The repositories own their Java API source modules. ARC embeds the published
`ru.ruscrafting.arc:arc-mounts-api`, `arc-origin-api`, and `arc-contracts-api`
artifacts at version 0.1.0. Children use `compileOnly` and never embed another
copy. Bukkit ServicesManager supplies the implementations. An implementation
update that preserves these contracts can be built independently of ARC.
Changing an API requires a new immutable API version and a compatible host.

Each child owns its shaded core implementation modules and lifecycle. The
shared API classes are the deliberate exception. SQL and Redis clients borrow
ARC's arc-core 2.7.22 providers; metrics use owner-scoped meters in ARC's registry.
Origin's chunk leases share ARC's existing ticket registry, and its NPC
holograms, auction operations, furniture inspection and payments call typed host
ports. This change makes no measured claim about heap or throughput savings.

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
must never be deployed to production.

For the first rollout, deliver ARC 1.4.392 and all three 0.1.0 child JARs before
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
