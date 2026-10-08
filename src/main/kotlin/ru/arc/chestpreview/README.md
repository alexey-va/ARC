# Private container inspection

Ordinary, trapped and copper chests, barrels, shulkers and the viewer's own
Ender Chest contents use private ItemDisplay icons. `arc.chest-preview` remains
required and defaults to false, including operators; the menu never grants it.
Opening/access checks below always run before reading inventory contents.

Similar stacks share one icon in first-slot order. Optional `× N` TextDisplays
show the total across the entire bounded 54-slot inventory, including matching
stacks beyond the displayed unique-item limit. Icons preserve their model and
metadata with display amount one. There are no item names, titles or overflow
messages, and empty containers show nothing.

## Configuration and personal controls

All server defaults live under `chest-preview` in
`plugins/ARC/modules/item-info.yml`. `/arc reload` replaces the ItemInfo runtime
and display owner with the loaded settings; subsequent tuning needs no restart.
The initial new plugin version still requires normal activation.

`/mm` → Settings → Interface → Container preview has an independent enable switch
and three tuning pages. It works independently of the ordinary item-info mode.
Sparse personal overrides persist in LuckPerms meta
`arc-chest-preview-preferences`. Unset fields follow the current server defaults;
page/all reset removes those overrides semantically through the `default` sentinel.
Changes affect only the current player's private scene and apply on its next tick.

| Settings | Purpose |
| --- | --- |
| `enabled`, `scale`, `max-items`, `columns`, `cell-spacing` | Enable, overall size and grid layout (up to 12 icons) |
| `max-distance`, `vertical-gap` | Bounded targeting reach and height above the lid |
| `icon-scale`, `depth-scale`, `block-pitch`, `block-yaw` | Icon size, panel-local depth and fixed shallow ordinary block pose |
| `item-transform` | Native ItemDisplay GUI, FIXED or NONE model context |
| `show-counts`, `count-scale`, `count-offset-y` | Numeric label visibility, size and vertical position |
| `background-opacity`, `brightness` | Background alpha and native display light level |
| `teleport-ticks`, `stability-threshold` | Native movement interpolation and small safe-position deadband |

Defaults use GUI, 12° top tilt / 20° side turn, 15% depth, 3-tick interpolation,
6 icons in 3 columns, and numeric labels. Explicit custom models and flat items
keep their authored pose; depth compression still applies to the whole icon.
The local half-turn cancels ItemDisplay's native Y rotation. No camera angles
are added to the icon quaternion: CENTER billboards already own camera facing.

## Motion and lifecycle

The shared inspection service still arbitrates source priority; a winning empty
container frame suppresses lower text sources. OFF for ordinary item info is
resolved temporarily for arbitration and its generic view is cleared before the
packet owner's next snapshot, so container preferences remain independent.
A higher-priority winner, access loss, target loss, suppression or reset clears
the private scene. No Bukkit entities or synthetic inventory events are created.

All parts retain handles and share one destination and native interpolation.
Tiny safe changes can retain the prior anchor. Clearance search refines the free
boundary instead of jumping by the coarse candidate stride. An obstructed
movement corridor snaps rather than animating through a solid block; a new
container or changed personal layout gets a fresh scene at its destination.
The oriented grid volume must fit loaded/sent free space and have line of sight.
Top placement is retried before side placement, so a clear lid cannot remain
latched to a distant side position. If no position fits, the panel is hidden.
Viewers with the test permission are inspected every tick; others keep the
ordinary five-tick cadence. Native client smoothness remains separate from these
geometry, lifecycle and packet checks.

## Access boundary

`ChestPreviewAccess` resolves physical containers (both halves for double
chests) from block metadata before inspecting any inventory. A denied, missing,
unsent, malformed, locked, obstructed or loot-table container hides the entire
card. The ray corridor and relevant chunks must
already be loaded; inspection does not load chunks or generate loot.

The read-only protection checks are WorldGuard's `CHEST_ACCESS` build query
(`INTERACT` for the ender-chest access block),
its session bypass and separate sign-chest protection, and Lands'
`INTERACT_CONTAINER` world role flag (`sendMessage=false`, including wilderness).
Installed-but-disabled or incompatible providers fail closed. Access is not
cached between viewers or refreshes.

Closed shulkers check the native half-block lid sweep with Paper's
`Entity.wouldCollideUsing`; its bounds match vanilla 1.21.11's opening check and
are deflated by `1e-6` to avoid counting touching faces. This also checks entity
collisions and the viewer's world border. Already-open shulkers skip that sweep.
The query's neighbouring chunks are verified loaded and sent first.

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
cover private audience, optional numeric labels, unchanged handle identity, independent
preferences, cleanup, interpolation and opacity. Configuration tests reload every
new setting; preference and HelpCenter tests cover sparse overrides and reset.

Native client acceptance after owner-controlled activation: grant only the test
account `arc.chest-preview`, compare an allowed chest and a denied Lands/WG chest,
point at each half of a double chest, remove the permission, change the contents,
and look away. Confirm the private card disappears and never appears for a
second player without the permission. Delivery without restart does not prove
that this behavior is active.

Platform references: [Paper Chest](https://jd.papermc.io/paper/1.21.11/org/bukkit/block/Chest.html),
[WorldGuard protection queries](https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/),
[Lands API](https://wiki.incredibleplugins.com/lands/developers/api).
