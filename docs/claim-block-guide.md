# Claim block guidance

`OnboardingModule` owns the transient `ClaimBlockGuide`. It runs when
`claim-guide.enabled` is enabled, in the configured onboarding worlds, and
with the Lands plugin enabled. The general onboarding `enabled` flag controls
outcome hints independently. All visible guide text lives in
`modules/onboarding.yml` under `claim-guide.text`.

Holding a native Lands claim block in either hand starts a personal guide:

On owned or member land, the independent Lands UI tool now provides a six-action personal
world menu and suppresses the old placement label. This menu also works when
onboarding is disabled; its lifecycle and settings are documented in
[Lands UI](lands-ui.md). Outside eligible land the placement guidance below
remains active. Looking at the close panel temporarily suppresses the glow of
the grid, walls and posts; the geometry remains visible. A member land uses the
protected-area hint rather than «Здесь уже занято».

- The ray-traced placement block (including the clicked face and replaceable
  blocks) determines the claim target when available; otherwise the player's
  current chunk determines it. The thin chunk highlight is independent of this target.
- The hologram appears four blocks ahead of the eyes immediately, follows the
  gaze every tick with two-tick client interpolation, and names the selected
  land when expanding. Land names are inserted as literal text.
- `claim-guide.grid-mode: CURRENT_CHUNK` is the default experiment to reduce
  visual noise: only the four thin edges of the 16 × 16 chunk under the player
  are drawn. Optional vertical corner posts are restricted to its four corners.
  `NEARBY_GRID` restores the previous nearby lattice and corner posts. Both modes
  use the existing personal color, height and snapping settings.
- Chunk lines are light blue by default. Real owned/member land borders are
  green; other owners' borders are red. `claim-guide.land-walls-enabled` keeps
  the existing translucent walls above those real boundaries in either grid
  mode. Internal edges and artificial edges at the local view cutoff never become walls.
- The item's radius still determines the complete target footprint for the
  placement message. Nearby land boundaries are queried independently of the
  thin-grid mode, using the greater of the item's radius and the display radius.
  Display settings label this radius as the minimum boundary-view area.
- If Lands is enabled but `LandsIntegration.getWorld(world)` returns `null`,
  the guide remains visible and displays `В этом мире приват недоступен` ahead
  of the player. It skips claim/selected-land queries in that world; the neutral
  chunk highlight never implies that claiming is allowed. The existing Lands
  UI menu hint remains available. A missing/disabled Lands plugin still prevents
  the guide from being attached at all.
- Only `ChunkPostClaimEvent` produces successful protection feedback. A batch
  of radius claims shares a confirmation; separate targets reset confirmations.
  The final consumed block retains the result for five seconds.
- Removing the held item, death, world change, quit, reload and shutdown clean
  up displays. Grid and text changes add no titles, action bars or chat messages.

The guide is read-only: Lands remains responsible for permissions, placement,
claims, region selection, limits and all monetary effects. A chunk highlight
does not bypass other placement restrictions. `/unclaim`
removes the current chunk, not the whole settlement.

## Compatibility evidence, 2026-09-09

The active survival artifact was read and inspected without mutation:

- Lands 8.4.3, `/home/freedeeml/McFine/classic_survival/plugins/Lands-8.4.3.jar`
- SHA-256 `fd8811180222302dd9f384e9443974ed0b3573496e6342c03e9a1ffee3afc16a`
- Native PDC: `lands:type` STRING `CLAIM_BLOCK`, `lands:radius` INTEGER.
- `getLandByUnloadedChunk`, `getLandPlayer`, `getEditLand(false)` and the
  post-claim event are available. No world/chunk loads are requested for borders.
- The artifact descriptor registers `/unclaim` directly.
- The tracked CMI starter kit grants radius-zero claim blocks explicitly;
  radius one is supported too and covers 3 × 3 chunks.

Native display visibility/scaling uses the
[Paper display entity contract](https://docs.papermc.io/paper/dev/display-entities/).
Displays are hidden by default before spawn, shown only to their viewer,
nonpersistent, full-bright, and bounded to loaded terrain.

## Deliberate bounds and verification

Item radii 0–4 are recognized (at most 81 target chunks). The personal display
radius defaults to two chunks in each direction and is bounded to 1–5. Lands
lookups include one extra neighbor ring so the visible cutoff cannot produce
false perimeter walls. Thin lines are 0.05 blocks wide and 0.045 blocks high;
real perimeter strokes are 0.14 wide and 0.20 high. Existing walls are 3 blocks
high and 0.04 thick, while optional posts are 8 blocks high and 0.05 wide.
Height changes move existing displays instead of respawning them. Terrain
height is never sampled. Existing region data is rechecked every five ticks
while holding the item; unchanged geometry is reused. Module reload reconstructs
the guide and rereads its mode and messages.

Legacy material-only claim items and special radii above four do not activate
this preview. Ordinary renamed blocks never activate it. This avoids presenting
an incorrect area for items without a supported native radius contract.

Focused checks: `ClaimBlockGuideTest` covers the scheduled unavailable-world
guide and item-removal cleanup; `ClaimGuideGeometryTest` covers both grid modes,
negative/positive chunk boundaries and unchanged real-land walls.
`ClaimBlockIdentityTest`, `ClaimGuideDisplayTest` and `OnboardingConfigTest`
cover item identity, display movement and configuration. A package check does not establish actual
client appearance; an in-game visual check must be reported separately.
