# Staff spells — ArcDungeons

`/arc stafftest` issues sixteen administrator-only mechanics samples.
`inferno` is temporarily retired; `gravity` and `ossuary` remain available. Use
`/arc stafftest <id> [player]` for one sample or `/arc stafftest all [player]`
to issue the set after checking inventory space. The items carry ARC prototype
metadata; they are not FMM or EliteMobs loot, and this catalog adds no shop,
reward or economy changes.

Four primaries — Frost, Fault, Lunar and Tide — use substantial frontal area
attacks with a seven-block reach. The other twelve keep their ranged, targeted,
summoned or charged primary mechanics; Solar is a piercing ranged spear again.
Secondary attacks retain distinct ranged, targeted or radial roles. Native particles accent stable
display bodies; the last sent pose collapses before cleanup.

## Controls

Every staff uses right-click for its primary attack and Shift + right-click for
its secondary attack. Ordinary attacks cast immediately. Ember and Icicle use
hold-and-release right-click for their primary: hold to charge, then release to
launch. Full charge takes 36 ticks (1.8 seconds); their secondary casts immediately.
Every staff uses left-click for frontal defense and Shift + left-click for its
absorption shield.
The vanilla client sends the native stop-using packet for these held attacks;
there is no click-toggle substitute or inferred left-button release timeout.

Changing Shift, held slot/item, world or player lifecycle cancels an active
charge. An accepted action starts a four-tick (0.2-second) global cooldown.
Primary, secondary, Q and F each keep a separate deadline per player and staff
family, so another ability is available after that short interval. Duplicate
items or slot swaps do not bypass the same ability's deadline. Mastery adjusts
primary/secondary/channel recovery once using the captured cast. Item lore and the HUD name the actual controls of
the held staff, including existing prototype and EliteMobs items.

The personal setting is available at `/menu` → «Настройки» → «Интерфейс» →
«Посохи»: hologram, top-screen bossbar, or off. Hologram placement has a separate
«Сверху» / «Снизу» choice and defaults to bottom. Both preferences persist in
LuckPerms metadata (`arc-staff-hud-mode`, `arc-staff-hud-placement`). A mode
change removes the prior surface on the next HUD update. The display shows
the selected ability, controls, charge and remaining attack cooldown; the
independent defense capacity bar retains its own lifecycle.

The server controls the base hologram pose through `hud.position`
settings: forward, horizontal and vertical displacement and scale. Top/bottom
uses the same vertical magnitude above/below the sightline. These settings
are read on module reload.

Gravity's primary uses held right-click: press to grab, release to throw. Shift + right-click instead creates a caster-centered gravity pulse.
Inferno is temporarily retired from issuance and casting. Existing EliteMobs
Inferno assignments migrate once to Ember; old prototype IDs remain recognizable
but cannot cast.

Gravity selects a visible solid block within its reach, holds a visual boulder
in front of the caster as the aim moves, and throws it when right-click is released. The
boulder is a visual effect: Gravity does not move, break, place or replace
world blocks. If the selected surface or safe path is unavailable, the grab is
rejected.

For every staff, left-click raises the frontal guard; Shift + left-click raises its
all-around absorption shield. Defenses have their own recovery track, independent of the
right-click attack cooldown. A frontal guard covers two hits for 1.2 seconds,
or three hits for 1.6 seconds for Fault, Gravity, Ossuary, Nova and Necromancer.
The all-around shield lasts twenty seconds and absorbs eight HP after armor and
resistance, before native absorption hearts. Overflow continues through normal
damage processing. Its bossbar shows remaining capacity. Recovery is four
seconds for frontal and twenty-five seconds from casting the shield. These two
abilities have separate recovery tracks, each shared across staff families to
prevent refreshing the same protection by changing weapons.
The frontal guard is a 12-part kite-shaped packet BlockDisplay shield with a transparent face;
Shift + left-click assembles four angled plates (40 parts) around the player. Material families
follow the staff element. The stable displays attach to the player, interpolate their opening,
recoil and collapse, and share the existing scene/part budgets. Native particles appear only
on a blocked hit or shield break. The center stays transparent and the raised outline leaves
the reticle readable. `StaffDefenseGeometry.kt` owns the shape used by both runtime and
`exportStaffRedesignPreview`; offline previews do not establish native-client acceptance.

