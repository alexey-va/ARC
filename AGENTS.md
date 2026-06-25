# AGENTS.md — ARC (Paper plugin)

**Architecture canon:** [`~/IdeaProjects/arc-core/AGENTS.md`](../arc-core/AGENTS.md) or [github.com/alexey-va/arc-core](https://github.com/alexey-va/arc-core/blob/main/AGENTS.md) — read before structural changes.

## Paper-only (stays in this repo)

- **Bootstrap:** `PaperArcRuntime.installScheduling(this)` in `ARC.kt` **before** `ModuleRegistry.initAll()`
- **Event DSL** — `EventDsl.kt`, Bukkit listeners, gameplay modules
- **GuiDsl** — [`src/main/kotlin/ru/arc/gui/GUI.md`](src/main/kotlin/ru/arc/gui/GUI.md)
- **Commands** — [`src/main/kotlin/ru/arc/commands/arc/COMMANDS.md`](src/main/kotlin/ru/arc/commands/arc/COMMANDS.md)
- **Ops HTTP** — [`src/main/kotlin/ru/arc/ops/AGENTS.md`](src/main/kotlin/ru/arc/ops/AGENTS.md)

## Runtime & deploy

| Doc | Purpose |
|-----|---------|
| `~/mcserver/TASKS.md` | Current tasks |
| `~/mcserver/AGENTS.md` | Deploy, MCP |
| `~/mcserver/classic/plugins/ARC/AGENTS.md` | Runtime YAML (spawn) |

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew test                    # all tests
./gradlew shadowJar               # build/libs/ARC-*.jar
cd ~/mcserver && ./scripts/mc arc classic classic_survival
```

## Dependencies

`arc-core` + `arc-core-paper` + `arc-core-logging` + `arc-core-redis` via composite build (`includeBuild("../arc-core")`).
