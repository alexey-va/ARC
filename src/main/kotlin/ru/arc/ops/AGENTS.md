# AGENTS.md — ru.arc.ops

HTTP ops API и ItemSpec для RusCrafting MCP. **Runtime configs:** `mcserver/classic/plugins/ARC/modules/ops-http.yml`, `item-presets.yml`.

## Purpose

- Authenticated HTTP on `127.0.0.1:25823` (spawn) / `:25824` (survival)
- Item build/give from JSON presets
- Item preset/bundle read/preview/write/delete and native give
- CMI kit read/preview/write through the CMI Java API and ordinary ItemSpec JSON
- CMI hologram list/preview/presence-aware upsert/delete through the native CMI API
- Scheduled-command read/preview/write/delete through ARC's native manager
- Location-pool read/preview/write/delete through native ARC storage
- Treasure/reward-pool read/preview/write/delete through native `TreasureManager`
- Citizens NPC list/preview/upsert/delete through the native Citizens API
- Citizens marker publication into BlueMap through BlueMap API 2.7.7
- One read-only health overview across every managed content catalog
- Native LuckPerms group/user reads, effective checks, reviewed point changes,
  and journaled migrations without console commands or direct storage writes

## Key classes

| Class | Role |
|-------|------|
| `OpsHttpModule` | Lifecycle, bind server |
| `OpsHttpServer` | Routes under `/ops/` |
| `OpsHttpHandlers` | Console, broadcast, reload, … |
| `OpsItemHandlers` | inventory, give, preview |
| `OpsCmiKitHandlers` | CMI `KitsManager` read/preview/upsert + `safeSave()` |
| `OpsCmiHologramHandlers` | CMI `HologramManager` read/preview/upsert/delete + native persistence |
| `OpsScheduledCommandHandlers` | Structured schedule list/preview/upsert/delete |
| `OpsLocationPoolHandlers` | Stable weighted coordinates over native location pools |
| `OpsTreasurePoolHandlers` | Strict reward schema over native treasure pools |
| `OpsContentHealthHandlers` | Isolated cross-catalog health summary |
| `OpsEconomyAuditHandlers` | Read-only persisted economy ledger summary and top balances |
| `BankAuditModule` | Single-leader Bank supply snapshots, aggregate metrics, and bounded account-change evidence |
| `OpsNpcHandlers` | Citizens list/placement validation/gated mutations |
| `BlueMapNpcMarkers` | Dynamic Citizens POI layer for each BlueMap world |
| `OpsItemSpec` | JSON → ItemStack (MiniMessage, NBT, customData) |
| `ItemPresets` | Native runtime and atomic persistence for `item-presets.yml` |
| `OpsItemPresetHandlers` | Strict catalog/preview/upsert/delete/give boundary |
| `OpsLuckPermsHandlers` | Typed native LuckPerms read/check/preview/apply/migration boundary |

## LuckPerms control plane

Reads use normalized persisted direct nodes; effective checks report exact
unexpired direct/inherited matches separately. Point writes require a one-time
review token, idempotency key, fresh digest, save/reload verification, and
spawn leadership. Migrations journal under `plugins/ARC/data/permission-migrations/`;
that runtime state is never tracked or deployed.

Never add raw commands, Bukkit offline UUID generation, `clear`, group/user
delete, direct SQL, unbounded user listing, or a generic raw HTTP route.

## Item presets (canonical)

```
GET    /ops/item-presets[/{id}]
POST   /ops/item-presets/preview
PUT    /ops/item-presets/{id}
DELETE /ops/item-presets/{id}
POST   /ops/player/{name}/give-preset
```

The public schema is either `{type:"preset", item:ItemSpec}` or
`{type:"bundle", items:[{preset,amount}]}`; `amount:"scaled"` uses the amount
supplied at give time. ARC is the only parser and resolver of
`item-presets.yml`. Do not add an MCP-side YAML reader, cache, alias fields, or
parallel preset registry. Preview validates the strict schema and builds every
resolved ItemStack. Writes use `Config.setStructured` plus `saveStrict`, reject
unsafe IDs and dangling bundle references, and preserve catalog integrity on
delete. Reads/preview require `item-presets-read-enabled`; PUT/DELETE require
`item-presets-write-enabled` and explicit production authorization. Give uses
the separate `items-give-enabled` mutation gate.

## CMI kits (canonical)

New kit operations use:

```
GET  /ops/cmi/kits[/{name}]
POST /ops/cmi/kits/preview
PUT  /ops/cmi/kits/{name}
```

Callers send ItemSpec objects. ARC builds Bukkit `ItemStack`; CMI owns runtime
cache and persistence. Never make MCP callers encode, decode, or patch
`!!binary`. Writes require `cmi-kits-write-enabled: true` and explicit
production authorization. The old blob codec and YAML generator were removed;
do not recreate an alternate kit-management path.

