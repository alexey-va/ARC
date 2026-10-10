# Prototype staff attacks

`/arc stafftest` gives the caller six mechanics samples. The existing `arc.test`
administrator permission controls item issuance. `/arc stafftest chain|mark|frost|lance|ember|nova`
gives one sample; an optional final player name allows console/operator delivery.
The entire selected set must fit in free inventory slots before anything is given.
There are no loot-table or shop changes. Anyone holding an issued sample can use it.

- **Грозовая ветвь / chain:** the Divine Staff (`3dfantasyweaponscit:divine_staff`)
  launches a centered seeking lightning projectile that turns toward the moving target,
  then jumps through up to four distinct targets. Damage occurs on contact and
  each jump keeps its reduced damage. Every movement segment checks walls and mobs.
- **Разрыв / mark:** the Night Staff (`3dfantasyweaponscit:night_staff`) places a
  short-lived mark that follows the acquired mob, then detonates at its current
  position and damages nearby visible mobs. With no target it forms at the aimed
  wall/range endpoint instead. The impact animation reuses the mark's display pieces
  for a continuous transition.
- **Ледяной хлопок / frost:** the Northgate Guardian Staff
  (`3dfantasyweaponscit:northgate_guardian_staff`) sends a horizontal ice wave along
  the ground in the direction you face. Its front travels through the cone over
  twelve ticks after a short lead-in; mobs take damage and slowness only when the
  front reaches them and they remain eligible and visible. It does not steer.
- **Солнечное копьё / lance:** the Winged Staff
  (`3dfantasyweaponscit:sagrada_winged_staff`) damages up to three mobs instantly
  along a narrow line through the reticle. After a four-tick charge, a bright
  faceted channel opens over four to six ticks, leaving a contact corona and
  twelve-tick aftermath; damage remains immediate. There is no soft lock.
- **Пепельная комета / ember:** the Hermit Staff
  (`3dfantasyweaponscit:bermunde_hermit_staff`) launches a straight, steady fire
  core from just ahead of the caster. Lead moving targets manually; it bursts
  outward on the first mob/wall or at maximum range. Collision is checked along
  each movement segment, not just at endpoints.
- **Изумрудная волна / nova:** the Celtic Staff
  (`3dfantasyweaponscit:holy_celtic_staff`) sends one expanding ground ring from the
  caster's feet to an eight-block radius. The front starts after two ticks and
  reaches full range sixteen ticks later; eligible, visible mobs are hit once as
  the front passes, up to the unchanged area-target limit. Its annular display
  remains centered on the caster; it does not add repeated damage or pull mobs.

Use the main-hand right click, including a direct click on an entity. Look near a
mob for chain/mark; small particles over its head preview their selected target.
The other primary attacks retain manual aiming; the meteor shower can select a
visible target as its landing point. Every spell can fire into
empty space and consumes its cooldown on a miss. All samples share
one caster cooldown, so swapping sample items cannot bypass it. Walls block initial
acquisition, every chain link, and blast damage. Players, NPCs, armor stands and
tamed pets are excluded. EliteMobs also applies its instance/minion eligibility.
Marks, staged waves and flying orbs cancel on caster death, quit, world change, module reload
or shutdown. Acquired marks also cancel on an invalid/distant target. Flight has
a fixed maximum range and at most eight orbs per caster; visual bursts are short
and use client-only block displays, sparse particles and local sounds. Each cast captures combat facts, so
changing the held item cannot change a delayed hit's level or critical roll.

## Shift abilities and cooldown display

Hold Shift and right-click with the same existing prototype item. The input is
captured at cast time, so releasing Shift during flight does not change an attack.
The 48-block targeting/beam/projectile range replaces 24; Frost's primary ground
cone reaches 10 blocks instead of 7. The ordinary Nova radius remains 8.

| Staff | Shift + right-click | Shared cooldown | Damage relative to the primary |
| --- | --- | --- | --- |
| Chain | Three seeking bolts start in different directions and curve into separate targets within a 55-degree cone; no further jumps | 1.5× | 0.75× per bolt; shared unique victims |
| Mark | A fixed black hole pulls attackable mobs, then collapses after 40 ticks | 2× | Four 0.12× pulses plus 0.65× collapse; each pulse uses normal damage/protection checks before velocity |
| Frost | An expanding circular ice front around the caster | 1.5× | One hit and slow per target, same area-target cap |
| Lance | Three piercing rays at −16°, 0°, +16° | 1.5× | 0.65×, deduplicated across rays and same total piercing-target cap |
| Ember | Three staggered meteors descend above the aimed point | 2× | 0.65× once per unique target across the shower, same total area cap |
| Nova | A forward emerald crest travels 12 blocks with a widening fan | 1.5× | One hit per target, same area-target cap |

