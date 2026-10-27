# Staff spell PvP

Ordinary player targets use the configured vanilla damage, attributed directly to
the caster through `LivingEntity.damage(amount, caster)`. EliteMobs damage scaling
and enchantment multipliers remain on the PvE route. World PvP, self/NPC/dead/
invulnerable targets, line of sight, current casts and duel isolation are checked
at selection and final impact. Duel damage keeps its existing multiplier/policy.
Summoned volleys retain their player damage budget outside duels as well.

For ordinary spell PvP, `StaffSpellLandsProtection` checks `ATTACK_PLAYER` for
both player UUIDs at the victim location through `LandWorld.hasRoleFlag(UUID,
Location, RoleFlag)`. This overload reads the actual role flags without general
`lands.bypass.*` permissions. Those global permissions are intentionally preserved.
An unmanaged world or absent Lands provider adds no Lands restriction; an enabled
provider that cannot answer fails closed. The optional adapter loads only at use.
Native entity damage events still enforce remaining Lands/region/team rules.

Verified against live Lands 8.9.6 on Survival, descriptor-resolved
`plugins/Lands-8.9.6.jar`, SHA-256
`b0057ed700bda1ed190beb4b7d8e38ead8f2c4221999d8021f1445947ad273ca`.
Compile dependency LandsAPI 7.25.4 exposes the same consumed overload. No plugin
load-order edge or first-party shared API contract was changed.

Focused tests: `StaffSpellModeledDamageTest`, `StaffSpellLandsProtectionTest`,
and `StaffSpellSummonsTest`. They cover ordinary configured damage and native
cancellation, role-denied/allowed paths and absence/unmanaged worlds, self/world
PvP, duel isolation and existing summon budgets. Real-player combat acceptance
is a separate runtime observation and is not established by the tests.