Changing staff cancels the active defense without resetting recovery. The same
attack and defense buttons apply to instant and held staves.
Staff interaction cannot break blocks, including in
creative mode, and native melee damage is suppressed while a prototype staff
is held.

## Q techniques and F mobility

Q is a staff technique; F is themed mobility in both ordinary worlds and magic
arenas. ArcDuels no longer registers its former Q Echo or standalone F Blink.
Shift + F remains the main-menu shortcut. A staff consumes world Q/F without
vanilla item drop or hand swap; inventory drop controls remain native. Controls
are denied during duel preparation, countdown, spectator and locked states.

| Staff | Q | F |
| --- | --- | --- |
| Chain | Steerable lightning orb; Q again detonates | Spark blink |
| Mark | Tether punishes leaving its anchor | Rift blink |
| Frost | Shatter the caster's existing slows | Ground ice slide |
| Lance | Pin an enemy near the wall behind it | Backward vault |
| Ember | Record a short trail; Q ignites it | Backward fire recoil |
| Nova | Rooting seed trap | Pull toward a visible solid anchor |
| Icicle | Compact 4.5-block radial crystal burst | Ice-step double impulse |
| Fault | Magic-cover wall; Q shatters it forward | Ground rush |
| Lunar | Wide outbound and returning crescent | Short phase blink |
| Gale | Sideways gust corridor | Brief steerable glide |
| Tide | Water capsule breaks on accepted damage | Forward surf |
| Requiem | Timed bell; Q strikes during its active window | Bell blink |
| Prism | Triangle damages enemies crossing its edges | Steerable speed burst |
| Necromancer | Focus existing archers or rally them | Soul blink |
| Gravity | Hold one enemy; Q throws it | Ballistic leap |
| Ossuary | Closing bone-jaw trap | Bone-ridge rush |

Q and F have separate per-player, per-staff-family cooldowns, retained when
changing staves or reconnecting. A four-tick GCD prevents same-tick bursts; round
reset clears the round's cooldowns. Per-staff defaults live at
`<staff>.hotkeys.q.cooldown-ticks` and `f.cooldown-ticks` in `staff-spells.yml`.
The existing HUD and item lore expose Q/F availability and continuation state.
Target-dependent Q techniques also play their authored visual in a bounded aim
point when no eligible enemy is present; combat still requires real eligible
targets. Icicle charge starts with no visible shards and grows up to eleven;
an exactly zero-charge release cancels without spending its primary cooldown.

`StaffSpellConjurations` and `StaffSpellTechniques` own Q lifecycles;
`StaffSpellMobility` owns F. All reuse the existing cast authorization and
bounded `StaffSpellDisplayEffects` pool. Physical movement checks headroom,
loaded chunks, safe landing and arena bounds; blinks may cross intervening
cover but cannot land outside the allowed arena. Virtual Fault cover blocks
staff rays, not ordinary projectiles or player movement. World blocks remain
unchanged. Cancelled or expired casts release their own control effects.

Focused checks: `:arc-dungeons:test --tests ru.arc.staffspells.StaffSpellInputTest`,
`StaffSpellMobilityTest`, `StaffSpellConjurationsTest`,
`StaffSpellTechniquesRuntimeTest`, `StaffMagicBarriersTest` and
`StaffHotkeyCooldownsTest`; ArcDuels' `KitRegistryMagicTest` covers the policy
adapter. `:arc-dungeons:exportStaffHotkeyPreview` exports 32 Q/F geometry clips
from production helpers with explicitly synthetic sample paths. It does not
prove native movement, collisions, sound, damage or client appearance.

## Catalog

