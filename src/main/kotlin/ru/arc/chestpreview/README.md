# Private container inspection

Ordinary, trapped and copper chests, barrels, shulkers, furnaces, blast furnaces,
smokers and the viewer's own Ender Chest contents use private ItemDisplay icons. `arc.chest-preview` remains
required and defaults to false, including operators; the menu never grants it.
Opening/access checks below always run before reading inventory contents.

Similar stacks share one icon in first-slot order. Optional white numeric TextDisplays
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
and two tuning pages. It works independently of the ordinary item-info mode.
Sparse personal overrides persist in LuckPerms meta
`arc-chest-preview-preferences`. Unset fields follow the current server defaults;
page/all reset removes those overrides semantically through the `default` sentinel.
Changes affect only the current player's private scene and apply on its next tick.

| Settings | Purpose |
| --- | --- |
| `enabled`, `scale`, `max-items`, `columns`, `cell-spacing` | Enable, overall size and grid layout (up to 54 icons and 9 columns) |
| `max-distance`, `vertical-gap` | Bounded targeting reach and height above the lid |
| `icon-scale`, `depth-scale`, `block-pitch`, `block-yaw` | Icon size, panel-local depth and fixed shallow ordinary block pose |
| `item-transform` | Native ItemDisplay GUI, FIXED or NONE model context |
| `show-counts`, `count-scale`, `count-offset-y` | Numeric label visibility, size and vertical position |
| `background-opacity`, `brightness` | Background alpha and native display light level |
| `teleport-ticks`, `stability-threshold` | Accepted legacy keys; unused by fixed placement and hidden from personal controls |

Defaults show all 54 distinct groups in 9 columns, with white numbers and no
backdrop. Scale is 0.9, cell spacing 0.36, icon size 0.28, count size 0.10 and
count offset −0.22. A partial last row keeps its column alignment. GUI uses
12° top tilt / 20° side turn and 15% depth. Explicit custom models and flat items
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

A scene acquires one world-fixed center in front of the selected container;
a viewer directly over its footprint starts above the lid. It reserves the configured maximum grid height above the container's support
plane, so adding rows cannot sink their counts into the floor. Initial placement
clears the viewer-facing vertical surface including the pitched grid and
icon/count depth, keeping ordinary views clear of a stacked container above.
It does not search free space above, beside or around neighbouring blocks.

The center remains fixed while looking around or changing item counts. Native
CENTER billboarding owns camera-facing rotation; the server sends no placement
updates during camera motion. Existing handles survive unchanged updates and
content/count changes. Another container or changed personal layout starts a
new scene at its destination with zero movement interpolation.

The center ray must remain in loaded/sent space and unobstructed. If it is
blocked or behind the camera, the scene hides while retaining its anchor and
handles, then reappears there when visible. Individual edges may be naturally
occluded by neighbouring blocks as the camera turns; they never force the
whole grid to drift or seek another location. Target/access checks still run
every tick for permitted viewers; others retain the ordinary five-tick cadence.
No see-through rendering is used. Native client appearance remains separate
from source geometry and lifecycle checks.

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

Shulkers opened by another player remain inspectable after the preview viewer
passes protection. Paper's `ShulkerBox.isOpen` reports the API-forced lid flag;
normal open inventories are detected with `inventory.viewers`. This inventory
access waits until protection and managed-container checks have passed; no slots
are read by the access check. Either actual viewers or the forced-open flag skip
the lid sweep, which could otherwise collide with the already extended lid.

Closed, unoccupied shulkers check the native half-block lid sweep with Paper's
`Entity.wouldCollideUsing`; its bounds match vanilla 26.1.2's opening check and
are deflated by `1e-6` to avoid counting touching faces. This also checks entity
collisions and the viewer's world border. The query's neighbouring chunks are
verified loaded and sent first. After the last viewer closes a shulker, its
closing lid may briefly suppress the preview until the sweep is clear.

PersonalLoot uses the viewer's persisted remaining loot rather than a shared
physical inventory. Its UUID marker must match on both halves, and the normal
container access checks and player admission cap still apply. A cold personal
record stays hidden until its read completes; confirmed missing records may
project a BetterStructures template only while `use-bs-loot` is enabled. A
preview never generates random chest loot, saves data, or advances reset state.
Unopened empty templates and exhausted records stay empty. ItemsAdder, Slimefun,
EliteMobs treasure chests, QuickShop shops, AutoSellChests and crate anchors
remain excluded: their raw inventories need not be the inventories a player
would see. ArcExcellentCrates supplies the typed
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
icon limits, ordinary and personal viewer isolation, cold personal lookup and
admission-cap behavior. Icon-renderer and controller checks
cover private audience, optional numeric labels, unchanged handle identity, independent
preferences, cleanup, fixed anchor/handle continuity, full 54-item grids and opacity. Configuration tests reload every
new setting; preference and HelpCenter tests cover sparse overrides and reset.

A source-derived offline preview can be exported during the placement tests by
setting `CHEST_PREVIEW_EXPORT_DIR`, then rendered with:

```bash
python3 -B scripts/render-chest-preview.py --geometry build/chest-preview-export/geometry.json \
  --ops-root /Users/alexey23/RusCrafting/ruscrafting-ops --output build/chest-preview-export/render
```

The adapter reuses the existing atelier renderer, native Minecraft models/textures
and font. Its views preserve aim at the container and include a dense stacked
container fixture, steep lower-side targeting and a partial row. They are frozen source poses, not native
client lighting, model loading, packet visibility or billboarding acceptance.

Native client acceptance after owner-controlled activation: grant only the test
account `arc.chest-preview`, compare an allowed chest and a denied Lands/WG chest,
point at each half of a double chest, remove the permission, change the contents,
and look away. Confirm the private card disappears and never appears for a
second player without the permission. Delivery without restart does not prove
that this behavior is active.

Platform references: [Paper Chest](https://jd.papermc.io/paper/1.21.11/org/bukkit/block/Chest.html),
[WorldGuard protection queries](https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/),
[Lands API](https://wiki.incredibleplugins.com/lands/developers/api).
