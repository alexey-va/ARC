# Legacy dungeon reward migration

The owner requested automatic replacement of old enchantment boxes, Slimefun
boxes and coin bundles. On 2026-10-18 the live plugin registry confirmed Denizen
7302 on Spawn and no Denizen on Survival; the owner selected ARC on both nodes.

## Identity and replacement

| Legacy signature | Current preset | Reward identity |
| --- | --- | --- |
| IRON_INGOT / 12033, `Шкатулка с чарами` or `Токен элитных чар` | `ae_lootbox` | `ae` |
| IRON_INGOT / 73026, `Лутбокс слаймфана` | `sf_lootbox` | `sf` |
| STICK / 11135, `Горсть монет` | `money_handful` | `smallmoney` |
| STICK / 11136, `Кучка монет` | `money_pile` | `mediummoney` |
| STICK / 11137, `Куча монет` | `money_heap` | `bigmoney` |
| STICK / 11138, `Мешок монет` | `money_bag` | `hugemoney` |

Material/model alone is insufficient: ItemsAdder uses the same models for
decorations. Markerless matching requires the historical name and use lore;
compatible native reward data can identify already-tagged legacy stacks.
Foreign custom data, PDC, conflicting reward identities and modern roll metadata
are excluded. The old SEA_LANTERN / 10000 fallback has no verified markerless
name; that shape is accepted only with compatible native SF identity.

The native `ItemPresets` factory is authoritative. One valid replacement must
retain the exact stack amount and expected treasure key before replacing the
same slot. No give/remove command or full-inventory overflow path is involved.
Missing or invalid templates leave the original intact. There is no world,
offline inventory or recursive container scan. Normal player inventory events
share the existing bounded next-tick refresh; the use path also normalizes the
actual main-hand stack before opening it.

Live evidence included markerless IRON_INGOT / 12033, quantity 2, with the
screenshot name, and STICK / 11136, quantity 4, named `Кучка монет`. Both had
`Нажмите ПКМ для использования` lore. The source predecessor is ops
`classic/plugins/Denizen/scripts/coin_bonus.dsc`, procedures at lines 327–405
and the old two-click repair at lines 532–600.

## Balance assessment

This is a one-for-one recovery of existing bearer items. Migration itself
grants **0 vault, 0 premium tokens, 0 EliteMobs crystals and 0 XP**. It preserves
the coin tier and does not change reward pools, probabilities or preset values.
Recovered items can subsequently be redeemed once through the normal opener.

The live native catalog on both nodes, checked on 2026-10-18, gives AE **1–2**
draws (EV 1.5) and SF **4–5** draws (EV 4.5). Local AE YAML still said 4–5:
copying that local value into migration would be wrong. Previously keyed legacy
boxes without roll metadata had one draw; normalizing those changes their draw
count to the current preset. Broken markerless stacks had no ARC redemption.
Modern containers already carrying roll metadata retain their own contract.

Live coin pools are uniform: small **1,500–3,000** (EV 2,250), medium
**4,000–5,500** (EV 4,750), big **7,500–8,000** (EV 7,750), huge
**8,000–9,000** (EV 8,500), all in vault coins. Historical `money_token_mean`
fields and tooltip averages are not the executing payout rule. This migration
does not adjust either the pool values or unrelated tooltip text.

The accompanying JSON models recovered *previously unusable* items. Low/base/high
are sensitivity assumptions: 1/5/20 participants each redeems 0.25/1/8 existing
items of each category per active day. These are not measured acquisition rates
or a newly recurring source. Under those assumptions total recovered coin
redemption is 5,812.5/23,250/186,000 vault per participant-day and
40,687.5/813,750/26,040,000 vault per network-week. Each category remains a
separate flow; AE/SF draws are not converted into currency. Previously functional
coin tokens retain the same pool and have zero incremental payout per token.

Stock size, dungeon acquisition rate, participant share and downstream resale
are unknown. The true upper bound is the number of existing or newly looted
eligible bearer units; reprocessing a migrated stack creates none. No new price,
progression cost, tax, boost, permission or acquisition route is added. No
investment yield or future contract income is counted. Current SELL availability
and the planned SELL-to-contract transition are unchanged; item redemption and
sale of the same item must not be counted twice.

The read-only shared ledger sample had an exact seven-day boundary
`1791747553216..1792352353218`, 12,803 records, 66,222 operations and 32 players;
139 records remained unknown/legacy. It does not measure legacy item stock or
conversion frequency. Mixed-currency aggregate totals were not used.

Verification must distinguish source tests, delivered JAR, loaded runtime and
observed player behavior. Matching, collisions, amount preservation, missing
factory results and repeated conversion are the focused regression boundaries.
The relevant Paper event contract is documented by
[PlayerInventorySlotChangeEvent](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/event/player/PlayerInventorySlotChangeEvent.html).