| ID · staff | Primary attack | Shift attack | Frontal guard | Absorption shield | Existing ItemsAdder skin |
| --- | --- | --- | --- | --- | --- |
| `chain` · Грозовая ветвь | Lightning jumps between up to four enemies | Грозовой круг expands a jagged lightning front eight blocks around the caster, blocked by cover | Грозовой заслон | Круговой покров | `3dfantasyweaponscit:divine_staff` |
| `mark` · Разрыв | A targeted void rupture follows the enemy, then explodes | Чёрная дыра pulls eligible enemies, then collapses | Покров пустоты | Круговой покров | `3dfantasyweaponscit:night_staff` |
| `frost` · Ледяной хлопок | A broad field of clustered ice teeth rises in front, reaches seven blocks and briefly slows enemies | Полярный круг expands an ice front eight blocks from the caster | Ледяной щит | Круговой покров | `3dfantasyweaponscit:northgate_guardian_staff` |
| `lance` · Солнечное копьё | A ranged solar spear pierces up to three enemies, applying damage when its light front arrives | Three diverging solar beams | Солнечный щит | Круговой покров | `3dfantasyweaponscit:sagrada_winged_staff` |
| `ember` · Пепельная комета | Fireball grows below the reticle with four-tick interpolation, then launches immediately with forward speed and accelerates | Three ground circles lock on cast; the first meteor launches after a 15-tick warning | Огненный заслон | Круговой покров | `3dfantasyweaponscit:bermunde_hermit_staff` |
| `nova` · Терновый хват | Seven thick ground roots fan forward up to 18 blocks and grip each accepted victim | Терновая клетка warns around the aimed 4.5-block area, then closes inward | Терновый заслон | Круговой покров | `3dfantasyweaponscit:holy_celtic_staff` |
| `icicle` · Ледяной венец | An irregular crown grows from zero to eleven icicles and retains the same bodies on release. The volley focuses one visible enemy; after losing it, airborne and delayed shards seek another near the current reticle, within their remaining travel budget | Five shards fall around the aimed point immediately | Кристальный заслон | Круговой покров | `3dfantasyweaponscit:olwen_staff` |
| `fault` · Обсидиановый раскол | A broad basalt and obsidian rupture rises in front up to seven blocks and pushes enemies back | Каменный выброс warns, then erupts at the aimed point and lifts enemies | Каменный бастион | Круговой покров | `3dfantasyweaponscit:staff_of_unholy_wisdom` |
| `lunar` · Лунный серп | One large moon crescent cuts across a seven-block frontal area | Two moon blades converge on the target | Лунный покров | Круговой покров | `3dfantasyweaponscit:black_snake_staff` |
| `gale` · Посох бури | An open tornado wanders irregularly around the aimed point and lifts enemies along its path | Шквал follows full-pitch aim up to 56 blocks, including upward; contact with an enemy, cover or the endpoint triggers the burst | Ветряной щит | Круговой покров | `3dfantasyweaponscit:goblin_witch_staff` |
| `tide` · Сердце прилива | A thick curling breaker covers a seven-block frontal area and pushes enemies back | Приливный вал is a broad curled wave with a foamy crest that travels forward | Водяной заслон | Круговой покров | `3dfantasyweaponscit:northgate_guardian_staff` |
| `requiem` · Реквием | A copper-and-gold bell forms above the aimed point, tolls at 0.8 seconds and releases a five-block soul wave | Погребальный перезвон suspends the bell above the caster and releases three seven-block waves, with five-second minimum recovery | Покров душ | Круговой покров | `3dfantasyweaponscit:lich_staff` |
| `prism` · Кристальная призма | A crystal grows at the aimed point and refracts beams into up to three enemies | Three rays converge on the selected point | Гранёный щит | Круговой покров | `3dfantasyweaponscit:northgate_golden_crook` |
| `necromancer` · Зов могилы | Adds two skeleton archers for 45 seconds; up to ten minions can coexist | Костяной жнец summons one larger wither-skeleton commander | Костяной заслон | Круговой покров | `3dfantasyweaponscit:lich_staff` |
| `gravity` · Гравитационный захват | Grab a surface within 32 blocks, hold the same boulder, and release into a fast accelerating throw up to 32 blocks | Гравитационный обвал lifts visible enemies within 6.5 blocks, then slams those that actually rose; contact damage occurs once on landing | Каменный заслон | Круговой покров | `3dfantasyweaponscit:staff_of_unholy_wisdom` |
| `ossuary` · Костяной прокол | Dense bone-spike clusters erupt in sequence along a fixed 24-block forward path, blocked by walls | Костяной прорыв warns at the aimed point, then irregular branching bone clusters erupt across a 5.5–6 block radius | Щит рёбер | Круговой покров | `3dfantasyweaponscit:lich_staff` |

