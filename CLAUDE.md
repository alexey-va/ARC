# CLAUDE.md

ARC — McFine Paper plugin (Kotlin/Java 25). Treasure hunts, stock, board, cross-server via Redis.

**Architecture, boundaries, migration:** [`AGENTS.md`](AGENTS.md) → [`arc-core/AGENTS.md`](../arc-core/AGENTS.md)

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew test
./gradlew shadowJar   # → build/libs/ARC-*.jar
```

## RusCrafting runtime (`~/RusCrafting/ruscrafting-ops`)

| Doc | Purpose |
|-----|---------|
| `mcserver/TASKS.md` | Current tasks — read after chat reset |
| `mcserver/AGENTS.md` | Deploy, MCP, server roles |
| `mcserver/classic/plugins/ARC/AGENTS.md` | Runtime YAML, ops-http |
| `src/main/kotlin/ru/arc/ops/AGENTS.md` | Item Ops, CMI kits API |

Deploy: `cd ~/RusCrafting/ruscrafting-ops && ./scripts/mc arc classic classic_survival`

Patterns (Config `get()`, Kotest+MockK, PluginModule, Tasks.*): **arc-core/AGENTS.md** — not duplicated here.
