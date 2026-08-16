# AGENTS.md — ARC (Paper plugin)

**Architecture canon:** [`~/IdeaProjects/arc-core/AGENTS.md`](../arc-core/AGENTS.md) or [github.com/alexey-va/arc-core](https://github.com/alexey-va/arc-core/blob/main/AGENTS.md) — read before structural changes.

## Paper-only (stays in this repo)

- **Bootstrap:** `PaperArcRuntime.installScheduling(this)` in `ARC.kt` **before** `ModuleRegistry.initAll()`
- **Event DSL** — `EventDsl.kt`, Bukkit listeners, gameplay modules
- **GuiDsl** — [`src/main/kotlin/ru/arc/gui/GUI.md`](src/main/kotlin/ru/arc/gui/GUI.md)
- **Commands** — [`src/main/kotlin/ru/arc/commands/arc/COMMANDS.md`](src/main/kotlin/ru/arc/commands/arc/COMMANDS.md)
- **Ops HTTP** — [`src/main/kotlin/ru/arc/ops/AGENTS.md`](src/main/kotlin/ru/arc/ops/AGENTS.md)

Chat mode commands and shared state exist in ARC. ARC adds CMI's `!` routing
prefix at `LOWEST` on Paper's `AsyncChatDecorateEvent`, which is emitted before
CMI's `AsyncChatEvent` shout handler. Do not move the prefix back to
`AsyncChatEvent`: CMI also listens at `LOWEST`, so registration order can make
the change too late. ProxyARC may derive the same prefixed text logically for
routing and Discord/Telegram bridges, but must never replace the Velocity
`PlayerChatEvent`: changing signed chat disconnects modern clients.

## Runtime & deploy

- Production `plugins/ARC/schematics` is a root symlink, not a per-server
  directory. Inspect it with `mc_remote_list(..., follow_symlink=true)`; a
  normal listing can falsely report it as empty. Spawn and survival resolve to
  the shared catalog, while parkour can resolve to a different/empty target.
  Re-list the intended node before issuing a BuildBook.

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