The action bar shows the selected ability, ten filling segments and remaining
seconds; readiness also shows the input. It reads the same cooldown used by the
cast gate and survives item/mode swaps without granting another attack. It clears
when the staff is put away. Previously issued PDC-marked items immediately gain
the new actions and HUD; their old stored lore is not rewritten automatically.
Newly issued samples describe the secondary ability.

Meteor origins trace upward from the destination and stay below an indoor
ceiling. Each strike reads its own ground height; with no ground in the bounded
trace, it retains the explicitly aimed height for an aerial detonation. All delayed launches, projectiles, marks and waves cancel on quit, death,
world change and reload. No blocks, weather, fire or native projectile entities
are created. A black-hole pull only follows a successful attributed damage event;
players, protected mobs, pets, NPCs and ineligible elite targets are excluded by
the existing bridge. Velocity uses Paper's [meters-per-tick API](https://jd.papermc.io/paper/26.1.2/org/bukkit/entity/Entity.html#setVelocity(org.bukkit.util.Vector)).

Compact flight uses a 1.15-block eye clearance so its centered muzzle remains
visible nearby; ground-level emerald fronts use 0.55 blocks plus full-piece bounds; large mark/comet impacts retain the 3.2-block exclusion. Display
checks include each part's full bounding sphere, and particle checks apply to
every emission point. Display visibility also
checks the swept interpolation segment every tick, with movement padding. This rule
also covers close impacts, crouching and spectators; collision still starts at
the real eye, so the clearance does not let a shot skip a nearby wall or mob.

## Ownership and damage contract

`src/main/kotlin/ru/arc/staffspells/` owns input, targeting, visuals and lifecycle.
`modules/staff-spells.yml` owns tuning and Russian item text. `StaffSpellsModule`
reloads through `/arc reload`; a reload clears pending marks, flying orbs and old scheduled
effects before installing the new settings. No native entities or temporary blocks are created.

These are separate `BLAZE_ROD` items marked only with ARC's
`staff_spell_prototype` PDC. The configured ItemsAdder item is copied through
ARC's appearance-only elite-skin helper; the prototype keeps its Blaze Rod
material and spell input. A configured skin that ItemsAdder cannot resolve makes
issuance fail instead of producing an unskinned sample. Test configurations with
no `skin` value keep the plain Blaze Rod appearance. These prototypes do not
modify or intercept a native FMM staff, register a replacement FMM resolver, or
require an EliteMobs/FMM source change.

`StaffSpellDamage` consumes the existing compile-only EliteMobs dependency.
For elites it calls `AdvancedDamageScaling.magicWeapon`, then
`CombatDamageContext.runPlayerToEliteBypass(PlayerDamageSource, Runnable)` around
the normal attributed Bukkit `damage` call. This preserves the normal protection
event path and EliteMobs attribution without running its damage formula twice.
For a modeled custom boss, that call also uses EliteMobs' existing
`CustomModel.runProjectileDamageBypass` scope. FMM then accepts the already
resolved spell hit instead of rerouting it through a physical melee hitbox.
These callable methods are implementation-package APIs, so compatibility remains
pinned to the project's EliteMobs API, not a promised stable upstream addon API.
Failures on an enabled integration reject the cast/hit and emit a diagnostic.
The called JVM signatures were also checked against the inspected Spawn
EliteMobs 10.9.8 JAR. The bridge attributes these custom attacks to Staves; it
does not classify them as a native `STAFF_FIREBALL` or `WAND_MISSILE` strike.

For this mechanics trial, the captured Staves skill level is also the **virtual
item level**. `power` is a matched-level elite-hit multiplier. `vanilla-damage` is
the separate base damage against ordinary mobs; chain falloff applies once to
each value. Native item enchantments, durability, drops and real staff replacement
are deliberately outside this prototype. Tuning is experimental, not a finished
equipment/economy balance. When EliteMobs is absent, the same visuals and normal
Bukkit damage remain usable without loading the optional integration classes.

## Display effects and limits

