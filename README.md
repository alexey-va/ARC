# ARC Minecraft Plugin

McFine **Paper/Purpur** plugin — treasure hunts, stock market, board, and cross-server Redis.

**Architecture for agents:** [`AGENTS.md`](AGENTS.md) → [arc-core/AGENTS.md](../arc-core/AGENTS.md)

## Requirements

- **Java 25** (Temurin)
- **Gradle 9.2.1**
- Published `arc-core 2.0.0` artifacts from the public RusCrafting repository

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

Runtime configs: [mcserver](https://github.com/alexey-va/arserver-plugins) — `classic/plugins/ARC/`, `classic_survival/...`

```bash
cd ~/mcserver && ./scripts/mc arc classic classic_survival
```

## Features

Treasure hunts, auto-building, survival-safe transactional builder tools, stock simulation, auction (Redis pub/sub), x-server announcements, and native cross-server mounts with progression, cosmetics, abilities, and player tuning.

Mount configuration and permission-state contract: [`docs/mounts.md`](docs/mounts.md).

Canonical ARC permission namespace and migration map: [`docs/permissions.md`](docs/permissions.md).

Builder tools player and recovery contract: [`src/main/kotlin/ru/arc/buildertools/README.md`](src/main/kotlin/ru/arc/buildertools/README.md).

## Dependencies

Vault, Redis, WorldEdit/WorldGuard; optional: Citizens, ItemsAdder, Jobs, BetterStructures, EliteMobs, PlaceholderAPI.

## License

MIT — see [LICENSE](LICENSE).