## CMI holograms (canonical)

```
GET    /ops/cmi/holograms[/{name}]?world=&limit=
POST   /ops/cmi/holograms/preview
PUT    /ops/cmi/holograms/{name}
DELETE /ops/cmi/holograms/{name}
```

The public HologramSpec is presence-aware: omitted root and nested fields
preserve current values. It covers the complete persistent CMI 9.8.6.4 editor
surface: location, display type, all lines/pages, ranges, intervals, spacing,
global click commands, permission/LOS flags, TextDisplay properties,
icon/board transforms, interaction bounds/particles, pagination, and
fade/rotation animations. CText click actions stay embedded in `lines`, as CMI
expects. Creation requires a complete loaded-world location. ARC calls CMI's
`HologramManager` and hologram persistence methods; never edit
`CMI/Saves/Holograms.yml` from MCP. Read/preview require
`cmi-holograms-read-enabled`; writes and deletes require
`cmi-holograms-write-enabled` plus explicit production authorization.

## Scheduled commands (canonical)

```
GET    /ops/scheduled-commands[/{id}]
POST   /ops/scheduled-commands/preview
PUT    /ops/scheduled-commands/{id}
DELETE /ops/scheduled-commands/{id}
```

The payload is a complete structured definition with `command`, `servers`, and
one of `interval`, `daily`, `weekly`, or `cron` schedules. ARC validates the
same `ScheduledCommandDraft` used by the in-game editor and persists through
`ScheduledCommandsManager`; callers never patch YAML paths or dispatch a
console command. Reads/preview use `scheduled-commands-read-enabled`. Writes
and deletes require `scheduled-commands-write-enabled: true` plus explicit
production authorization. There is no run-now endpoint in this content API.

## Location pools (canonical)

```
GET    /ops/location-pools[/{id}]
POST   /ops/location-pools/preview
PUT    /ops/location-pools/{id}
DELETE /ops/location-pools/{id}
```

Callers exchange `locations[]` with `server`, `world`, `x/y/z`, optional
`yaw/pitch`, and `weight`. Never expose or accept the internal
`WeightedRandom` Gson map. Preview/upsert require at least one location for the
current ARC network ID and reject missing local worlds, empty pools,
duplicates, unsafe IDs, unknown fields, non-finite/out-of-range coordinates,
and invalid weights. Persistence uses `LocationPoolManager` and atomic file
replacement. Reads/preview use `location-pools-read-enabled`; mutation requires
`location-pools-write-enabled: true` and explicit production authorization.

## Treasure pools (canonical)

```
GET    /ops/treasure-pools[/{id}]
POST   /ops/treasure-pools/preview
PUT    /ops/treasure-pools/{id}
DELETE /ops/treasure-pools/{id}
```

The public schema supports native `item`, `money`, `command`, `sub-pool`,
`enchant`, `potion`, `ae`, and `slimefun` rewards. Item rewards use ItemSpec;
never expose or accept Bukkit's serialized `stack` map. Preview builds ItemStack
objects, computes selection probabilities and health, and rejects unknown
fields, unsafe or duplicate IDs, missing sub-pools, and sub-pool cycles. Delete
rejects referenced pools. Writes atomically replace one YAML file before
publishing the pool to `TreasureManager`. Reads/preview use
`treasure-pools-read-enabled`; mutation requires
`treasure-pools-write-enabled: true` and explicit production authorization.
There is deliberately no draw/give/run endpoint in this content API.

Existing item rewards are returned as `{preserveItem:true,itemPreview:{...}}`.
`itemPreview` is informational and may be lossy for rich Bukkit components;
round-trip edits preserve the exact live stack by pool + reward ID. To replace
the item itself, remove those fields and send `item:ItemSpec`. Do not turn
`itemPreview` into an implicit write source.

The treasure core has no compatibility parser for old message fields,
`sub-pool-id`, Slimefun `itemId`/`item`, or AE `book`/`randombook` kinds.
Canonical durable fields are `messages`, `poolId`, `item-id`, and
`kind: item|random_book`.

Upsert rejects empty or all-zero-weight pools and references to transitively
unusable sub-pools. Existing unhealthy pools remain visible in read output for
repair; remove them with the dedicated delete operation instead of persisting
another unusable definition.

## Content health (canonical)

```
GET /ops/content/health
```

This read-only endpoint is the first step before content work. It summarizes
ItemPresets, CMI kits, scheduled commands, location pools, and treasure pools.
It respects every component's existing read gate, isolates startup/integration
failures, and reports `complete`, `healthy`, issue counts, unavailable
components, unhealthy pools, and intentionally disabled kits/schedules. Do not
turn it into a write, repair, reload, draw, or execution endpoint.

