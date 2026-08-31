# ARC Minecraft Plugin

McFine **Paper/Purpur** plugin — treasure hunts, stock market, board, and cross-server Redis.

**Architecture for agents:** [`AGENTS.md`](AGENTS.md) → [arc-core/AGENTS.md](../arc-core/AGENTS.md)

## Requirements

- **Java 25** (Temurin)
- **Gradle 9.2.1**
- Published `arc-core 2.1.3` artifacts from the public RusCrafting repository

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew test
./gradlew shadowJar
```

Deployable output: `build/libs/ARC-1.0.jar` (shadowed dependencies). The
non-deployable plain archive is written separately as `ARC-1.0-plain.jar`.

The default build is standalone and does not require an `arc-core` checkout.
For coordinated local development, opt into source substitution explicitly:

```bash
./gradlew test shadowJar -ParcCoreDir=/absolute/path/to/arc-core
```

## Deploy

Runtime configs: [ruscrafting-ops](https://github.com/alexey-va/ruscrafting-ops) — `classic/plugins/ARC/`, `classic_survival/...`

```bash
cd ~/RusCrafting/ruscrafting-ops && ./scripts/mc arc classic classic_survival
```

## Features

Treasure hunts, stock simulation, auction (Redis pub/sub), x-server
announcements, and native cross-server mounts with progression, cosmetics,
abilities, and player tuning.

Mount configuration and permission-state contract: [`docs/mounts.md`](docs/mounts.md).

Canonical ARC permission namespace and migration map: [`docs/permissions.md`](docs/permissions.md).

## Dependencies

Dependencies are enabled per module. Optional integrations include Citizens,
ItemsAdder, Jobs, BetterStructures, EliteMobs, and PlaceholderAPI. Survival
building assistance is owned by the standalone ArcBuilder repository.

### Selective PlaceholderAPI cache

Expensive player or server placeholders can be cached explicitly without
enabling a global PlaceholderAPI cache:

```text
%arc_cache_<ttl-seconds>_<inner-placeholder-without-percent-signs>%
%arc_cache_30_cmi_user_stats_PlayTime%
%arc_cache_plain_30_cmi_user_stats_PlayTime%
```

The TTL must be an integer from 1 to 300 seconds. Cache entries are isolated by
player UUID (or by a separate server context), TTL, and the exact inner
placeholder. Relational (`rel_...`) and nested `arc_cache_...` placeholders are
intentionally unsupported. A miss is resolved synchronously on the caller's
thread, so this helper does not make an unsafe third-party expansion safe to
call asynchronously. The inner placeholder is limited to 160 characters;
results longer than 2048 characters and unknown results are returned but not
cached. Concurrent misses may resolve the same value more than once so ARC never
blocks one expansion thread on another expansion's threading requirements.

The opt-in `cache_plain` form uses the same cache contract but removes legacy
Minecraft section-sign formatting codes (`§0`–`§f`, `§k`–`§o`, `§r`, and
`§x`) from a resolved value before caching it. It is intended for MiniMessage
surfaces that otherwise truncate multi-part legacy-formatted values. The
ordinary `cache` form continues to preserve the expansion result exactly.

## License

MIT — see [LICENSE](LICENSE).
