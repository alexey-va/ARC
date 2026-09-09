# Claim block guidance

`OnboardingModule` owns the transient `ClaimBlockGuide`. It runs only when
onboarding and `claim-guide.enabled` are enabled, in the configured onboarding
worlds, and with Lands available. All visible text lives in
`modules/onboarding.yml` under `claim-guide.text`.

Holding a native Lands claim block in either hand starts a personal guide:

- A short title explains placement; its subtitle and a retained chat line give
  `/unclaim` for removing protection from the chunk the player stands in.
- The ray-traced placement block (including the clicked face and replaceable
  blocks) determines the target when available; otherwise the player's current chunk is previewed immediately.
- Green outlines mean unclaimed land, cyan means the player's claimed land,
  and red means another owner's land. Wording accompanies each state.
- The item's radius determines the complete target footprint. The selected
  land's nearby outer borders are also cyan; internal edges and artificial
  edges at the local view cutoff are omitted.
- Only `ChunkPostClaimEvent` produces successful protection feedback. A batch
  of radius claims shares one title; separate targets reset confirmations.
  The final consumed block retains the result for five seconds.
- Removing the held item, death, world change, quit, reload and shutdown clean
  up displays. Titles are short-lived and pickup notices have a 30-second
  cooldown. The action bar alternates contextual help with removal guidance.

The guide is read-only: Lands remains responsible for permissions, placement,
claims, region selection, limits and all monetary effects. Green means no Lands
claim exists there; it does not bypass other placement restrictions. `/unclaim`
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

Item radii 0–4 are recognized (at most 81 queried chunks). The additional
selected-land view is a 3 × 3 neighborhood. Each border edge is a bright ribbon 0.60 blocks high and 0.35 blocks thick,
centered at the player's eye height, with 2.40-block end posts for visibility.
Height changes move existing displays instead of respawning them; entity origins
stay inside the represented chunks. Terrain height is never sampled. Existing region data is rechecked every five ticks while
holding the item; unchanged geometry is reused.

Legacy material-only claim items and special radii above four do not activate
this preview. Ordinary renamed blocks never activate it. This avoids presenting
an incorrect area for items without a supported native radius contract.

Focused checks: `ClaimBlockIdentityTest`, `ClaimGuideGeometryTest`, and
`OnboardingConfigTest`. A package/compatibility check does not establish actual
client appearance; an in-game visual check must be reported separately.