## MCP surface budget

The internal HTTP routes stay catalog-specific and typed. The external MCP
surface is intentionally compact:

- `arc_ops_server` returns normal server status, or `GET /ops/economy/audit`
  when `economy_hours > 0`; the ledger read is gated by
  `economy-audit-read-enabled` and never mutates balances. Economy ledger
  schema v2 also returns bounded `recentEvents`, `recentFailures`, attempt
  outcomes, context-field coverage, and `adminShopSales`: ranked item path,
  material, canonical Slimefun ID when available, exact sold quantity,
  effective unit price, seller concentration, actual attributed income,
  evidence kind, and unattributed remainder for EconomyShopGUI and
  AutoSellChests sales. The response also ranks successful transactions by a
  bounded `source + action` classifier; arbitrary provider strings never become
  metric labels. Detailed
  account, session, balance, item, counterparty, and correlation evidence stays
  in this authenticated response and must never become Prometheus labels.
  `autoSellAudit` is a local, read-only runtime diagnostic: loaded/eligible
  chest counts, interval and multiplier distributions, lifetime item count,
  and ARC-captured AutoSell pre-events. It samples only already loaded chests
  and must never expose owner, chest, inventory, or location identity.
  The same response includes `bankAudit` from the single `spawn` collector:
  aggregate Bank supply plus bounded top accounts and recent account changes.
  Complete snapshot pairs classify observed transfers, interest accrual,
  interest capitalization, unexplained supply movement, and mixed changes;
  this is explicitly `snapshot_delta_inferred`, not an exact Bank event.
  Survival and parkour must remain standby rather than collecting duplicate
  network snapshots.

- `arc_ops_content_read` dispatches list/detail and preview;
- `arc_ops_content_write` dispatches gated upsert/delete on one explicit node;
- `arc_ops_content_health` performs the cross-catalog audit.
- `arc_ops_npc_read` covers list/detail/preview;
- `arc_ops_npc_write` covers gated presence-aware upsert/delete;
- `arc_ops_hologram_read` covers CMI list/detail/preview;
- `arc_ops_hologram_write` covers gated CMI presence-aware upsert/delete;
- `arc_ops_world_snapshot` wraps the ready-made BlueMap renderer.

Do not expose one MCP tool per HTTP verb/catalog, and do not add native content
APIs for ordinary YAML such as `announce.yml`. Simple configs stay in the
normal read/diff/push workflow. The generic `arc_ops_request` escape hatch is
not part of the supported surface. No compatibility aliases are required.

## Citizens NPCs and BlueMap

```
GET    /ops/npcs[/{id}]?world=&limit=
POST   /ops/npcs/preview
PUT    /ops/npcs
PUT    /ops/npcs/{id}
DELETE /ops/npcs/{id}
```

Read/preview require `npcs-read-enabled`. Mutations require
`npcs-write-enabled` plus explicit production authorization. Preview and writes
only inspect already loaded chunks: do not generate terrain as a side effect of
content placement. `PUT /ops/npcs` creates from an `NpcSpec`; `PUT
/ops/npcs/{id}` patches one existing NPC. Missing root and nested fields
preserve current settings, explicit `null` removes only supported optional
traits, supplied collections replace only that collection, and unknown fields
are rejected. Location changes require passable feet/head, solid ground and an
in-border position. Writes use native Citizens traits and persist through
`NPCRegistry.saveToStore()`.

Compile and test against the same Citizens minor version as production
(`2.0.42-SNAPSHOT`, live build 4162 as verified on 2026-07-26). The stable
NpcSpec field `commands.persistSequence` maps to Citizens
`rememberLastUsed()` / `setRememberLastUsed()`; the former
`persistSequence()` / `setPersistSequence()` methods are absent at runtime.

When BlueMap is present, ARC publishes the live Citizens registry as
`ruscrafting-citizens` marker sets. Do not serialize or edit Citizens
`saves.yml` from MCP and do not replace the native API with console commands.

## Routes (items)

```
POST /ops/item/preview
POST /ops/player/{name}/give
GET  /ops/player/{name}/inventory
```

Guard: `OpsHttpConfig.itemsReadEnabled` / `itemsGiveEnabled`.

## Extension: new endpoint

1. Handler in the matching `Ops*Handlers` boundary
2. Route in `OpsHttpServer.route()`
3. List in `routes()` helper
4. Comment in `src/main/resources/modules/ops-http.yml`
5. MCP tool in `mcserver/scripts/mcp-server/server.py` if needed
6. Test in `src/test/kotlin/ru/arc/ops/` (Kotest + MockBukkit)

## Deploy

```bash
./gradlew shadowJar
cd mcserver && ./scripts/mc arc classic
```
