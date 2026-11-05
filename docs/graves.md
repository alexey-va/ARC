# ARC player graves

ARC owns new player graves, complete native item bytes, XP, recovery, the menu,
cross-server discovery and public nearby tombstones. GravesX is optional compatibility
for old records, not the owner of new deaths. Mob graves, tokens and looting
other players' graves are outside this implementation.

`/graves` and the first main-menu button open a native list with contents,
coordinates, the configured Russian world name, experience and expiry. Backend
identifiers stay internal. Shared MySQL includes graves
whose source backend is offline; a visit requires that backend online.
The destination waits for actual HuskSync completion and ARC reconciliation.

Parkour keeps its separate inventory without HuskSync. It reads the same grave
list, but never applies or advances a shared-inventory checkpoint. Pending
operations are isolated by inventory profile.

At the grave, right-click the tombstone to open a normal contents chest. The
item icons are detached read-only copies; **Забрать всё** is the only withdrawal
action. Crouching does not claim items. Only the owner can withdraw, within six
blocks on the owning backend. Overflow stays in the same grave.

`/graves admin [server]` requires `arc.graves.admin` (default: operators) and
reads all current ACTIVE graves from shared SQL, including offline owners and
backends. An administrator can inspect another owner's contents without claim
or travel actions. Permission, player identity and request lifetime are checked
again before an asynchronous result is displayed. Ordinary players remain
restricted to their own graves.

ARC's separate grave-creation chat notice is off by default. Set
`notifications.creation-message: true` to emit it once after a new grave's
durable CREATE acknowledgement. Listing, reconnecting and cross-server refreshes
never announce existing graves as new. Other death, return-teleport and claim
messages retain their existing behavior.

## Existing economic conditions

Defaults retain 3-hour storage, 18 graves per player per backend, 80% XP and
100 Vault coins for a successful visit. `arc.graves.teleport.free` still waives
the fee; this change grants no permissions. Walking and claiming are free.
Keep-inventory worlds and canceled deaths retain their native policy.
Unavailable placement/storage/capacity keeps death items and XP with the player.
See [the assessment](graves-economy-20261103.json).

## Item ownership and recovery

`arc_native_graves` uses the existing ARC audit MySQL settings/shared pool.
Without configured MySQL settings the module stays unavailable and registers no player recovery guards; a configured database failure retains recovery protection.
`arc_native_grave_sequences` allocates monotonic operation receipts.
`arc_native_grave_watermarks` retains each owner's latest acknowledged exact
inventory/XP checkpoint independently of grave expiry or removal.

Death retains inventory until PREPARED is durable; later death cancellation is
checked before admission. Claim uses CLAIMING. Application first wins fresh SQL
CAS: a cached operation cannot authorize another payout. Input, item/XP pickup,
consumption, mending and durability changes wait while the transition/recovery
is pending. An unexpected mismatch moves only that entitlement into REVIEW,
releases ordinary controls and logs grave/player IDs. REVIEW never auto-replays.

Gameplay state matching compares all 41 native ItemStacks, including amount,
components and PDC, plus the exact level, progress bits and total XP. Paper may
reorder keys in an otherwise identical NBT compound during item rebinding;
binary inequality alone is not an inventory change. Persisted model equality,
canonical payload hashes and SQL CAS remain byte-exact. A pickup granting no XP
preserves the player's original XP fields without rounding them.

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

For the first introduction of native grave receipts, every backend sharing a
HuskSync inventory must register the receipt before native grave writes begin.
HuskSync drops unknown custom data on an older backend, which can turn later
progress into an apparent stale inventory. That introduction requires stopping
Spawn and Survival together before starting receipt-aware ARC; an independent
automatic restart must not activate only one backend. Subsequent cosmetic
updates retaining the same receipt wire format do not repeat this migration
gate. Parkour's local profile is independent of the coordinated introduction.

## Visual lifecycle

Nearby players see varied packet-only tombstones. Every inscription has a solid
plaque generated from the same measured text bounds, including its signature,
with a visible inset and a small front offset. The cairn mounts its plaque on
the lid. Engraved monuments have an independent dark plaque below the portrait.
Text wrapping and fitting retain both quote and full Java/Bedrock nickname.
Unmeasured legacy glyphs suppress the inscription instead of guessing its size.

`/mm → Настройки → Моя могила` selects a random style or one of ten models
(five compact and five engraved), plus automatic, custom or absent inscription.
A custom inscription is one plain line of at most 80 supported characters;
formatting, hidden controls and unsupported glyphs are rejected. Preferences
use the existing shared LuckPerms metadata owner under `arc-grave-appearance`.
They apply to future graves. Each new grave captures an immutable cosmetic
snapshot; reconnects, configuration changes and later preferences do not change
its text. A selected engraved model uses its matching compact design when
images or the second interaction cell are unavailable.

