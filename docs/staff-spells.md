# Prototype staff spells — ARC 1.4.378

`/arc stafftest` issues seventeen administrator-only mechanics samples: the
fourteen established staves plus `inferno`, `gravity` and `ossuary`. Use
`/arc stafftest <id> [player]` for one sample or `/arc stafftest all [player]`
to issue the set after checking inventory space. The items carry ARC prototype
metadata; they are not FMM or EliteMobs loot, and this catalog adds no shop,
reward or economy changes.

## Controls

For ordinary casts, hold right-click and release. Charge reaches full after 36
ticks (1.8 seconds). Holding Shift when the cast begins chooses the shifted
right-click ability; changing Shift, the held slot or item, world, or player
lifecycle cancels it. Attacks share one per-player attack cooldown, with the
duration configured per spell and adjusted for the shifted cast.

`Inferno` and `Gravity` are held channels rather than charged casts. Hold
right-click, with or without Shift, to operate them. Inferno follows the aim
with a continuous stream for at most three seconds and stops on release.
Gravity selects a visible solid block within its reach, holds a visual boulder
in front of the caster as the aim moves, and throws it when released. The
boulder is a visual effect: Gravity does not move, break, place or replace
world blocks. If the selected surface or safe path is unavailable, the grab is
rejected.

Left-click raises the staff's frontal guard; Shift + left-click raises its
all-around absorption shield. Defenses have their own recovery track, independent of the
right-click attack cooldown. A frontal guard covers two hits for 1.2 seconds,
or three hits for 1.6 seconds for Fault, Gravity, Ossuary, Nova and Necromancer.
The all-around shield lasts three seconds and absorbs eight HP after armor and
resistance, before native absorption hearts. Overflow continues through normal
damage processing. Its bossbar shows remaining capacity. Recovery is four
seconds for frontal and eight seconds for the shield; both share that track.
Changing staff cancels the active defense without resetting recovery. The left-click controls do not perform
the former quick attacks. Staff interaction cannot break blocks, including in
creative mode, and native melee damage is suppressed while a prototype staff
is held.

## Catalog

| ID · staff | ПКМ | Shift + ПКМ | ЛКМ · frontal guard | Shift + ЛКМ · absorption shield | Existing ItemsAdder skin |
| --- | --- | --- | --- | --- | --- |
| `chain` · Грозовая ветвь | Lightning jumps between up to four enemies | Three bolts seek separate targets | Грозовой заслон | Круговой покров | `3dfantasyweaponscit:divine_staff` |
| `mark` · Разрыв | Mark follows its target and bursts; without a target, bursts at the aimed point | Чёрная дыра pulls eligible enemies, then collapses | Покров пустоты | Круговой покров | `3dfantasyweaponscit:night_staff` |
| `frost` · Ледяной хлопок | Ground wave slows enemies in a 50° cone, up to 28 blocks (global cap: 48) | Полярный круг expands an ice front eight blocks from the caster | Ледяной щит | Круговой покров | `3dfantasyweaponscit:northgate_guardian_staff` |
| `lance` · Солнечное копьё | Narrow solar beam pierces up to three enemies | Three diverging solar beams | Солнечный щит | Круговой покров | `3dfantasyweaponscit:sagrada_winged_staff` |
| `ember` · Пепельная комета | Fireball grows below the reticle with four-tick interpolation, then launches immediately with forward speed and accelerates | Three ground circles lock on release; the first meteor launches after a 15-tick warning | Огненный заслон | Круговой покров | `3dfantasyweaponscit:bermunde_hermit_staff` |
| `nova` · Терновый хват | Visible ground-hugging roots travel toward the target, close and slow enemies | Терновая клетка closes around the aimed point | Терновый заслон | Круговой покров | `3dfantasyweaponscit:holy_celtic_staff` |
| `icicle` · Ледяной венец | Seven shoulder icicles sample the current reticle as each launches; full charge releases eleven | Five shards fall around the aimed point; full charge releases nine | Кристальный заслон | Круговой покров | `3dfantasyweaponscit:olwen_staff` |
| `fault` · Обсидиановый раскол | Five ground fissures advance in sequence, up to 18 blocks | Каменный выброс warns, then erupts at the aimed point and lifts enemies | Каменный бастион | Круговой покров | `3dfantasyweaponscit:staff_of_unholy_wisdom` |
| `lunar` · Лунный серп | Falling silver crescent releases a ground shockwave | Two moon blades converge on the target | Лунный покров | Круговой покров | `3dfantasyweaponscit:black_snake_staff` |
| `gale` · Посох бури | Open-center wind column forms at the aimed point and lifts nearby enemies | Шквал is a compact air ram that travels forward | Ветряной щит | Круговой покров | `3dfantasyweaponscit:goblin_witch_staff` |
| `tide` · Сердце прилива | A water core travels to the target and bursts into a broad spray | Приливный вал is a low wave with a foamy crest | Водяной заслон | Круговой покров | `3dfantasyweaponscit:northgate_guardian_staff` |
| `requiem` · Реквием | Staggered soul missiles launch from offset lanes in front of the caster | Плач душ expands outward from a sigil | Покров душ | Круговой покров | `3dfantasyweaponscit:lich_staff` |
| `prism` · Кристальная призма | Crystal grows at the aim point and fires at up to three visible enemies | Three rays converge on the selected point | Гранёный щит | Круговой покров | `3dfantasyweaponscit:northgate_golden_crook` |
| `necromancer` · Зов могилы | Summons two skeleton archers for ten seconds; full charge summons up to four | Костяной жнец summons one larger wither-skeleton commander | Костяной заслон | Круговой покров | `3dfantasyweaponscit:lich_staff` |
| `inferno` · Дыхание печи | Hold for a continuous, aim-following fire stream (up to three seconds and nine blocks) | Поток пламени uses the same held stream | Огненный заслон | Круговой покров | `3dfantasyweaponscit:bermunde_hermit_staff` |
| `gravity` · Гравитационный захват | Select a visible surface and hold a stone boulder; track with aim and release to throw | Бросок глыбы uses the same grab-and-throw channel | Каменный заслон | Круговой покров | `3dfantasyweaponscit:staff_of_unholy_wisdom` |
| `ossuary` · Костяной прокол | Long bone spikes erupt in a fast ground wave, with sparse native particles | Костяной прорыв warns at the aimed point, then seven bone spikes shoot upward and impale enemies | Щит рёбер | Круговой покров | `3dfantasyweaponscit:lich_staff` |

