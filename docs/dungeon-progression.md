# Dungeon progression in ArcDungeons

The addon retains EliteMobs skill XP, weapon identity, class requirements and
existing active abilities. `/dungeon skills` and the adventure panel open a
native Paper Dialog with skill levels, XP, passive milestones, existing perks
and a personal library. Shift+F starts the dialog flow with its ArcDungeons
owner, including when the previous menu belonged to ARC.

## Completion books

One successfully completed instance can produce one personal book containing
10% of the player's accepted combat XP, rounded down separately per branch.
The automatic paired-weapon allocator records only actual recipient increments.
Armor stays independent. The book applies all displayed branches together;
there is no training-target choice, trade or conversion into an unrelated skill.
Books are collected in the library and need no inventory slot.

The completion ledger observes native post-eligibility XP. Cancelled events,
zero XP, spectators, foreign-world mobs and nonparticipants cannot create a
reward. Players without accepted XP and unfinished runs receive no book.
Native contribution and level eligibility remain authoritative. A higher-level
enemy is not, by itself, a reason to invalidate an otherwise eligible fight.
Open dungeon areas grant passive effects but have no instance-completion books.

EliteMobs 10.9.8 can dispatch `DungeonCompleteEvent` before the final kill's
`SkillXPHandler`. The addon seals at the outer death event's MONITOR priority;
non-kill completions use the next tick. Quit/kick and integration teardown flush
accepted completions before EmSync detaches. Each live instance ledger seals
once and uses a unique run ID.

Pending books and native XP share one existing EmSync snapshot (`xb`). Legacy
snapshots omit that field; an empty list explicitly records that all books were
consumed. Same-server restore applies the whole snapshot too. Accepted writes
remain serialized through quit, reconnect and owner retirement. Consumption
removes the entitlement and increments native XP on the server thread, then
persists asynchronously. Double clicks cannot reapply it; an unknown write
outcome is never retried as a new XP increment. Normal later snapshots retain
the current state. This uses the existing Redis durability boundary, not a new
cross-service transaction or an additional XP account.

## Passive effects

Every ten native skill levels adds one percentage point, capped at 10% at level
100. Effects require synchronized native data and active participation in an
ongoing native instance, or an installed native open-dungeon world.

* Armor mastery reduces accepted elite damage multiplicatively. It respects
  native defensive skills' aggregate 85% cap and preserves stronger reductions
  already applied by another owner. It does not affect environmental damage.
* Held melee skills use one owned transient attack-speed modifier; unrelated
  attribute modifiers and base values remain intact.
* Migrated native wands/staves shorten the managed main spell cooldown to
  `ceil(baseTicks / (1 + bonus))`. Other active/defense budgets, charge time,
  duel overrides and administrative prototypes are unchanged. The staff host
  now uses exact per-player tick deadlines instead of the former five-tick
  decrement. Cooldowns survive reconnect and expire without a new I/O path.
* Bows, crossbows and unmanaged FMM attacks retain their existing native perks;
  no unsupported projectile or draw-speed bonus is advertised.

See the [unit-separated balance assessment](dungeon-progression-balance-20261016.md)
for throughput limits, rounding and the small timing correction at zero mastery.

## Verification boundaries

Focused tests cover allocation, completion ordering, duplicate redemption,
uncertain persistence, same-server and reconnect snapshots, lifecycle teardown,
native defensive caps, weapon identity, exact cooldown admission, menu ownership
and dialog navigation. Menu exports can be rendered with the actual client font
and resource-pack frame assets at 640×360. That is offline layout evidence;
live plugin readiness and native-client gameplay acceptance are separate checks.

Sources: `DungeonProgression`, `DungeonMasteryEffects`,
`DungeonSkillProgressionMenus`, `DungeonSharedSkillXp`, `EmSync`, and the ARC host
`StaffCooldownClock`. Copy lives under `dungeon-qol.progression` in
`modules/elitemobs.yml`; new defaults are merged without replacing overrides.
