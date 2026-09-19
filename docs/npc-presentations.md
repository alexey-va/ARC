# NPC presentation ownership

ARC owns visible NPC names, permanent text and temporary dialogue. Citizens
keeps the internal NPC name/identity and movement. Internal names remain stable
for existing name-based scripts; ARC's NPC lookup additionally accepts the
visible name. BlueMap and the operations API report the ARC name.

## Storage and migration

`plugins/ARC/data/npc-presentations.json` is the authoritative schema-v1 catalog,
keyed by Citizens UUID. The numeric `npcId` is diagnostic, not identity: deletion
and reuse of an integer ID cannot transfer a presentation to another NPC.

Each `records[uuid]` has `npcId`, `presentation`, and `legacyBackup`. The
presentation contains `name`, `nameVisible`, `lines`, `lineHeight`, `viewRange`,
`hasHologram`, and optional original `speechBubbles`/`sendTextToChat` flags.
An empty name or empty line array is intentional, not a request to reimport.

On first encounter ARC imports the previous `arc_npc_hologram_backup_v1`
metadata, or current native content when no backup exists. A styled final
name line is separated from the body. The complete new catalog is committed
atomically with read-back **before** native names/lines are hidden or cleared.
The original backup is retained for deliberate operator recovery. Invalid
legacy entries are skipped without deleting their native content; a failed
catalog write prevents the migration from touching Citizens.

Edit through the existing NPC operations API or edit the catalog and use
`/arc reload`. Reload validates the replacement before changing the active
catalog. Invalid JSON or unsupported schema fails without replacing the file.
Do not edit Citizens names/holograms to change an already-managed presentation.

## Native lifecycle and performance

Citizens 2.0.43 build 4238 invokes `Trait.run()` from `removeTrait()` and can
reattach its hologram trait at spawn/name update. ARC therefore keeps an empty
trait attached, hides `NAMEPLATE_VISIBLE`, clears nonempty native lines, and
cleans an existing name renderer. Periodic reconciliation never unregisters a
trait, never serializes backup metadata, and never writes an unchanged catalog.
Unsupported item/custom native renderers are not cleared.

ARC shutdown or module disable removes its own displays; it deliberately does
**not** transfer text ownership back to Citizens or recreate native holograms.
Disabling this renderer consequently hides ARC labels until it is re-enabled.
This is not a rollback command. Use the retained backup for an explicit rollback.

Following still runs at the configured two-tick cadence with client display
interpolation. MiniMessage/components are rebuilt only when text changes.
All delayed callbacks belong to the lifecycle scope and are cancelled/fenced
across reload and shutdown. Native chat-bubble fallback is retired; dialogue
uses the same ARC stack rather than reviving a second renderer.

Verification: focused store/service/model/operations tests cover lossless
text migration, failed persistence, repeated reconciliation, hidden/empty
content, restart, and reused numeric IDs. A new spawn profile after activation
is required to quantify the live performance improvement; source tests do not
establish a server MSPT reduction.