The visual language uses a short anticipation, a decisive strike and a slower
particle aftermath. Reference studies were the official GGG
[Witch walkthrough](https://www.youtube.com/watch?v=82CGiyshJ0c) (Bonestorm and
violet/white discharges around 5:53–6:20) and
[Ranger walkthrough](https://www.youtube.com/watch?v=iw870QM1V5k) (Lightning Arrow
and Lightning Rods). These inform timing, bright cores and irregular edges;
no external game assets are copied.

Lightning advances in alternating short leaders and fast discharges, with older
segments thinning into an afterimage. MARK is an upright fracture whose shards
snap outward. The black hole has a dark faceted core, a tilted luminous accretion
disk and inward particles. The solar piercer charges briefly, crosses its ray
quickly, then leaves a bright contact corona. Comets have a hot, voluminous burst
and slower embers. Emerald waves are broad translucent crescents with a bright
lip, replacing the stacked ground pillars. Frost retains its sequential ground
crystals. Damage, cooldowns, target limits and the two input modes are unchanged;
lightning time-to-contact follows its new stepped travel speed.

`StaffSpellDisplayGeometry` is shared by the renderer and offline textured preview.
`StaffSpellDisplayEffects` reuses core `PaperPacketDisplays` under the `staff-spells`
visual budget source. It creates no native Bukkit entities, chunks or chunk tickets.
Core filters received chunks, player worlds, range and connections and coalesces
updates through the shared packet budget. Every piece has its own real world anchor
so culling also works for beams crossing chunk boundaries.

Hard bounds are 48 pieces per scene, four scenes per caster, twelve scenes globally,
and four scenes per viewer within 64 blocks (at most 192 handles eligible for one
viewer). A viewer's own casts are selected first, then nearby casts. The oldest
visual scene is evicted when a pool fills; damage and projectile collision continue
independently. Shapes update every two ticks with two-tick client interpolation. Lightning instead
adds fixed jagged channel segments without interpolation: its 47 stable slots keep
the previous route in place until an eight-tick fade; unreached slots stay hidden.
Moving marks/projectiles update their logical position between display frames.
FROST's front spans twelve ticks after a two-tick lead-in in a 24-tick scene;
NOVA's eight-block front spans sixteen ticks after the same lead-in in a 20-tick
scene; the emerald layers dissolve together while still advancing. MARK and EMBER
impacts retain handles through a two-tick release transition. MARK retains its
rotation clock and charged shape. LANCE and its particles share four charge ticks,
a four-to-six-tick traversal and twelve ticks of aftermath. The visual muzzle stays
below the reticle; the damage ray still starts at the eye.

Tracked effects expire with their controller, with a final 160-tick safety cap.
All scenes are removed on caster death, quit, world change, expiry or module
shutdown/reload. Particle emission uses bounded loops and retains per-viewer
camera clearance. Approximate emission bounds per cast are 100 positions per
solar ray (three rays for secondary), 206 for a comet impact and 225 for a radial
emerald wave; gravity emits 35 positions per pulse. These bounds are not a
measured TPS/FPS guarantee.

Paper 1.21.11 requires `Color` data for `Particle.FLASH`. A missing payload throws
synchronously, so the old LANCE launch could abort before its display scene was
created whenever another viewer could receive the launch flash. MARK release
was affected too. Every FLASH now carries a color. `StaffSpellParticleContractTest`
exercises the emission path with an observer outside the camera cutoff and checks
payloads against the installed Paper particle types; a solo caster whose near
flash is hidden cannot reproduce that failure.

## Verification

Run the focused staff tests through the repository's Gradle `test` task with the
fully qualified class names, then package with `shadowJar`. Deterministic checks
cover target geometry, occlusion, item identity and delayed-effect lifecycle.
Tests also cover empty casts, manual aim, piercing and swept orb collision.
They do not establish in-client appearance or the feel of fighting moving mobs.
On the selected runtime, use the issuance command and compare the six samples
against moving mobs, an obstructed target and a group, then try switching items
during cooldown. Review EliteMobs scaling/progression in real combat separately.

For the offline preview, run `exportStaffSpellPreview` with
`-PstaffSpellPreviewOutput=/absolute/path/scene.json`, then use the location-atelier
textured preview builder with a vanilla palette. Inspect launch, middle and impact
poses from player-eye, front, side and top views. This reuses runtime geometry but
does not validate Minecraft interpolation, particles, sound or combat feel.
