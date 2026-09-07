# Entity cleanup

`EntityCleanupModule` owns lifecycle; `modules/entity-cleanup.yml` owns policy.
The bundled module is disabled. Survival explicitly enables the first
`rules.mob-equipment` rule with a remaining lifetime of 1800 ticks (90 seconds
at 20 TPS). All lists, world filters, per-material lifetimes and mob exclusions
are compiled once on startup/reload. Unknown enum names and invalid lifetimes
reject the reload instead of broadening cleanup. Additive config migration
preserves operator values, empty lists and unknown future settings.

There is no periodic task, entity/chunk scan, item index, asynchronous Bukkit
access, database, Redis dependency or per-drop logging. Work is proportional
to relevant spawn/pickup/death/item-spawn events and that death's drop list.
Native server aging performs removal; unloaded chunks and low TPS delay it.
Weapons/armor are unstackable under the required vanilla item comparison.

## Provenance and exclusions

Eligible mob spawns receive a PDC proof containing the current service session
and spawn reason. Pickup, dispenser armor and player interaction revoke proof
for the whole mob. A continuous `/arc reload` retains proof and pickup
protection, while stopping/re-enabling the module or restarting the server
invalidates all old proof. Existing unknown mobs/items are never scanned.

The death handler modifies only eligible existing drop stacks, adding a
temporary PDC ticket. Paper retains its native `DefaultDrop` consumer. The
actual `ItemSpawnEvent` removes that ticket before applying native age; ticket
session, config revision and server tick must still match. This is an exact
stack handoff, with no nearby-entity matching or shared death queue. A later
player pickup/re-drop creates a fresh item with the normal lifetime.

Names, lore, PDC, models, non-default components and attributes are protected
by comparison to a fresh vanilla item after normalizing damage/repair cost.
Enchantments are protected by default; `protect.enchanted: false` permits
vanilla enchantments while retaining all other exclusions. Player deaths,
unknown source mobs, named/configured special mobs, unlimited-lifetime items,
owned/thrown items and cancelled spawns are skipped.

Plugins/admin commands that replace tracked mob equipment with *indistinguishable*
vanilla items without an event cannot be identified universally. Exclude their
worlds, spawn reasons, entity types, PDC or metadata through this module's
policy. The survival profile excludes dungeon/QA worlds and CUSTOM spawns.

## Native lifetime and extension

`NativeItemLifetime` reads the active Spigot and Paper world snapshots through
the public (deprecated) `Server.Spigot` bridge once on reload/world load. No
supported replacement exposes these world rates in Paper 1.21.11. The resolver
accounts for per-world Spigot defaults and Paper alternate material rates;
unknown/invalid rates skip aging. Disk edits to Paper/Spigot themselves must
first be activated by their normal server lifecycle. Existing drops keep the
age already applied when the module configuration changes.

Add a new rule as its own owner in this package and a new `rules.<id>` subtree;
register/unregister its event handlers through the module lifecycle. Do not
add a global sweeper simply to host future rules. Startup/reload logging reports
the active TTL and cumulative aged/unknown-native-rate item counters.

Primary contracts: [Paper death delivery](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java),
[native drop consumers](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/patches/sources/net/minecraft/world/entity/Entity.java.patch),
[native item age](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftItem.java),
[active config snapshots](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/src/main/java/io/papermc/paper/configuration/PaperConfigurations.java).
