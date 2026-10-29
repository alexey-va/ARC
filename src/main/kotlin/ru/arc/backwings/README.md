# Back wings

CosmeticsCore owns wing rendering, movement, its equipped state and native persistence.
ARC only supplies item entitlement, the existing plain-F shortcut and migration of the
old `arc:back-wings` PDC preference. There is no custom display, body-yaw estimator,
per-tick transform update or fallback renderer. Original ItemsAdder items, meshes and
textures remain unchanged; vendor content configuration owns fitting.

| Original item ID | CosmeticsCore ID |
| --- | --- |
| `dreadknight:dreadknight_wings` | `arc_dreadknight_wings` |
| `galaxy_set:galaxy_wings` | `arc_galaxy_wings` |
| `deceaseset:decease_wings` | `arc_decease_wings` |

Plain **F**, with one of those exact ItemsAdder IDs in the **actual main hand**, cancels
only the hand swap. An equipped wing turns off; another wing first removes only other
ARC wing IDs, then equips the requested one. Foreign body cosmetics are never removed
by ARC. Neither hand, event hand nor any inventory slot is changed. Shift+F,
already-cancelled input (including ArcBuilder's editor), ordinary items and offhand-only
wings keep their existing controls. The handler runs at NORMAL with `ignoreCancelled`,
after MenuShortcutController's LOWEST handler.

The original item must remain in one of the 36 storage slots; cursor, armor, offhand
and nested containers do not establish ownership. ARC grants two temporary Bukkit
permissions for each owned wing: `cosmeticscore.user.cosmetics.wear.<id>` and
`cosmeticscore.user.cosmetics.seeingui.<id>`. No wildcard, admin permission, LuckPerms
record or permanent entitlement is created. Newly acquired items are discovered without
pressing F, so the native wardrobe can show and equip them. Item loss revokes only those
permissions and removes only the corresponding ARC wing. Death, quit, provider absence
and module shutdown revoke ARC's attachment. Native CosmeticsCore owns its visual
lifecycle and saved selections; quitting or reloading ARC does not overwrite them.

One initial online-player enumeration seeds a queue maintained by join/quit events.
Every ten ticks, one player inventory is checked per shared `BudgetedTaskQueue` step.
This bounds each step to 36 slots and three native state placeholders. There are no
per-tick online-player snapshots or motion listeners. HuskSync joins wait for
`BukkitSyncCompleteEvent` before reading inventory. Existing online players are checked
on module enable; respawn/world changes request a check too.

The old PDC choice is migrated once, only while its original item is owned. An existing
native ARC-wing choice wins. The old key is cleared after verified native equip, an
existing native choice, or an invalid/unowned legacy value; failed requests keep it for
retry. ARC never writes a new selection key. After migration, the native wardrobe is
authoritative: a GUI unequip remains off across checks, world changes and ARC reload.
No new database, Redis contract or cross-server preference policy is introduced.

## Documented integration boundary

The bridge uses only the mandatory arguments of the vendor's documented console commands:

- `cosmetics <player> add <id>`
- `cosmetics <player> remove <id>`

Live CosmeticsCore 1.3.13 rejected the documented optional `silent` argument; no optional flags are sent.
It queries `%cosmetics_has_equipped_<id>%` through the existing PlaceholderAPI dependency.
The three fixed IDs are the entire command/permission surface. Only literal `true` or
`false` (with surrounding whitespace ignored) is accepted; an unresolved token is unknown,
not an empty wardrobe. A successful Bukkit command dispatch is verified by reading back
the requested native state. No vendor classes, reflection, paid JAR dependency or API
artifact redistribution is involved. ItemsAdder, CosmeticsCore and PlaceholderAPI must
all be enabled when the module starts. If an optional provider is absent, no renderer is
started. An ARC module reload can start the bridge after providers become available.

References: [commands](https://cosmeticscore.devs.beer/commands),
[permissions](https://cosmeticscore.devs.beer/permissions),
[PlaceholderAPI](https://cosmeticscore.devs.beer/compatibility-with-other-plugins/compatible/placeholderapi),
[static body cosmetics](https://cosmeticscore.devs.beer/adding-content/body-cosmetic/static-body-cosmetic).

Configuration is `modules/back-wings.yml`; its Russian feedback remains editable and
reloadable through the normal module lifecycle. Existing obsolete pose keys are ignored
and preserved by merge-forward rather than destructively rewriting operator files.
Shutdown unregisters listeners, cancels its scope and removes all ARC permission
attachments. `BackWingsModule.drained()` remains the immediate completed cleanup contract
because ARC no longer owns a visual task queue.

## Focused checks and evidence boundary

```bash
./gradlew :test --tests ru.arc.backwings.BackWingsRuntimeTest \
  --tests ru.arc.backwings.BackWingsConfigTest
```

The focused tests use real mock inventories, PDC and Bukkit permission attachments with
a documented-command/PAPI double. They cover both-hand invariance, exact toggle/switch
commands, foreign-cosmetic preservation, storage-only ownership, temporary permission
revocation, old-choice migration, native GUI authority, provider failures and lifecycle
cleanup. They do not prove CosmeticsCore 1.3.13's actual placeholder output, command
readback timing, loading order, model fitting, cross-server persistence or native motion.
Those require the installed vendor plugin and the actual Minecraft client; source or
mock checks do not establish visual acceptance.
