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
Brigadier command tree actually sent to a Minecraft client, plus the Origin
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

Run the GitHub Actions `build` workflow manually with `heavy_tests=true` to run
MySQL and both Paper profiles alongside unit tests. Ordinary pushes run only the
unit/package job; E2E-only paths do not trigger it. The workflow uploads runner
output and Paper logs on success or failure. Do not run the container-backed
contract fixture on the owner’s local workstation.

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
