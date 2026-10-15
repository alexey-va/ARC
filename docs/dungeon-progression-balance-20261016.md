# Dungeon progression: balance decision before implementation

Date: 2026-10-16. This records the proposed implementation, not live activation.

## Personal books

One completed native instance can award one personal book. Each branch in the
book contains floor(accepted native combat XP earned by that player in this run
/ 10). Paired XP is counted after allocation, once in each actual recipient;
the total book budget is at most 10% of accepted combat XP. Armor remains its
own branch. No arbitrary branch conversion, AFK participation award, global
XP multiplier, purchase, trade, resale, class unlock or native eligibility bypass.
Open-world kills do not create completion books. Repeated completion notifications
cannot mint another reward from the same captured run.

Books are collected automatically in the player's progression window. They
do not require an inventory slot. A book grants the displayed branch amounts
together, once. Existing EmSync stores pending entitlements with native XP in
one snapshot; all network persistence stays asynchronous. Unknown persistence
outcomes must not repeat an increment. Old books cannot recursively earn books.

Hypothetical low/base/high: 1/3/6 completed runs per active player-day,
1,000/10,000/100,000 accepted skill XP per run, 5/20/50 participants. This is
sensitivity, not telemetry: added XP is at most 100/3,000/60,000 per player-day;
books 1/3/6. Rounding can only reduce issuance. Incomplete runs add zero books.
At a fixed existing combat XP rate, the upper total XP multiplier is 1.10,
so the theoretical time to a fixed XP goal is at least 1/1.10 = 90.91% of before.
Native XP boosts are already included in the accepted event and are not
applied again when consuming a book.

## Passive mastery

Every ten native skill levels adds one percentage point, capped at ten points
at level 100. No extra selection or new level currency. Bonuses work only in
eligible native dungeon contexts, with the skill system and synchronized data
ready. Existing active abilities, unlock requirements and selection stay intact.

Armor mastery multiplies accepted native elite damage by (1-bonus). Native
selected Battle Hardened at 20% plus mastery 10% gives 28% total reduction, not 30%.
Preserve native 85% maximum skill reduction; never increase damage a different
owner already reduced further. Dungeon mastery does not apply to PvP/environment.

Supported held melee weapons receive a namespaced transient attack-speed
modifier; do not rewrite base values or other owners' attributes. Supported
wand/staff spell casts use ceil(baseTicks/(1+bonus)), so speed is at most 10%
higher and damage, charge time and distinct active/defense cooldowns stay intact.
Bow/crossbow draw and unmanaged native FMM cooldowns have no established hook:
do not describe a cosmetic attribute as actual firing speed. Their native perks
remain available. Duel settings and administrative prototypes are excluded.

Upper direct throughput sensitivity for eligible attacks: 1.00 -> 1.10, before
animation, accuracy, resource and enemy constraints. Combining maximum supported
speed and completion books yields at most 1.21 times hypothetical skill XP per
combat-time; defense can also change survival/time and is not priced as zero.
Real throughput and income remain unmeasured.

The existing staff host used a shared five-tick decrement timer. That can erase
small speed bonuses and distort ratios at bucket boundaries. Before changing
its code, the scoped decision is to give ArcStaffCooldowns exact per-player
server-tick deadlines, preserving all configured base tick counts and the same
start/remaining/clear API. Generic CooldownManager stays unchanged. Relative to
the old timer's phase, the observed interval can shift by up to four ticks even
at zero mastery; configured tick budgets become the actual admission contract.
This is not represented as zero gameplay impact or as measured DPS.

## Separate economic units and observation

Direct deltas are zero individually for Vault, premium tokens and EliteMobs
crystals. Book entitlements and native skill XP are separate nonmonetary units;
there is no exchange rate, resale value or new shop cost. Existing SELL is live,
the SELL-to-contract transition remains future work. Neither flow is modified.

Fresh Spawn economy audit requested 168 h at 2026-10-15T22:36Z: SQL ready,
32,901 retained events, no pending events; returned currencies are Vault/tokens.
The audit does not measure dungeon native XP, crystals or weapon throughput.
Do not use mixed-currency totals to judge these changes. Existing canonical
economy inventory was read; update its affected entry with verified delivery.

Required checks: exact per-run/branch conservation, cancellation, native zero,
paired share, nonparticipant/spectator, duplicate completion and consumption,
same-server and cross-server reload, delayed/failed persistence, closed owner,
foreign modifier preservation, mastery bounds and native defensive cap.
