# Prototype staff attacks

`/arc stafftest` issues fourteen mechanics samples: `chain`, `mark`, `frost`,
`lance`, `ember`, `nova`, `icicle`, `fault`, `lunar`, `gale`, `tide`,
`requiem`, `prism` and `necromancer`. The command uses the existing
administrator-only `arc.test` permission. `/arc stafftest <id> [player]` issues
one sample; `/arc stafftest all [player]` first checks that the whole set fits
in the recipient's inventory. These are ARC prototype items, not native FMM or
EliteMobs loot. There are no loot-table, shop or economy changes.

## Controls and charge

Hold right-click and release to cast. Charge rises from 0 to 100% over 36
ticks; the charge bar shows the selected ability, percent and release prompt.
Holding Shift when the charge starts selects the secondary right-click ability.
Changing Shift, swapping or moving the item in inventory, dropping the
staff, changing worlds, dying or quitting cancels the charge.

Staff clicks cannot break blocks, including in creative mode; entity clicks
suppress vanilla melee damage.

Left-click casts the quick attack. Shift + left-click casts its quick secondary.
Quick attacks fire without a charge and use the same per-player staff
cooldown. The boss bar follows the active input, displays remaining cooldown
and ready state, and updates when the player switches staves or attack modes.
Changing items cannot bypass the shared cooldown.

## Catalog

| ID and item | Hold/release ПКМ | Hold/release Shift + ПКМ | ЛКМ | Shift + ЛКМ | Existing ItemsAdder skin |
| --- | --- | --- | --- | --- | --- |
| `chain` · Грозовая ветвь | Lightning jumps between up to four enemies | Three bolts seek separate targets | `Искровой веер` — three branching discharges ahead | `Цепной щелчок` — aimed discharge within eight blocks | `3dfantasyweaponscit:divine_staff` |
| `mark` · Разрыв | Mark follows its target and bursts; without a target, bursts at the aimed point | `Чёрная дыра` pulls eligible enemies, then collapses | `Хватка пустоты` — soul streams converge near the target | `Тянущая печать` — rune pulls affected enemies at the aimed point | `3dfantasyweaponscit:night_staff` |
| `frost` · Ледяной хлопок | Ground wave slows enemies in a 50° cone, up to 28 blocks (global cap: 48) | `Полярный круг` — radial ice front expands eight blocks from the caster | `Морозное дыхание` — close frost cone | `Ледяной заслон` — frontal slowing ice wall | `3dfantasyweaponscit:northgate_guardian_staff` |
| `lance` · Солнечное копьё | Narrow solar beam pierces up to three enemies | `Солнечный трезубец` — three diverging beams | `Солнечный взмах` — short diagonal golden cut advances across the lane | `Солнечный кол` — vertical strike at the aimed point, within eight blocks | `3dfantasyweaponscit:sagrada_winged_staff` |
| `ember` · Пепельная комета | Straight fire comet accelerates and bursts | `Метеорный дождь` — three meteors hit the selected point | `Огненное дыхание` — short forward fire stream | `Огненный знак` — flare at the aimed point, within eight blocks | `3dfantasyweaponscit:bermunde_hermit_staff` |
| `nova` · Изумрудная роща | Branching roots grow toward the target and slow enemies | `Сердце рощи` — large flower opens and releases a pollen wave | `Хлыст лозы` — broad vine arc | `Корневая клетка` — roots close around the aimed point, within eight blocks | `3dfantasyweaponscit:holy_celtic_staff` |
| `icicle` · Ледяной венец | Seven homing shoulder icicles; full charge releases eleven | `Ледяной дождь` — five shards fall around the aimed point; full charge releases nine | `Ледяная картечь` — quick spread of ice shards | `Кристальная клетка` — crystals rise around and slow the target | `3dfantasyweaponscit:olwen_staff` |
| `fault` · Обсидиановый раскол | Five ground fissures advance in sequence, up to 18 blocks | `Каменный выброс` — warning, then an aimed eruption lifts enemies | `Каменная дробь` — ground wave of stone fragments | `Столп разлома` — stone plates rise at the aimed point | `3dfantasyweaponscit:staff_of_unholy_wisdom` |
| `lunar` · Лунный серп | Huge crescent falls and releases a silver shockwave | `Лунные ножницы` — two moon blades converge on the target | `Лунный взмах` — upright crescent travels forward | `Затмение` — moon disk closes at the aimed point, within eight blocks | `3dfantasyweaponscit:black_snake_staff` |
| `gale` · Посох бури | Dense cloud funnel rolls toward the target and lifts enemies | `Шквал` — broad wind front rolls along the ground | `Воздушная ладонь` — broad pressure wave ahead | `Подъёмный вихрь` — vortex pulls and lifts enemies at the aimed point, within eight blocks | `3dfantasyweaponscit:goblin_witch_staff` |
| `tide` · Сердце прилива | Four sequential geysers; full charge creates six | `Приливный вал` — high wave rolls forward and knocks enemies back | `Водяной хлыст` — broad whip with a foamy trail | `Водяной фонтан` — water burst lifts enemies at the aimed point, within eight blocks | `3dfantasyweaponscit:northgate_guardian_staff` |
| `requiem` · Реквием | Soul streams converge around the target and burst in ghost flame | `Плач душ` — souls spread out from a sigil and slow enemies | `Поток душ` — soul stream advances forward | `Печать скорби` — ghost sigil bursts at the aimed point and slows enemies | `3dfantasyweaponscit:lich_staff` |
| `prism` · Кристальная призма | Crystal grows at the aim point and fires beams at up to three enemies | `Сведение лучей` — three rays converge | `Осколочный веер` — fan of crystal fragments | `Преломление` — seven crystal rays close around the aimed point, within 12 blocks | `3dfantasyweaponscit:northgate_golden_crook` |
| `necromancer` · Зов могилы | Summons two skeletons for ten seconds; full charge summons four | `Костяной жнец` — one large reaper with a wide attack | `Костяной укол` — bone fragments thrust forward | `Могильная печать` — aimed spectral strike within eight blocks | `3dfantasyweaponscit:lich_staff` |