Each sample uses its configured ItemsAdder skin; issuance fails with a player
message and a server warning if that configured skin is unavailable. Duel
staff items are built separately on a stable native base. Their optional
client-pack appearance requires both a fully namespaced `duel-appearance.item-model`
and a positive `custom-model-data`; the bundled defaults leave both unset.

## Targeting and lifecycle

Launch aim assist is controlled by `targeting.aim-assist`: enabled, half-angle
(default 8 degrees), maximum miss (2 blocks), strength (0.70) and excluded spell
IDs. It nudges eligible forward projectiles toward a nearby visible target;
it preserves exact reticle hits, never grants a hit, and retains swept collision
and cover checks. Ground-travelling effects preserve their horizontal direction.
Primary ranged casts reach up to 56 blocks. Lightning uses this same launch
correction for its first projectile, with no persistent target lock; only a real
impact starts the existing homing chain jumps. Radial pulses, selected ground
zones and summons keep their own targeting; soft target selection uses an
eight-degree cone. Close attacks retain their seven-block reach.

Combat rays ignore passable vegetation and leaf blocks while preserving exact
solid-block collisions and magic shields. An exposed head or shoulder can
satisfy visibility; these sampled body points stay inside the existing hitbox
and do not enlarge damage collision.

Icicles share one live focus across airborne and delayed shards. A direct
reticle acquisition or real collision establishes it; an initial near miss
still receives only fractional launch assistance. Death, invalidation, cover
or moving the reticle away releases focus and discards the obsolete aim point.
A replacement must be near the current reticle, visible from the eye and shard,
ahead of the shard and reachable within its original remaining travel budget.
Soft replacement obeys the configured assist switches, cone (at most 12 degrees)
and maximum miss; retention allows 1.5 times those bounds, capped at 18 degrees.
With no replacement, airborne shards continue their current heading. Empty-space
launches retain their individual sampled aim. A blocked launch step may turn up
to 45 degrees onto an already clear straight line to the aim point; it cannot
cross a wall or shield. Each enemy still receives at most one damage hit per volley.

Frontal primaries contact once after six ticks, matching the interpolated full-size
pose. They select up to three eligible targets at contact within seven blocks,
a 110-degree front sector and 2.5 blocks vertically. Cover is rechecked from
both the cast origin and current player eye. Frost slows for 16 ticks; Fault
and Tide push only after accepted damage. Cancelled or stale casts cannot hit.

Damage attempts recheck their captured combat context and target eligibility;
the damage path requires a visible target when the hit is applied. Moving
attacks use their spell-specific collision and path rules. Ordinary casts
exclude players, NPCs, armor stands and tamed pets. Duel-only casts admit only
registered opponents and their live registered summon actors in the captured match and round; delayed phases retain
that authorization. EliteMobs retains its instance/minion eligibility rules.
Area attacks obey the configured target limit. Each ordinary sequence attempts
a target at most once at contact; Requiem has explicit repeated pulses and
divides its original cast damage across three primary or five secondary strikes.
NOVA primary grows seven terrain-sampled roots across a forward fan up to 18 blocks
between ticks 2 and 14. Its swept front attempts each reachable victim once. The
secondary warns for eight ticks around the aimed 4.5-block area, closes through
tick 14 and then applies contact. Only accepted damage followed by successful root
control creates a wrap. Every victim keeps its own 70-tick (3.5-second) hold from
contact; changing staves and eviction of an old attack controller preserve that
hold. The ground field withers by tick 26, while individual wraps remain attached
to their victims. World changes, death, expired combat authorization and cover
still end invalid control.

GALE primary uses one continuously moving center for its funnel, particles and
swept damage. The bounded irregular path is clipped at cover and requires physical
ground. GALE secondary launches from the eye along full-pitch aim, including upward
toward roof targets; its body and particle accents keep that orientation through
travel and the airborne terminal burst. Its swept path checks current cover and
eligible targets, with one damage attempt per target across contact and burst.

