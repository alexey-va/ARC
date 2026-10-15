# Staff spells — ArcDungeons 0.1.2

`/arc stafftest` issues sixteen administrator-only mechanics samples.
`inferno` is temporarily retired; `gravity` and `ossuary` remain available. Use
`/arc stafftest <id> [player]` for one sample or `/arc stafftest all [player]`
to issue the set after checking inventory space. The items carry ARC prototype
metadata; they are not FMM or EliteMobs loot, and this catalog adds no shop,
reward or economy changes.

Four primaries — Frost, Fault, Lunar and Tide — use substantial frontal area
attacks with a seven-block reach. The other twelve keep their ranged, targeted,
summoned or charged primary mechanics; Solar is a piercing ranged spear again.
Shift attacks keep their existing behavior. Native particles accent stable
display bodies; the last sent pose collapses before cleanup.

## Controls

Left-click casts ordinary abilities immediately. Primary `Ember` and `Icicle`
use click-toggle charging: click once to begin, then click again to launch.
Full charge takes 36 ticks (1.8 seconds). Shift + left-click selects the second
ability and casts immediately. Changing Shift, the held slot or item, world,
or player lifecycle cancels an active charge; right-click cancels it and raises
defense. Attacks share one per-player attack cooldown, adjusted for the spell
and its shifted variant.

A private three-row hologram below the reticle shows the selected ability and
its actual control mode: instant cast, click to charge and click again to
release, or Gravity grab/throw. Charging shows accumulated strength; recovery
shows the remaining attack cooldown. Physical button holding is not required.
The HUD replaces the attack bossbar; the independent defense bar is retained.

`Gravity` also uses click-toggle: the first left-click grabs, the next throws.
The aim can move between clicks, with or without Shift. Inferno is temporarily
retired from issuance and casting. Existing EliteMobs Inferno assignments migrate
once to Ember; old prototype IDs remain recognizable but cannot cast.

Gravity selects a visible solid block within its reach, holds a visual boulder
in front of the caster as the aim moves, and throws it on the second left-click. The
boulder is a visual effect: Gravity does not move, break, place or replace
world blocks. If the selected surface or safe path is unavailable, the grab is
rejected.

Right-click raises the staff's frontal guard; Shift + right-click raises its
all-around absorption shield. Defenses have their own recovery track, independent of the
left-click attack cooldown. A frontal guard covers two hits for 1.2 seconds,
or three hits for 1.6 seconds for Fault, Gravity, Ossuary, Nova and Necromancer.
The all-around shield lasts twenty seconds and absorbs eight HP after armor and
resistance, before native absorption hearts. Overflow continues through normal
damage processing. Its bossbar shows remaining capacity. Recovery is four
seconds for frontal and twenty-five seconds from casting the shield; both share that track.
The frontal guard is a 12-part kite-shaped packet BlockDisplay shield with a transparent face;
Shift + right-click assembles four angled plates (40 parts) around the player. Material families
follow the staff element. The stable displays attach to the player, interpolate their opening,
recoil and collapse, and share the existing scene/part budgets. Native particles appear only
on a blocked hit or shield break. The center stays transparent and the raised outline leaves
the reticle readable. `StaffDefenseGeometry.kt` owns the shape used by both runtime and
`exportStaffRedesignPreview`; offline previews do not establish native-client acceptance.

Changing staff cancels the active defense without resetting recovery. The right-click controls only activate defense. Staff interaction cannot break blocks, including in
creative mode, and native melee damage is suppressed while a prototype staff
is held.

## Catalog

