# Dungeon position saves

ARC 1.4.3 adds player-owned position saves to the existing EliteMobs dungeon QoL.
Configuration lives under `dungeon-qol` in `modules/elitemobs.yml`; startup merges
missing bundled defaults without replacing operator values.

## Player controls

- `/начать` (also `/данж начать`): start the current dungeon lobby through native
  EliteMobs. Outside a dungeon or after the start, it explains the current state.
- `/сохраниться [название]`: save the current safe standing position. Empty names
  select the first available `Место 1`–`Место 5`; the same name replaces that
  player's manual point, ignoring case. Names are literal text, at most 32 chars.
- `/сохранения`: native dialog with manual/automatic points, creation, deletion
  confirmation, entry/last-exit travel, and a separate native dungeon exit.
- `/данж вход`: return to the authored start of this exact dungeon clone.
- `/данж выйти`: native EliteMobs quit for instances; `open-exit-command` (default
  `spawn`) for open dungeons, because native quit has no open-world return path.

Only positions are saved. Inventory, rewards, mobs, objectives and match state
are never restored. Cancelling an ordinary command/plugin teleport in an instance
shows a throttled, clickable explanation of exit and checkpoint commands.

## Scope and safety

Each player's PDC retains up to five manual and three rolling autosaves per
world/run, across at most 16 worlds. The separate existing last-exit checkpoint
remains compatible. Points expire after `resume-hours` (default 72).
Dynamic runs use an in-memory token keyed by the native instance object and the
actual world UUID; a new instance or ARC lifecycle cannot reuse an old run.
Open dungeons use their persistent world UUID and `open` token.
Death and dungeon completion clear positions in that world. Players cannot use
another participant's points, spectate through this feature, enter a different
world, revive themselves, or teleport from a lobby/finished match.

Autosaves are checked every 20 seconds, only when a player stands safely, is not
flying/gliding/riding/falling/burning, and has been out of combat for 15 seconds.
The first safe check can save; subsequent points require at least 120 seconds
(configurable 30–600) and eight blocks from the latest autosave. Three AUTO points
roll independently of manual names; manual saves have a five-second rate limit.
Manual saves/removals and autosaves use the core native player-data persistence
port. A persistence failure reports failure rather than claiming disk durability.

Travel waits three seconds. Movement, combat, logout, quit, death, closure, changed
membership/run, replaced/deleted points, and an unsafe destination invalidate the
request. Each timer owns a unique token, so an old timer cannot complete a newer
request. The standing body and supporting floor are checked against liquids,
hazards, walls, height and border; only existing chunks may load, never new terrain.
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

`EMCheckpointTeleporter` registers after EliteMobs. On the primary thread it
requires the current ongoing DungeonInstance membership, scopes authorization to
one UUID and exact same-world PLUGIN destination, arms the flag at LOWEST only for
that event, lets native LOW consume it, and clears it at LOW and in `finally`.
Nested/foreign events and destination rewrites are denied. Other cancellations
are never undone; an already-set native flag makes this adapter decline the call.
The existing season-instance membership guard remains in force.

The authored start is resolved with native `ConfigurationLocation.serialize` and
rebound to the clone world, matching `DynamicDungeonInstance.generate`.
If this native contract changes, re-verify the handler and start-location path
before updating EliteMobs. Do not replace the scope with a generic uncancel or a
flag left armed across ticks.

## Delivery

Ship the shadow JAR plus scoped `elitemobs.yml` and affected guide text. Keep QoL
disabled on parkour. A `--no-restart` / `--no-reload` delivery verifies disk bytes
only: player-facing activation and native client interaction await the owner's
later restart. Unit/MockBukkit and CI evidence do not imply live gameplay QA.
