# Private chest inspection

Looking directly at an ordinary or trapped chest produces an anchored inspection
hologram only for a player with `arc.chest-preview`. The permission defaults to
false, including operators, and is not a child of `arc.admin`. No permissions are
granted by the module. The existing inspection preference must be `HOLOGRAM`;
`OFF` and `BOSSBAR` do not display container contents.

`plugins/ARC/modules/item-info.yml` owns `chest-preview.max-items` (default 6,
range 1–12), `max-distance` (default 4.5, never beyond actual interaction reach),
`vertical-gap` (default 0.15), and `background-opacity` (integer percentage,
0 fully transparent, 100 opaque; default 40). `/arc reload` reloads configuration
before replacing the ItemInfo runtime and its display owner. Existing scenes are
removed and the next inspection tick uses the new opacity; no restart is needed
for subsequent setting changes once this plugin version is active.

The preview contains **only item icons** in a compact three-column grid, without
names, titles, counts, overflow or empty-state labels. Similar stacks share one
icon in physical slot order. Empty chests have no visible panel. The item model
and appearance metadata are retained, with display amount normalized to one.

The existing inspection arbitration selects the chest provider by priority. Its
empty frame suppresses lower text sources, while the host captures that winning
provider's icon snapshot for a private `PaperPacketDisplays` scene. OFF, BOSSBAR,
a higher-priority winner, target loss, suppression and player reset clear the
icons. The blank-space TextDisplay is only a background rectangle; it contains
no readable glyphs. The items use native GUI ItemDisplay transforms. All parts
share one chest-top anchor and client billboard, retaining display handles on
unchanged refreshes. A local half-turn cancels ItemDisplay's native Y rotation,
preserving the item's inventory-facing GUI model (block tops and unmirrored tools).
No Bukkit entity, inventory window, synthetic open event or
world mutation is created. The five-tick inspection refresh rechecks access
before reading contents.

Visual reference: [Volmit Gloss container previews](https://github.com/VolmitSoftware/docs/blob/master/gloss/15-container-previews.md)
and its slot-grid example. ARC retains its own access checks, permission and
compact text-free layout; Gloss is not installed or required.

## Access boundary

`ChestPreviewAccess` resolves both halves from block metadata before inspecting
either inventory. A denied, missing, unsent, malformed, locked, obstructed or
loot-table half hides the entire card. The ray corridor and relevant chunks must
already be loaded; inspection does not load chunks or generate loot.

The read-only protection checks are WorldGuard's `CHEST_ACCESS` build query,
its session bypass and separate sign-chest protection, and Lands'
`INTERACT_CONTAINER` world role flag (`sendMessage=false`, including wilderness).
Installed-but-disabled or incompatible providers fail closed. Access is not
cached between viewers or refreshes.

PersonalLoot, ItemsAdder, Slimefun, EliteMobs treasure chests, QuickShop shops,
AutoSellChests and crate anchors are excluded: their raw inventories need not be
the inventories a player would see. ArcExcellentCrates supplies the typed
`CrateLocationService` through Bukkit ServicesManager; absence of that service
while a crate plugin is installed hides previews. ARC consumes the API with
`compileOnly`; only ArcExcellentCrates carries its runtime classes.

QuickShop-Hikari 6.3.0.2 (`getShopIncludeAttached(Location)`) and AutoSellChests
3.0.0 (`AutoSellChestsAPI.isSellChest(Block)`) are isolated third-party adapters,
verified against the installed JARs. They bind once per plugin identity and use
direct location lookups, not registry scans. API failures hide the card. Before
adding another inventory-locking or virtual-container plugin, add its read-only
access classifier here; Bukkit has no universal side-effect-free “can open” API.

## Verification

Run the focused `ChestPreviewAccessTest`, `ChestPreviewProviderTest`,
`ChestPreviewSettingsTest`, and `ItemInfoConfigTest` suites. They cover permission
before target resolution, both-half authorization before content reads,
unavailable chunks, lock/loot/obstruction, failed providers, stable aggregation,
icon limits, viewer isolation and config bounds. Icon-renderer and controller checks
cover private audience, no readable text, unchanged handle identity, mode changes,
cleanup and opacity. The config test reloads the file with a changed opacity.

Native client acceptance after owner-controlled activation: grant only the test
account `arc.chest-preview`, compare an allowed chest and a denied Lands/WG chest,
point at each half of a double chest, remove the permission, change the contents,
and look away. Confirm the private card disappears and never appears for a
second player without the permission. Delivery without restart does not prove
that this behavior is active.

Platform references: [Paper Chest](https://jd.papermc.io/paper/26.1.2/org/bukkit/block/Chest.html),
[WorldGuard protection queries](https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/),
[Lands API](https://wiki.incredibleplugins.com/lands/developers/api).
