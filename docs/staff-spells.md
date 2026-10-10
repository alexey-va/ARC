# Prototype staff attacks

`/arc stafftest` issues thirteen mechanics samples: `chain`, `mark`, `frost`,
`lance`, `ember`, `nova`, `icicle`, `fault`, `lunar`, `gale`, `tide`,
`requiem` and `prism`. The command is administrator-only through the existing
`arc.test` permission. `/arc stafftest <id> [player]` issues one sample;
`/arc stafftest all [player]` first checks that the whole set fits in the
recipient's inventory. These are ARC prototype items, not native FMM or
EliteMobs loot. There are no loot-table, shop or economy changes.

## Controls and charge

Hold right-click and release to cast. Charge rises from 0 to 100% over 36
ticks; the charge bar shows the selected ability, percent and release prompt.
Holding Shift when the charge starts selects the secondary right-click ability.
Changing Shift, swapping or moving the item in inventory, dropping the
staff, changing worlds, dying or quitting cancels the charge.

Staff clicks cannot break blocks, including in creative mode; entity clicks suppress vanilla melee damage.

Left-click casts the quick attack. Shift + left-click casts its quick secondary.
Quick attacks fire without a charge and use the same per-player staff
cooldown. The boss bar follows the active input, displays remaining cooldown
and ready state, and updates when the player switches staves or attack modes.
Changing items cannot bypass the shared cooldown.

## Catalog

| ID and item | Hold/release ПКМ | Hold/release Shift + ПКМ | ЛКМ | Shift + ЛКМ | Existing ItemsAdder skin |
| --- | --- | --- | --- | --- | --- |
| `chain` · Грозовая ветвь | Seeking lightning jumps between up to four distinct targets | Three seeking bolts fan out to separate targets | Close lightning fan | Aimed lightning snap | `3dfantasyweaponscit:divine_staff` |
| `mark` · Разрыв | A mark follows its acquired target, then bursts; without a mob it uses the aimed point | A fixed black hole pulses, pulls eligible mobs and collapses | Short aimed strike sigil | Aimed pulling glyph | `3dfantasyweaponscit:night_staff` |
| `frost` · Ледяной хлопок | Ground wave advances through a 50° cone for up to 28 blocks (global cap: 48) | Radial ice front expands from the caster to eight blocks | Close frost fan | Frontal slowing ice ward | `3dfantasyweaponscit:northgate_guardian_staff` |
| `lance` · Солнечное копьё | A narrow solar front travels up to 48 blocks and damages targets as it reaches them | Three solar fronts travel at −16°, 0° and +16° | Close crossed solar cleave | Downward solar pin at the aimed point | `3dfantasyweaponscit:sagrada_winged_staff` |
| `ember` · Пепельная комета | A straight fire core accelerates, then bursts on collision or at range | Three staggered meteors descend at the selected point | Close fire crescent | Aimed contact flare | `3dfantasyweaponscit:bermunde_hermit_staff` |
| `nova` · Изумрудная волна | Витой побег — a spiraling vine travels toward the aim point | Изумрудное цветение — a rooted bloom opens at the selected point | Хлыст лозы — a close curved vine whip | Корневая клетка — roots pull at the aimed point | `3dfantasyweaponscit:holy_celtic_staff` |
| `icicle` · Ледяной венец | Seven seeking shoulder icicles; full charge releases eleven | Five falling lances around the aim point; full charge releases nine | Three curved ice claws | Rising crystal cage that slows targets | `3dfantasyweaponscit:olwen_staff` |
| `fault` · Обсидиановый раскол | Five ground ruptures advance along the facing direction, up to 18 blocks | After a warning, an aimed ground eruption lifts nearby mobs | Forward ground fissure | Aimed stone pillar | `3dfantasyweaponscit:staff_of_unholy_wisdom` |
| `lunar` · Лунный серп | Серп возвращения — crescent travels out, then returns | Лунная орбита — crescent orbits briefly, then sweeps through | Лунный взмах — broad close crescent sweep | Лунные ножницы — crossed cuts at an aimed point within eight blocks | `3dfantasyweaponscit:black_snake_staff` |
| `gale` · Посох бури | Блуждающий смерч — traveling tornado pulls nearby mobs | Воронка — pulsing stationary pull field | Воздушная ладонь — close pressure fan | Подъёмный вихрь — aimed pull and lift within eight blocks | `3dfantasyweaponscit:goblin_witch_staff` |
| `tide` · Сердце прилива | Водяная комета — arcing projectile bursts on impact | Гейзер — delayed expanding ground eruption | Прибой — short breaking wave | Водяной фонтан — aimed water lift within eight blocks | `3dfantasyweaponscit:northgate_guardian_staff` |
| `requiem` · Реквием | Призрачная стая — five seeking wisps select distinct targets | Ритуал душ — charged sigil releases a radial pulse | Призрачная хватка — close spectral claws | Руки могилы — aimed grasping pull ring | `3dfantasyweaponscit:lich_staff` |
| `prism` · Кристальная призма | Раздвоенный луч — three traveling rays pierce their lanes | Рикошетный луч — ray steps through up to two new targets | Кристальный срез — close crystal blades | Кристальное цветение — aimed crystal bloom | `3dfantasyweaponscit:northgate_golden_crook` |

