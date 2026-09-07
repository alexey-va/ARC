# Real Paper command tests

Run `./gradlew plugwrightTest` with Java 25. Gradle downloads Paper 26.1.2,
Node 22.14.0 and Plugwright 2.0.4. The disposable server binds only to
127.0.0.1:25565; do not run another Paper test suite on that port concurrently.
Fixtures live under `build/plugwright` and are recreated for each run. The
official Vault 1.7.3 plugin supplies the API required during ARC bootstrap.
The E2E task starts an isolated `RedisTestService` container with a random
mapped port and copies the resolved RedisEconomy 4.5.12 JAR (SHA-256
`7ccd1c5fbc43ab1a3345f4c9705b71aa39fbbbd457647c1808629bc86884e51f`) into the
disposable server. No production Redis or credentials are used.

These tests exercise blocked commands, namespaced aliases, OP bypass and the
Brigadier command tree actually sent to a Minecraft client, plus a real
Parkour 7.2.8 course created in the synthetic flat world. The Parkour test
verifies ARC HUD packets across join, checkpoint, death recovery and finish,
plus the Origin
contract GUI against real RedisEconomy storage. Existing JVM tests retain
their separate coverage. Network/AI modules may log unavailable optional
dependencies; the suite does not assert that every optional module is ready.
The disposable order enables bounded dynamic pricing: the first quote is
checked against the actual balance delta and the next quote must decrease after
the accepted supply is persisted. Disabled command submission and a confirmation
click outside Origin must preserve both real items and the RedisEconomy balance.
The world-change check waits for the actual client dimension before opening the GUI.

The OP transition is a regression check: pruning with a cached restricted
policy during Paper's async pass removed commands before the fresh permission
check could restore them. Filtering must use the synchronous pass, as allowed
by the [Paper event contract](https://jd.papermc.io/paper/26.1.2/com/destroystokyo/paper/event/brigadier/AsyncPlayerSendCommandsEvent.html).

The GitHub Actions `build` workflow runs MySQL and three Paper profiles alongside
unit tests on matching pushes/PRs and on manual dispatch, including E2E-only
changes. The normal profile includes contracts and Parkour; an isolated Parkour
profile and the 1.21.11 cleanup profile provide separate evidence. Each profile
uploads distinctly named runner output and Paper logs on success or failure.
Do not run the container-backed contract fixture on the owner’s local workstation.

For the isolated Parkour acceptance run, use
`TEST_TIMEOUT=60000 python3 /tmp/arc-plugwright-run.py ./gradlew --no-daemon plugwrightTest -PparkourE2e=true`.
This profile keeps Redis disabled and runs only `parkour-real-paper.spec.js`.
Course creation already records checkpoint 0. The test adds only two points
ahead, waits for each plate to release after creation, and physically walks onto
it. No administrative checkpoint advancement is used. The native plate state,
player position and ARC HUD packets are observed; generated course JSON is
retained with the CI artifacts.

## Entity cleanup acceptance

CI also runs `entity-cleanup.spec.js` on Paper 1.21.11 using
`./gradlew plugwrightTest -PcleanupE2e=true -Pe2eMinecraftVersion=1.21.11` and stages
`fixtures/entity-cleanup.yml`. The fixture enables only the configured mob
equipment rule with a 40-tick remaining lifetime for vanilla zombie/skeleton drops in
the disposable `rc_origin_spawn` world.
The scenario asserts native item age through server-side scoreboard predicates,
then covers ordinary mob gear, player drops, custom components, pickup-history
trust loss, two same-tick deaths and `/arc reload`. This focused profile disables
Redis and filters to cleanup scenarios; the normal suite retains real Redis
and contract acceptance. Run integration servers in CI, as required by the
operations runtime-delivery policy. Restart provenance invalidation is also
covered by the JVM event tests.

The market retirement scenario verifies that ARC loads while investment command
roots and the invest subcommand are absent from an operator's command tree.

## Resource-contract process crashes

The dedicated CI profile runs on Paper 1.21.11 with
`ARC_CONTRACT_CRASH_FIXTURE=1 ./gradlew plugwrightTest -PcontractCrashE2e=true -Pe2eMinecraftVersion=1.21.11`
and `TEST_TIMEOUT=900000`. Do not run this container/server profile on the local
workstation. `contractCrashFixtureJar` is a separate test artifact and is never
included in the production shadow JAR.

The fixture decorates the existing coordinator ports, preserving the real
Redis repositories, native inventory persistence and RedisEconomy adapter. It
halts the JVM after native removal, durable escrow, durable payment rejection,
provider success, durable PAID and durable contract state. The test starts a
replacement Paper process against the same directory and still-live disposable
Redis, reconnects the same offline UUID, and checks inventory, provider balance,
journal status, held quota and spent budget. A final restart must be idempotent.

Plugwright 2.0.4 exposes no restart API. This one-spec profile therefore owns its
replacement child processes, rebinds the public `ServerWrapper` and always stops
the final child. It never re-runs `plugwrightClean` between process generations.
Per-generation logs and synthetic journal snapshots are retained as CI artifacts.
This proves process-crash boundaries; it does not claim power-loss/fsync safety
for Minecraft or the external economy provider.
