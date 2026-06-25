# ARC Minecraft Plugin

McFine **Paper/Purpur** plugin — treasure hunts, stock market, board, farms, cross-server Redis.

**Architecture for agents:** [`AGENTS.md`](AGENTS.md) → [arc-core/AGENTS.md](../arc-core/AGENTS.md)

## Requirements

- **Java 25** (Temurin)
- **Gradle** + composite build with [arc-core](https://github.com/alexey-va/arc-core)

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew test
./gradlew shadowJar
```

Output: `build/libs/ARC-*.jar` (shadowed dependencies)

## Deploy

Runtime configs: [mcserver](https://github.com/alexey-va/arserver-plugins) — `classic/plugins/ARC/`, `classic_survival/...`

```bash
cd ~/mcserver && ./scripts/mc arc classic classic_survival
```

## Features

Treasure hunts, auto-building, stock simulation, auction (Redis pub/sub), x-server announcements, farms/mines.

## Dependencies

Vault, Redis, WorldEdit/WorldGuard; optional: Citizens, ItemsAdder, Jobs, BetterStructures, EliteMobs, PlaceholderAPI.

## License

MIT — see [LICENSE](LICENSE).