These skin IDs already exist in the verified ItemsAdder staff catalog; no new
textures are added. Tide deliberately reuses the Northgate Guardian staff
model. If ItemsAdder cannot resolve a configured skin, item issuance fails
instead of silently issuing an unskinned sample.

The per-spell `power`, `vanilla-damage` and `cooldown-ticks` defaults are
experimental prototype tuning, not a finished equipment balance. Full charge
raises normal attack power and damage; quick attacks are uncharged and lower
damage. Secondary and quick inputs use their configured cooldown rules. The
HUD reads the same cooldown gate that prevents the cast.

## Targeting and lifecycle

Target selection and damage use the existing visibility and eligibility checks.
Prototype targeting excludes players, NPCs, armor stands and tamed pets.
The existing duel-only service admits only the registered opponent in the captured match and round;
new inputs and delayed attacks preserve that authorization. EliteMobs retains its
instance/minion eligibility rules. The configured area target limit remains
eight. Marks, waves, projectiles and fields cancel on caster death, quit, world
change, expiry or module reload/shutdown. Delayed hits retain the cast's
captured combat data; switching the held item does not change it. These attacks
create no native projectile entities, temporary blocks, fire or weather.

The samples are separate `BLAZE_ROD` items marked with ARC's
`staff_spell_prototype` PDC and given an appearance through the existing
ItemsAdder elite-skin helper. They do not replace or intercept native FMM
staves. Elite damage uses ARC's existing attributed damage bridge and normal
protection event path; ordinary mobs continue through Bukkit damage.

## Visual previews and verification

`StaffSpellDisplayGeometry` supplies production block-display geometry to both
the packet renderer and the offline preview exporter. The preview includes all
four inputs for NOVA and the five new Arsenal staves, plus charge states.
The companion `motion-clips.json` exports every staff/input at a two-tick cadence
for continuous silhouette comparison at zero and full charge. Its fixed anchor
is deliberate; it does not simulate live projectile trajectories. Exported snapshots are source-driven offline previews;
they do not establish Minecraft interpolation, particles, sounds, combat feel
or ordinary-client acceptance. Focused tests cover deterministic mechanics and
lifecycle, but an in-client review is still needed to judge the final appearance
against moving mobs and obstructions.

Visual scenes use bounded client-side block displays: 48 parts per scene, at
most 12 scenes per caster, 16 per viewer and 32 globally, with hard limits of
192 display parts per caster/viewer and 576 globally. The oldest visual is
evicted when a limit is reached; projectile collision and damage continue
independently. Scene geometry updates every two ticks, and cleanup follows the
same caster and module lifecycle as the attack.

The build's `exportStaffSpellPreview` task accepts
`-PstaffSpellPreviewOutput=/absolute/path/scene.json`; the result can be opened
with the location-atelier textured preview builder. It reuses runtime geometry
but does not model native Minecraft lighting, interpolation, particles, sounds
or measured client acceptance.

## Charge and motion feedback

A released cast captures a normalized visual charge alongside its combat snapshot.
Full charge thickens the released forms; each staff has its own accumulation
formation. Caster-only hand particles and pitched cues mark start, 25%, 50%,
75% and full charge. Holding full charge does not repeat its ready sound.
Denied cooldown attempts share one quiet sound/particle gate every eight ticks
across all four inputs. The bossbar remains the authoritative cooldown display.

Moving Arsenal and Ember scenes use their controller clock at two-tick display
updates. The controller hands impact cleanup back to the display owner. Ember
keeps semantic core identities across impact while retiring its wake and growing
new debris. Native particle wakes trail the sampled movement rather than its
future target. Requiem and Prism draw one body per runtime projectile.

Gale borrows the ArcFarms tornado's funnel, counter-rotating crown and occasional
wind layers, with at most 32 native particles per three-tick frame. Its display
orientation follows yaw only, keeping the funnel upright when aiming up or down.
Only eight display debris chunks orbit inside the particle funnel. Gale’s air palm
and Tide’s breaking surf use native particles exclusively, with at most 21 and 24
particles per two-tick frame respectively. No farm behavior or terrain is changed.
