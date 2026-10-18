# Personal-loot identity recovery

The recovery repairs conflicting double-chest identities while preserving the
original `player:::chestUuid` inventories. Existing claims and leftovers stay
authoritative; repair, retry, restart and splitting/rejoining must not create a
new reward. A viewer with leftovers under both historical identities can collect
those existing records sequentially. New viewers receive one normal reward for
the shared group, subject to the existing union-of-players limit.

Live configuration checked on 2026-10-18: `max-players=5`, 3–5 common selections,
one independent 10% special selection, and up to four existing debris items.
`default` and `generic_bs` select `structures_common`. Both loaded pools were
healthy: 64 common entries and five special entries, each with selectable weight
100. Rates, pool content, quantities and prices are unchanged.

The [assessment](personal-loot-recovery-balance-20261018.json) compares currently
blocked encounters with restored access. Its low/base/high cohorts are explicit
assumptions, not telemetry: 1/3/5 participants and 0.25/1/4 distinct eligible
groups per player-day. The resulting weekly recovery is 7/84/560 common
selections, 0.175/2.1/14 expected special selections and at most 7/84/560 debris
items. These are different item measures, not money. Existing remaining loot is
not counted as a new mint. Direct deltas are separately zero Vault, tokens,
Elite Coins and XP. No earning multiplier, price or progression cost changes.

The observed shared ledger covers 12,869 records and 66,306 operations across
31 players, with 139 unclassified records. It does not establish how many broken
chests are encountered. Resale and redemption values are not invented; current
SELL and the planned transition to contracts stay unchanged. Selling and
contracting the same item cannot both be counted.

Acceptance requires preservation of both original records, a shared claim cap,
no reroll of exhausted/missing claimed data, protection checks on both halves,
durable recovery before native mutation, and retry/restart/concurrent-open
regressions. Source tests, artifact delivery, activation and a real player
opening the repaired chest are separate evidence.

Validation:

```sh
python3 -B ../ruscrafting-ops/.agents/skills/balance-ruscrafting-economy/scripts/evaluate.py docs/personal-loot-recovery-balance-20261018.json
```
