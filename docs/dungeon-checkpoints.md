# Dungeon position saves

ARC 1.4.3 adds player-owned position saves to the existing EliteMobs dungeon QoL.
Configuration lives under `dungeon-qol` in `modules/elitemobs.yml`; startup merges
missing bundled defaults without replacing operator values.

## Player controls

- Shift + F opens the dungeon panel while the player is in an active dungeon
  or its lobby. It takes precedence over the personal shortcut only there;
  ordinary F and an already-cancelled swap event remain untouched. Outside a
  dungeon the saved shortcut behavior applies again.
- The panel has a direct Main menu action and a Group section with party
  guidance and native `/elitemobs:em party menu` management. The latter is
  offered only when the installed EliteMobs party feature exists and is enabled
  (10.8.1 supports it; the supported 10.7.3 runtime does not). Panel, Group,
  guide and Main menu navigation do not explicitly close the dialog first;
  the shared ArcCore runtime owns dialog replacement and Escape semantics.
- `/данж` (also `/dungeon`): opens the dungeon control surface from any
  location. Outside a dungeon it offers the dungeon guide, the portal hub and
  the native EliteMobs list. `/данж тп` routes to `pw aguild`, and
  `/данж список` opens `elitemobs:em`; inside an instance, these transitions
  first ask the player to use `/данж выйти`. If EliteMobs is unavailable on a
  node, the menu opens the general guide.
- `/начать` (also `/данж начать`): start the current dungeon lobby through native
  EliteMobs. Outside a dungeon or after the start, it explains the current state.
- `/сохраниться [название]`: save the current safe standing position. Empty names
  select the first available `Место 1`–`Место 5`; the same name replaces that
  player's manual point, ignoring case. Names are literal text, at most 32 chars.
- `/сохранения`: native dialog with manual/automatic points, creation, deletion
  confirmation, entry/last-exit travel, and a separate native dungeon exit.
  Its Autosave submenu selects 1, 2 or 5 minutes, or Off. The preference is
  persisted in the player's `arc:dungeon_autosave_seconds` integer PDC and
  applies to all dungeons on that node; it does not alter manual points or
  departure-resume behavior. Failed persistence restores the prior preference.
- `/данж вход`: return through the normal ARC portal to safe ground near the
  authored start of this exact dungeon clone, outside native return portals.
- `/данж выйти`: native EliteMobs quit for instances; `open-exit-command` (default
  `spawn`) for open dungeons, because native quit has no open-world return path.

Only positions are saved. Inventory, rewards, mobs, objectives and match state
are never restored. A blocked ordinary command/plugin teleport in an instance
shows a throttled, clickable explanation of exit and checkpoint commands.

## Scope and safety

Each player's PDC retains up to five manual and three rolling autosaves per
world/run, across at most 16 worlds. The separate existing last-exit checkpoint
remains compatible. Points expire after `resume-hours` (default 72).
Dynamic runs use an in-memory token keyed by the native instance object and the
actual world UUID; a new instance or ARC lifecycle cannot reuse an old run.
Open dungeons use their persistent world UUID and `open` token.
Death preserves positions in that world; a new instance makes its old points
unavailable. Players cannot use another participant's points, spectate through
this feature, enter a different world, revive themselves, or teleport from a
lobby/finished match.

Autosaves are polled every 20 seconds. The first point is written at the first
suitable check while walking on a safe solid surface, without fire, water or
flight, and after 15 seconds outside combat. Subsequent points require at least
the player's selected interval (otherwise `autosave-seconds`, default 120,
configurable 30–600) and eight blocks from the latest autosave. Off prevents
new AUTO points without deleting old ones; the server's `autosave-enabled`
gate always takes precedence. Three AUTO points roll independently of manual names; manual
saves have a five-second rate limit.
Manual saves/removals and autosaves use the core native player-data persistence
port. A persistence failure reports failure rather than claiming disk durability.

Saved-point and entry returns use the normal ARC portal flow; there is no countdown
or movement-cancel travel timer. The same-world run, membership, point freshness
and safe-destination checks still apply. The standing body and supporting floor
are checked against liquids, hazards, walls, height and border; only existing
chunks may load, never new terrain.
The conservative passability check may reject cramped/partial-block positions;
players can choose another point or leave through the native exit.

## Native teleport contract

Verified 2026-09-06 by reading the descriptor-matched root JARs (no update-queue
inspection): spawn EliteMobs **10.8.1** SHA-256
`aac94a889701633d1746aa81646ddf05c1b0a8d1ce6b37fcdd69b011da766d24`,
survival **10.7.3** SHA-256
`688e93033a36f4168b2f69b8a65921fbb36affc047aa9534027724a2e5da674e`.
The pinned compilation/test API is `elitemobs-api:10.1.1`. The normalized
`MatchInstanceEvents.onPlayerTeleport` bytecode instructions agree across all
three: LOW, ignoreCancelled, a public one-event `teleportBypass`, then instance
membership/world cancellation. Native start uses that same flag.

EliteMobs can register its listeners after ARC during deferred initialization.
On the primary thread `EMCheckpointTeleporter`
requires the current ongoing DungeonInstance membership, scopes authorization to
one UUID and exact same-world PLUGIN destination, arms the flag at LOWEST only for
that event, lets native LOW consume it, and clears it at NORMAL and in `finally`.
This ordering is independent of which plugin registers its listeners first.
Nested/foreign events and destination rewrites are denied. Other cancellations
are never undone; an already-set native flag makes this adapter decline the call.
The existing season-instance membership guard remains in force.

The authored start is resolved with native `ConfigurationLocation.serialize` and
rebound to the clone world, matching `DynamicDungeonInstance.generate`.
Authored positions may float above the floor or overlap the return wormhole.
Entry resolution selects nearby safe standing ground outside native wormhole
trigger volumes; saved points inside those volumes are also rejected.
If this native contract changes, re-verify the handler and start-location path
before updating EliteMobs. Do not replace the scope with a generic uncancel or a
flag left armed across ticks.

## Delivery

Ship the shadow JAR plus scoped `elitemobs.yml` and affected guide text. Keep QoL
disabled on parkour. A `--no-restart` / `--no-reload` delivery verifies disk bytes
only: player-facing activation and native client interaction await the owner's
later restart. Unit/MockBukkit and CI evidence do not imply live gameplay QA.