Each sample uses its configured ItemsAdder skin; issuance fails with a player
message and a server warning if that configured skin is unavailable. Duel
staff items are built separately on a stable native base. Their optional
client-pack appearance requires both a fully namespaced `duel-appearance.item-model`
and a positive `custom-model-data`; the bundled defaults leave both unset.

## Targeting and lifecycle

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
persistence and drops are disabled; each squad expires after ten seconds, one
squad is allowed per caster, and caps are four actors per caster and sixteen
globally. Archers fire visible, bounded native arrow actors with scripted swept collision;
these carry no independent native damage or pickup. They hit only on contact,
stop at solid cover, and disappear with the summon session. Each archer has four HP. Opponents can
destroy archers, while bystanders and teammates cannot interfere. Each squad
can land at most six successful hits. Summon attacks pass
through the same staff damage and protection path as other casts.

Visual attacks do not edit blocks, place fire or change weather. Gravity's
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
invulnerability frames. Full charge adds 1.8 seconds and scales damage by 1.75,
with a maximum direct hit of 7 HP. Necromancer instead has an eight-second
recovery and a shared six-hit squad budget: each arrow deals 0.8 HP on a tap
cast, or 1.4 HP on a full cast. Successful summon hits can overlap later staff
casts; the squad budget and lifetime still apply. These are initial balancing
limits, not evidence of equal duel win rates.

## Preview and evidence limits

The source-driven redesign is documented in
[`staff-effects-redesign.md`](staff-effects-redesign.md). The
`exportStaffRedesignPreview` Gradle task can export seven multi-stage Arsenal
spells, both held channels at zero and full charge, and both guard shapes for
all seventeen staves. Pass an absolute path with
`-PstaffRedesignPreviewOutput=/absolute/path/redesign-sequences.json`; the
source is
[`StaffRedesignPreviewExport.kt`](../src/test/kotlin/ru/arc/staffspells/StaffRedesignPreviewExport.kt).
The offline viewer uses source-authored frames but does not simulate damage,
target selection, collision, sound, client lighting or exact vanilla particle
physics. Moving scenes use an illustrative fixed lane. A preview does not
establish ordinary-client visual or audio acceptance.

This document describes the source contract; it does not certify a build,
artifact delivery, runtime activation or in-client review.
