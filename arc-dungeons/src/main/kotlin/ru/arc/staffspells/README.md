# Staff spell PvP

Ordinary player targets use the configured vanilla damage, attributed directly to
the caster through `LivingEntity.damage(amount, caster)`. EliteMobs damage scaling
and enchantment multipliers remain on the PvE route. World PvP, self/NPC/dead/
invulnerable targets, line of sight, current casts and duel isolation are checked
at selection and final impact. Duel damage keeps its existing multiplier/policy.
Summoned volleys retain their player damage budget outside duels as well.

Ordinary spell PvP uses ARC's `ArcPvpService`, registered by `DungeonHostServices`.
The shared owner is `src/main/kotlin/ru/arc/pvp/`: its settlement rule checks only
the negative `arc_no_pvp` natural flag on each player's settlement default area.
That one prohibition covers all subareas and both sides of a border. Missing/false
means allowed by the settlement policy. There are no Lands role, membership,
nation or alliance checks in this policy. Native Lands PvP bypass grants remain.

The same service preserves native justTeams cached team settings, including
friendly fire, allied teams and source-specific projectile/cloud/TNT restrictions.
Staff eligibility and continuing crowd control query it silently before affecting
a player. Deliberate offensive input/direct geometric contact reports a denied
attempt through the shared two-second per-player message cooldown. Passive aim
and nearby acquisition are silent. Native damage listeners retain their vetoes;
the policy never uncancels another plugin's event.

`arc_no_pvp` first registers from ARC `onLoad`, before Lands enables, so its first
installation needs a full Paper restart. Module reload reuses the registered flag.
An enabled provider that cannot answer fails closed with an operator diagnostic.

Verified against live Lands 8.9.6 on Survival, descriptor-resolved
`plugins/Lands-8.9.6.jar`, SHA-256
`b0057ed700bda1ed190beb4b7d8e38ead8f2c4221999d8021f1445947ad273ca`.
Compile dependency LandsAPI 7.25.4 exposes the consumed natural-flag APIs. Native
justTeams 2.8.1 cached getters and veto semantics were inspected separately.

Focused tests include `StaffSpellModeledDamageTest`, `StaffSpellPvpProtectionTest`,
and the root `ru.arc.pvp` policy tests. Keep the existing summon budgets and duel
behavior. Real-player combat acceptance is a separate runtime observation and
is not established by these tests. Native justTeams may remove a protected cloud
target before ARC observes the event; that veto remains intact, but ARC cannot
invent a refusal message for a target that is no longer in the event.