All skins use entries already present in the ItemsAdder staff catalog; no new
textures are added. Tide reuses the Northgate Guardian staff model, and the
necromancer uses the Lich staff. If ItemsAdder cannot resolve a configured
skin, item issuance fails instead of silently issuing an unskinned sample.

The per-spell `power`, `vanilla-damage` and `cooldown-ticks` defaults are
experimental prototype tuning, not a finished equipment balance. Full charge
raises normal attack power and damage; quick attacks are uncharged and lower
damage. Secondary and quick inputs use their configured cooldown rules. The
HUD reads the same cooldown gate that prevents the cast.

## Targeting and lifecycle

Target selection and damage use the existing visibility and eligibility checks.
Prototype targeting excludes players, NPCs, armor stands and tamed pets. The
existing duel-only service admits only the registered opponent in the captured
match and round; new inputs and delayed attacks preserve that authorization.
EliteMobs retains its instance/minion eligibility rules. The configured area
target limit remains eight. Each sequence hits a target at most once, at its
contact phase, and rechecks combat context, eligibility and line of sight.
Active attacks and summon sessions cancel on caster death, quit, world change,
expiry or module reload/shutdown. Delayed hits retain the cast's captured
combat data; switching the held item does not change it. Visual attacks create
no projectile entities, temporary blocks, fire or weather.

Summons have AI, pickup, persistence and drops disabled. They last ten seconds,
use bounded movement on supported safe ground and do not pathfind around
obstacles. There is one squad per caster, at most four actors per caster and
sixteen globally; each squad can land at most six successful hits. Summoned
damage uses the same staff bridge and protection path as the other attacks.

The samples are separate `BLAZE_ROD` items marked with ARC's
`staff_spell_prototype` PDC and given an appearance through the existing
ItemsAdder elite-skin helper. They do not replace or intercept native FMM
staves. Elite damage uses ARC's existing attributed damage bridge and normal
protection event path; ordinary mobs continue through Bukkit damage.

## Particles, previews and verification

Quick attacks use their own native-particle gesture sequences; they do not
replay shortened right-click display scenes. New authored particle frames run
every two ticks with one audience lookup per frame. Per viewer, those frames
share a ceiling of 480 packets and 4096 particles per two-tick window; excess
is discarded immediately, not queued. Minecraft owns each particle's lifetime
and motion.

The source-driven redesign is documented in
[`staff-effects-redesign.md`](staff-effects-redesign.md). The
`exportStaffRedesignPreview` Gradle task exports the six redesigned staves'
primary and secondary right-click scenes at zero and full charge, plus both
uncharged quick inputs for all fourteen staves. It samples source-authored
particle and display frames every two ticks. Run it with
`-PstaffRedesignPreviewOutput=/absolute/path/redesign-sequences.json`; the
export source is
[`StaffRedesignPreviewExport.kt`](../src/test/kotlin/ru/arc/staffspells/StaffRedesignPreviewExport.kt).
The offline viewer previews particle sprites and display geometry, but does
not simulate damage, target selection, collision, sounds, client lighting or
exact vanilla particle physics. Moving scenes use an illustrative fixed lane;
the preview does not establish ordinary-client acceptance.

Focused tests cover deterministic mechanics and lifecycle. An in-client review
is still needed to judge appearance against moving mobs and obstructions.
