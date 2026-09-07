# Real Paper command tests

Run `./gradlew plugwrightTest` with Java 25. Gradle downloads Paper 26.1.2,
Node 22.14.0 and Plugwright 2.0.4. The disposable server binds only to
127.0.0.1:25565; do not run another Paper test suite on that port concurrently.
Fixtures live under `build/plugwright` and are recreated for each run. The
official Vault 1.7.3 plugin supplies the API required during ARC bootstrap.

These tests exercise blocked commands, namespaced aliases, OP bypass and the
Brigadier command tree actually sent to a Minecraft client. Redis is disabled
in the fixture; network chat, persistence and third-party integrations are not
covered by this suite. Existing JVM tests retain their separate coverage.
Network/AI modules can log unavailable Redis dependencies in this isolated
fixture; the suite does not assert that every optional module is ready.

The OP transition is a regression check: pruning with a cached restricted
policy during Paper's async pass removed commands before the fresh permission
check could restore them. Filtering must use the synchronous pass, as allowed
by the [Paper event contract](https://jd.papermc.io/paper/26.1.2/com/destroystokyo/paper/event/brigadier/AsyncPlayerSendCommandsEvent.html).

GitHub Actions runs the suite independently of unit/MySQL tests and uploads the
runner output and Paper logs on success or failure.