The bundled `graves/epitaphs.yml` contains 206 unique short Russian phrases,
9–12 per death category. The last damage cause distinguishes falling, lava,
fire, drowning, explosions, projectiles, mobs, PvP, void, suffocation, hunger,
freezing, magic, lightning, retaliation, falling blocks, elytra collision,
wither, dragon and generic deaths. Selection is stable per grave and category;
it never loads or parses the catalog during a death event.

`appearance.weights` retains relative model frequency for random selection;
all-zero weights fall back to slate. The legacy `appearance.epitaphs` pool
continues to render old graves lacking a snapshot. New automatic inscriptions
come from the cause-specific catalog. Cosmetics are stored in the optional
`arc_native_grave_appearance` table, atomically with grave creation. They are
excluded from the canonical v1 item/XP payload, its hash and recovery receipts.
Older ARC readers/writers keep the same payload and leave the optional table
alone. Missing or corrupt cosmetic metadata falls back to the legacy appearance
without preventing item recovery.

Each viewer receives client-only BARRIER masks only for their allocated selected
nearby scenes (at most eight, with one tomb per cell). Core transport remains
asynchronous; allocation is not a native-client rendering acknowledgement. Actual world
blocks are never written; masks use only loaded, client-sent, passable cells.
Main-hand right-click opens the owner's contents chest after readiness,
current-record, expiry, world and reach checks. Foreign clicks disclose no
inventory. Claims still use the existing owner-only durable flow. Teleport
visits choose a safe adjacent column excluding the target and other local active
tomb columns. If every bounded candidate is blocked, the visit fails without
charging the player.

Paper's actual block updates may replace a client mask; the owner reasserts it
on the next tick after a click and on a one-second heartbeat. Removal restores
the current actual block data, never a cached before-image. Scenes and masks
clear on claim/expiry, world unload/change, death, logout and shutdown; queued
work cannot enter a replacement runtime. No world entities, signs, block
persistence, chunk tickets or background chunk loads are created. There are no
grave bossbars, compass or tracking controls. A confirmed durable withdrawal
plays one quiet local sound and a short particle accent for its owner.

When GravesX is absent, a loaded-entity sweep and future chunk-load events remove
only ArmorStand/TextDisplay holograms with `graveHologram` plus a valid matching
`gravesx:grave_uuid` or `graveHologramGraveUUID:<uuid>`. Unmarked decorations and
conflicting IDs stay. Old blocks and the GravesX database are not broadly deleted.

## Administrator-created decorative graves

With `arc.graves.admin` (OP by default), look at a loaded solid support block
within six metres and run `/arc grave create`. The native dialog keeps that
fixed target, offers all ten existing designs plus random selection, a random
cause-based inscription or plain custom text up to 80 supported characters,
and an optional signature up to 17 characters. The signature starts empty.
`/arc grave create random` immediately saves a random model and inscription.
New scenery faces the administrator in the nearest cardinal direction. Tall
image variants retain the existing compact fallback when images or headroom
are unavailable.

Aim at a decorative model and use `/arc grave edit` to change your own creation,
or `/arc grave remove` to open an explicit deletion confirmation. Selection uses
the rendered model bounds; the support block need not be broken. Entry and
commit check the fixed admin permission, player identity, world, loaded chunk,
distance and current record revision. Creating or editing also rechecks solid
support and passable placement. Old visits, quit, death, world changes and
runtime shutdown invalidate callbacks.

The bounded local `plugins/ARC/data/decorative-graves.json` catalog is separate
from native death records: at most 4,096 entries, no items, XP, payment, recovery
receipts, personal-list entries, network advertisements or teleport targets.
Construction, loading and atomic read-back-verified writes use ARC's background
executor; the main thread retains an immutable snapshot. One admitted catalog
write runs at a time, and shutdown observes its storage drain. A failed catalog
load/write disables scenery management independently of item recovery. Raw
storage failures remain observable; completed decorative I/O does not prevent
the next recovery runtime from starting.

Public scenery uses the same HUD/display/engraving owner as real graves.
Recoverable graves have priority at a shared cell and throughout the eight-scene
budget. Decorative scenes never produce client recovery barriers or loot actions.
The existing plaque backing and ordinary death-grave signatures remain intact;
unsigned decorative inscriptions do not acquire an administrator's name.

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
