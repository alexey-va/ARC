# Personal dungeon filler loot balance

The change creates persistent item rewards when a player has an eligible personal chest with up to four available slots; four slots are used for the modeled usual case. Each reward is one COBWEB, COBBLESTONE, DIRT or STICK, at most once per player/chest. The existing decorative icons are not claimable rewards, so item issuance is limited by the available slots and is zero for exhausted legacy records. The reward itself mints **0 Vault and 0 tokens**.

The low/base/high scenarios are explicit sensitivities, not telemetry. They use 1/3/5 participants and 0.25/1/4 eligible chest opens per player-day for seven days. `maxPlayers=5` applies per chest; these participant counts describe the modeled cohort, not a network cap. Each modeled chest has the usual four free slots and gives one of every filler item.

| Scenario | Participants | Opens/player-day | Items/player-week, each material | Items/cohort-week, all four | Tracked shop sale if every saleable item is sold |
|---|---:|---:|---:|---:|---:|
| Low | 1 | 0.25 | 1.75 | 7 | 36.8375 Vault |
| Base | 3 | 1 | 7 | 84 | 442.05 Vault |
| High | 5 | 4 | 28 | 560 | 2,947 Vault |

The sale sensitivity uses tracked EconomyShopGUI pages: COBWEB sells for 20.00 Vault, COBBLESTONE for 0.05 and DIRT for 1.00. No plain STICK sell row appears in the tracked `shops/*.yml` pages, so it adds item supply but no direct shop-sale value in this model. A parallel flat `shops.yml` has a STICK row at 0.29 Vault. The economy inventory calls the split pages working, but the flat file’s current runtime load status was not verified; if that row is active, the sensitivity rises by 0.5075/6.09/40.60 Vault per cohort-week. The three split-page rates are configured source values, not material-specific live rates. The parent’s filtered seven-day live audit showed the `adminShopSales` source active (142,554.40 Vault, 7,669 items, 8 transactions), but did not return any of these four materials among its 12 ranked rows; that does not prove the materials had no sales. EconomyShopGUI SELL remains the modeled current route; the planned SELL-to-contract transition is not treated as complete.

An open `guild_cobblestone` order was confirmed in the parent’s live read at 0.17 Vault per accepted cobblestone, with a 64-item minimum per player submission and 7,500 Vault weekly budget. It is an alternative route, not extra income stacked on the 0.05 shop value. The modeled loot alone gives an individual 1.75/7/28 cobblestone per week, below the minimum. A player can add this loot to cobblestone acquired elsewhere and redeem a qualifying submission; the active order therefore remains a possible marginal route, with actual redemption depending on the player’s other inventory and the order window.

The assessment JSON separates item counts, direct zero-currency rewards, and downstream sale sensitivity by currency. The single-replica economy read used by this worker reset its connection, so the item-specific sell values remain labeled as tracked configuration; the parent’s successful audit and contract snapshot supply the live evidence above.
