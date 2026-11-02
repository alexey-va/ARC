# ARC player graves

ARC owns new player graves, complete native item bytes, XP, recovery, the menu,
cross-server discovery and private markers. GravesX is optional compatibility
for old records, not the owner of new deaths. Mob graves, tokens and looting
other players' graves are outside this implementation.

`/graves` and the first main-menu button open a native list with contents,
coordinates, server/world, experience and expiry. Shared MySQL includes graves
whose source backend is offline; a visit requires that backend online.
The destination waits for actual HuskSync completion and ARC reconciliation.

Parkour keeps its separate inventory without HuskSync. It reads the same grave
list, but never applies or advances a shared-inventory checkpoint. Pending
operations are isolated by inventory profile.

At the grave, crouch or choose **Забрать вещи** in its card. Only the owner can
claim, within six blocks on the owning backend. Overflow stays in the same grave.

## Existing economic conditions

Defaults retain 3-hour storage, 18 graves per player per backend, 80% XP and
100 Vault coins for a successful visit. `arc.graves.teleport.free` still waives
the fee; this change grants no permissions. Walking and claiming are free.
Keep-inventory worlds and canceled deaths retain their native policy.
Unavailable placement/storage/capacity keeps death items and XP with the player.
See [the assessment](graves-economy-20261103.json).

## Item ownership and recovery

`arc_native_graves` uses the existing ARC audit MySQL settings/shared pool.
`arc_native_grave_sequences` allocates monotonic operation receipts.
`arc_native_grave_watermarks` retains each owner's latest acknowledged exact
inventory/XP checkpoint independently of grave expiry or removal.

Death retains inventory until PREPARED is durable; later death cancellation is
checked before admission. Claim uses CLAIMING. Application first wins fresh SQL
CAS: a cached operation cannot authorize another payout. Input, item/XP pickup,
consumption, mending and durability changes wait while the transition/recovery
is pending. An unexpected mismatch moves only that entitlement into REVIEW,
releases ordinary controls and logs grave/player IDs. REVIEW never auto-replays.

After applying and verifying native inventory/XP, ARC records a HuskSync custom
receipt and captures the matching snapshot in the same Paper turn. Exact SQL
payload readback precedes final grave CAS and atomic owner-watermark advancement.
An unknown write is not a payout ACK.

HuskSync 3.8.8-c3a6afd Lockstep can prefer older Redis state over newer SQL,
including a world-save snapshot. ARC compares synchronized receipt and durable
watermark. It restores a newer valid Husk SQL inventory, or the independent
checkpoint if history is stale/rotated, and persists before releasing recovery.
Old world-save captures begun before the local native transition are canceled
before publication: otherwise BEFORE items could mix with the new receipt.
Disconnect checkout and later normal saves retain their native path.

Receipt serialization stays registered with HuskSync while the Graves module
is stopped, so ordinary saves retain receipts. The next generation replaces it
and accepts the prior formal wire value without a plugin-classloader cast.
The fence-only Bukkit listener is owned by HuskSync, so delayed world-save events
remain guarded after ARC/module stop. Same-classloader generations reuse one
listener and its UUID/timestamp fences; HuskSync disable removes the registration.
Neither this guard nor the serializer retains runtime/player/SQL resources.
Module stop separately drains accepted storage and Core's native packet cleanup.
Reinitialization awaits both. Full JAR/classloader hot replacement is unsupported;
use a server restart.

Every backend sharing a HuskSync inventory must register the receipt before native
grave writes begin. HuskSync drops unknown custom data on an older backend, which
can turn later progress into an apparent stale inventory. Quiesce/stop Spawn and
Survival together before starting the new ARC; per-target rolling restarts are
unsafe. Do not stage this JAR in either active plugin root while an independent
automatic restart could activate only that backend. Parkour's local profile is
independent of this coordinated activation gate.

## Visual lifecycle

Nearby owners see two packet-only stone parts and a camera-facing label.
No world entities/blocks, chunk tickets or background chunk loads are created.
Stable scenes disappear on claim, expiry, world change, logout and shutdown.
The existing selected-grave compass and menu reminder remain.

When GravesX is absent, a loaded-entity sweep and future chunk-load events remove
only ArmorStand/TextDisplay holograms with `graveHologram` plus a valid matching
`gravesx:grave_uuid` or `graveHologramGraveUUID:<uuid>`. Unmarked decorations and
conflicting IDs stay. Old blocks and the GravesX database are not broadly deleted.

## Two-stage legacy handoff

1. Activate ARC with GravesX still installed. New deaths use ARC; the old adapter
   serves existing GravesX records.
2. Clean ARC stop captures loaded valid PLAYER inventories once (equipmentMap
   duplicates the same items), stores IMPORTED records and atomically seals
   their hashes in `plugins/ARC/data/native-graves-legacy-handoff.json`.
3. Quarantine the exact GravesX root JAR through supported `mc rm`, preserve its
   data directory, and restart with GravesX absent. Only exact sealed records
   activate; partial activation can retry without increasing contents/revisions.

Starting with GravesX present first invalidates an old seal, preventing stale
reload snapshots from becoming imports. Do not remove GravesX after a failed
final seal or re-enable its old database after ownership moved to ARC.
The API exposes a loaded cache, not a complete database export. Missing-world,
corrupt and unloaded records are not guessed/discarded; keep the database for
separate evidence-based recovery.

## Evidence

Focused tests cover metadata/overflow/XP, delayed durability, stale CAS, recovery
sessions, REVIEW, menus/markers, legacy seals, exact Husk ACK and real MySQL
rollback. API/bytecode evidence uses Paper 1.21.11 and HuskSync 3.8.8-c3a6afd
(runtime SHA-256 `b2e39fef7b7d00496b7a7359e272b00b2c9c8ac74610f2963ab5625837426073`);
GravesX artifact/source identities are in [libs/README.md](../libs/README.md).
Mocks and source-derived marker composition do not prove native player
death/transfer or Minecraft rendering. Report delivery, activation and those
observations separately.