| ID · staff | ЛКМ | Shift + ЛКМ | ПКМ · frontal guard | Shift + ПКМ · absorption shield | Existing ItemsAdder skin |
| --- | --- | --- | --- | --- | --- |
| `chain` · Грозовая ветвь | Lightning jumps between up to four enemies | Грозовой круг expands a jagged lightning front eight blocks around the caster, blocked by cover | Грозовой заслон | Круговой покров | `3dfantasyweaponscit:divine_staff` |
| `mark` · Разрыв | A targeted void rupture follows the enemy, then explodes | Чёрная дыра pulls eligible enemies, then collapses | Покров пустоты | Круговой покров | `3dfantasyweaponscit:night_staff` |
| `frost` · Ледяной хлопок | A broad field of clustered ice teeth rises in front, reaches seven blocks and briefly slows enemies | Полярный круг expands an ice front eight blocks from the caster | Ледяной щит | Круговой покров | `3dfantasyweaponscit:northgate_guardian_staff` |
| `lance` · Солнечное копьё | A ranged solar spear pierces up to three enemies, applying damage when its light front arrives | Three diverging solar beams | Солнечный щит | Круговой покров | `3dfantasyweaponscit:sagrada_winged_staff` |
| `ember` · Пепельная комета | Fireball grows below the reticle with four-tick interpolation, then launches immediately with forward speed and accelerates | Three ground circles lock on cast; the first meteor launches after a 15-tick warning | Огненный заслон | Круговой покров | `3dfantasyweaponscit:bermunde_hermit_staff` |
| `nova` · Терновый хват | Roots travel along the ground toward the target, close around it and slow enemies | Терновая клетка closes around the aimed point | Терновый заслон | Круговой покров | `3dfantasyweaponscit:holy_celtic_staff` |
| `icicle` · Ледяной венец | Seven shoulder icicles sample the current reticle as each launches; full charge releases eleven | Five shards fall around the aimed point immediately | Кристальный заслон | Круговой покров | `3dfantasyweaponscit:olwen_staff` |
| `fault` · Обсидиановый раскол | A broad basalt and obsidian rupture rises in front up to seven blocks and pushes enemies back | Каменный выброс warns, then erupts at the aimed point and lifts enemies | Каменный бастион | Круговой покров | `3dfantasyweaponscit:staff_of_unholy_wisdom` |
| `lunar` · Лунный серп | One large moon crescent cuts across a seven-block frontal area | Two moon blades converge on the target | Лунный покров | Круговой покров | `3dfantasyweaponscit:black_snake_staff` |
| `gale` · Посох бури | An open tornado forms at the aimed point and lifts nearby enemies | Шквал is a twisting air ram with a fixed 36-block horizontal reach, clipped at cover | Ветряной щит | Круговой покров | `3dfantasyweaponscit:goblin_witch_staff` |
| `tide` · Сердце прилива | A thick curling breaker covers a seven-block frontal area and pushes enemies back | Приливный вал is a broad curled wave with a foamy crest that travels forward | Водяной заслон | Круговой покров | `3dfantasyweaponscit:northgate_guardian_staff` |
| `requiem` · Реквием | A copper-and-gold bell forms above the aimed point, tolls at 0.8 seconds and releases a five-block soul wave | Погребальный перезвон suspends the bell above the caster and releases three seven-block waves, with five-second minimum recovery | Покров душ | Круговой покров | `3dfantasyweaponscit:lich_staff` |
| `prism` · Кристальная призма | A crystal grows at the aimed point and refracts beams into up to three enemies | Three rays converge on the selected point | Гранёный щит | Круговой покров | `3dfantasyweaponscit:northgate_golden_crook` |
| `necromancer` · Зов могилы | Adds two skeleton archers for 45 seconds; up to ten minions can coexist | Костяной жнец summons one larger wither-skeleton commander | Костяной заслон | Круговой покров | `3dfantasyweaponscit:lich_staff` |
| `gravity` · Гравитационный захват | Grab a surface within 32 blocks, hold the same boulder, and release into a fast accelerating throw up to 32 blocks | Бросок глыбы uses the same grab-and-throw channel | Каменный заслон | Круговой покров | `3dfantasyweaponscit:staff_of_unholy_wisdom` |
| `ossuary` · Костяной прокол | Dense bone-spike clusters erupt in sequence along a fixed 24-block forward path, blocked by walls | Костяной прорыв warns at the aimed point, then seven bone spikes shoot upward and impale enemies | Щит рёбер | Круговой покров | `3dfantasyweaponscit:lich_staff` |

Each sample uses its configured ItemsAdder skin; issuance fails with a player
message and a server warning if that configured skin is unavailable. Duel
staff items are built separately on a stable native base. Their optional
client-pack appearance requires both a fully namespaced `duel-appearance.item-model`
and a positive `custom-model-data`; the bundled defaults leave both unset.

## Targeting and lifecycle

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
Area attacks obey the configured target limit, and each sequence attempts a
target at most once at its contact phase.

Necromancer summons use temporary native skeleton actors with native movement
and their normal targeting/action goals removed. They follow visible enemies
within safe reachable ground instead of pathfinding around obstacles. Pickup,
persistence and drops are disabled; each squad expires after 45 seconds. New squads preserve earlier summons;
up to ten actors may coexist per caster, within a shared global budget of 32 actors. A full
roster rejects the cast without consuming cooldown. Archers fire visible, bounded native arrow actors with scripted swept collision;
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