Necromancer summons use temporary native skeleton actors with native movement
and their normal targeting/action goals removed. They follow visible enemies
within safe reachable ground instead of pathfinding around obstacles. Pickup,
persistence and drops are disabled; each squad expires after 45 seconds. New squads preserve earlier summons;
up to ten actors may coexist per caster, within a shared global budget of 32 actors. A full
roster rejects the cast without consuming cooldown. Summoning while flying
searches up to64loaded blocks downward for a safe support/body footprint;
void or unloaded columns reject safely. The Necromancer HUD shows the live army
count out of10 in both hologram and bossbar modes. Archers fire visible, bounded native arrow actors with scripted swept collision;
these carry no independent native damage or pickup. They hit only on contact,
stop at solid cover, and disappear with the summon session. Each archer has four HP. Opponents can
destroy archers, while bystanders and teammates cannot interfere. Actors recover for three seconds between attacks and have a half-second windup.
Their longer lifetime permits sustained attacks; each squad retains a bounded 48-hit ceiling. Summon attacks pass
through the same staff damage and protection path as other casts.

Visual attacks do not edit blocks, place fire or change weather.
`scenery.worlds` enables short-lived client-only destruction for Ember, Mark, Fault
and Gravity impacts: exact world names allow selected worlds, `*` allows all worlds
on that server, and an empty list disables it. Up to twelve exposed blocks per
impact briefly appear missing with interpolated packet-display debris, then restore
to current world state after two seconds. Tile entities and unloaded chunks are
excluded. The effect has separate scene, viewer and fragment limits and no drops.
 Gravity's
boulder is a packet display. Necromancer intentionally creates bounded native
actors and arrows; these are the only native gameplay entities created by these prototype
casts, and their lifecycle is owned by the staff module.

## Arena roster and damage

ArcDuels selects `chain`, `mark`, `lance`, `ember`, `icicle`, `fault` and
`necromancer`. Other prototypes remain available through `/arc stafftest`.
The kit retains its 0.4 damage multiplier. ARC reads separate reloadable
`duel.<id>.damage` and `duel.<id>.cooldown-ticks` settings; PvE tuning is unchanged.
Both arena values are included in the combat capability fingerprint.

Against one unarmored opponent, primary tap casts deal 2.6–4 HP at 1.3–2.2
second recovery, giving a theoretical 1.82–2.25 HP/s before misses, cover and
invulnerability frames. Full charge on Ember and Icicle adds 1.8 seconds and
scales damage by 1.75, with a maximum direct hit of 7 HP. Necromancer instead
has an eight-second recovery; each archer hit deals 0.8 HP. Ten archers build up over
five casts and have a theoretical combined ceiling near 2.3 HP/s before misses
and invulnerability frames. Successful summon hits can overlap later staff casts; the squad
budget and lifetime still apply. These are initial balancing limits, not
evidence of equal duel win rates.

## Preview and evidence limits

The source-driven redesign is documented in
[`staff-effects-redesign.md`](staff-effects-redesign.md). The
`:arc-dungeons:exportStaffRedesignPreview` Gradle task can export seven multi-stage Arsenal
spells, baseline and enlarged visual states for the held gravity channel, and both
guard shapes for all sixteen available staves. Pass an absolute path with
`-PstaffRedesignPreviewOutput=/absolute/path/redesign-sequences.json`; the
source is
[`StaffRedesignPreviewExport.kt`](../arc-dungeons/src/test/kotlin/ru/arc/staffspells/StaffRedesignPreviewExport.kt).
The offline viewer uses source-authored frames but does not simulate damage,
target selection, collision, sound, client lighting or exact vanilla particle
physics. Changed ground attacks use their full configured range on an unobstructed flat
lane; other moving attacks use a shorter illustrative lane. A preview does not
establish ordinary-client visual or audio acceptance.

This document describes the source contract; it does not certify a build,
artifact delivery, runtime activation or in-client review.

## Collision-safe scenery

Scenery remains packet-only. A viewer whose swept body approaches a masked
block receives its current real block state immediately, with a one-tick
fallback and a1.5-block margin. That viewer's matching debris is hidden; other
viewers keep the effect until their own approach or scene expiry. The server
world, collision, drops and containers are never modified.
