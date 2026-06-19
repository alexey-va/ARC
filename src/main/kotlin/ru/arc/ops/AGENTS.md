# AGENTS.md — ru.arc.ops

HTTP ops API и ItemSpec для McFine MCP. **Runtime configs:** `mcserver/classic/plugins/ARC/modules/ops-http.yml`, `item-presets.yml`.

## Purpose

- Authenticated HTTP on `127.0.0.1:25823` (spawn) / `:25824` (survival)
- Item build/give from JSON presets
- **CMI blob export** via Paper NBT serialization

## Key classes

| Class | Role |
|-------|------|
| `OpsHttpModule` | Lifecycle, bind server |
| `OpsHttpServer` | Routes under `/ops/` |
| `OpsHttpHandlers` | Console, broadcast, reload, … |
| `OpsItemHandlers` | inventory, give, preview, **cmiBlob\*** |
| `OpsItemSpec` | JSON → ItemStack (MiniMessage, NBT, customData) |
| `ItemPresets` | Load `item-presets.yml` bundles |
| `CmiItemCodec` | `serializeAsBytes()` → gzip → base64 |

## CmiItemCodec

CMI `!!binary` = **gzip(base64(Paper item NBT))**. Same as kiteditor.

- `encode(stack, displayAmount=1)` — kit icons always amount **1**
- `decode(blob)` — round-trip / debug

**Must run on main thread** — use `OpsBukkitSync.call { }` in handlers.

## Routes (items)

```
GET  /ops/item/cmi-blob/presets
GET  /ops/item/cmi-blob/preset/{name}?amount=1
POST /ops/item/cmi-blob          — ItemSpec | {preset} | {presets:[]}
POST /ops/item/preview
POST /ops/player/{name}/give
GET  /ops/player/{name}/inventory
```

Guard: `OpsHttpConfig.itemsReadEnabled` / `itemsGiveEnabled`.

## Extension: new endpoint

1. Handler in `OpsItemHandlers` or `OpsHttpHandlers`
2. Route in `OpsHttpServer.route()`
3. List in `routes()` helper
4. Comment in `src/main/resources/modules/ops-http.yml`
5. MCP tool in `mcserver/scripts/mcp-server/server.py` if needed
6. Test in `src/test/kotlin/ru/arc/ops/` (Kotest + MockBukkit)

## Tests

`CmiItemCodecTest` — round-trip encode/decode. MockBukkit cannot decode real production blobs from live CMI.

## Deploy

```bash
./gradlew shadowJar
cd mcserver && ./scripts/mc arc classic
```
