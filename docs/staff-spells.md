# Prototype staff attacks

`/arc stafftest` gives the caller six mechanics samples. The existing `arc.test`
administrator permission controls item issuance. `/arc stafftest chain|mark|frost|lance|ember|nova`
gives one sample; an optional final player name allows console/operator delivery.
The entire selected set must fit in free inventory slots before anything is given.
There are no loot-table or shop changes. Anyone holding an issued sample can use it.

- **Грозовая ветвь / chain:** the Divine Staff (`3dfantasyweaponscit:divine_staff`)
  launches instant lightning that jumps through up to four distinct targets joined
  by visible links. Each jump has reduced damage.
- **Разрыв / mark:** the Night Staff (`3dfantasyweaponscit:night_staff`) places a
  short-lived mark that follows the acquired mob, then detonates at its current
  position and damages nearby visible mobs. With no target it forms at the aimed
  wall/range endpoint instead.
- **Ледяной хлопок / frost:** the Northgate Guardian Staff
  (`3dfantasyweaponscit:northgate_guardian_staff`) sends a wide cone that damages
  nearby visible mobs; slowness applies only after an actual health/absorption
  reduction. It does not steer; point the cone yourself.
- **Солнечное копьё / lance:** the Winged Staff
  (`3dfantasyweaponscit:sagrada_winged_staff`) fires an instant narrow golden beam,
  piercing up to three mobs directly along the reticle. There is no soft lock.
- **Пепельная комета / ember:** the Hermit Staff
  (`3dfantasyweaponscit:bermunde_hermit_staff`) launches a straight flying fire orb.
  Lead moving targets manually; it bursts on the first mob/wall or at maximum
  range. Collision is checked along each movement segment, not just at endpoints.
- **Изумрудная волна / nova:** the Celtic Staff
  (`3dfantasyweaponscit:holy_celtic_staff`) strikes visible mobs within six blocks
  around the caster once, with a four-block turquoise whirlwind and an expanding
  ground ring. The whirlwind forms up to 3.5 blocks ahead, inside the wave area,
  so the caster can see it; the damage and particle ring stay centered on the caster.
  The whirlwind is visual; it does not add repeated damage or pull mobs.

Use the main-hand right click, including a direct click on an entity. Look near a
mob for chain/mark; small particles over its head preview their selected target.
The other four spells do not acquire a soft-lock target. Every spell can fire into
empty space and consumes its cooldown on a miss. All samples share
one caster cooldown, so swapping sample items cannot bypass it. Walls block initial
acquisition, every chain link, and blast damage. Players, NPCs, armor stands and
tamed pets are excluded. EliteMobs also applies its instance/minion eligibility.
Marks and flying orbs cancel on caster death, quit, world change, module reload
or shutdown. Acquired marks also cancel on an invalid/distant target. Flight has
a fixed maximum range and at most eight orbs per caster; visual bursts are short
and use client-only block displays, sparse particles and local sounds. Each cast captures combat facts, so
changing the held item cannot change a delayed hit's level or critical roll.

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

The six source-driven silhouettes are a branching lightning rod, an orbiting violet
seal that bursts into crystals, a fan of ice blades, a golden spear, a magma orb
with orbiting fragments, and a turquoise whirlwind. The mark and orb follow their
existing combat positions; the beam, cone and nova retain their manual aiming and
single-hit behavior. Casting into empty space also produces the display effect.

`StaffSpellDisplayGeometry` is shared by the renderer and offline textured preview.
`StaffSpellDisplayEffects` reuses core `PaperPacketDisplays` under the `staff-spells`
visual budget source. It creates no native Bukkit entities, chunks or chunk tickets.
Core filters received chunks, player worlds, range and connections and coalesces
updates through the shared packet budget. Every piece has its own real world anchor
so culling also works for beams crossing chunk boundaries.

Hard bounds are 48 pieces per scene (NOVA uses 48; the other spells use at most 32), four scenes per caster, twelve scenes globally,
and four scenes per viewer within 32 blocks (at most 192 handles eligible for one
viewer). A viewer's own casts are selected first, then nearby casts. The oldest
visual scene is evicted when a pool fills; damage and projectile collision continue
independently. Shapes update every four ticks with four-tick client interpolation.
Moving marks/projectiles update their logical position between display frames.
Ordinary effects last 12–28 ticks; tracked effects expire with their mark/projectile,
with a final 160-tick safety cap. All scenes are removed on caster death, quit,
world change, expiry or module shutdown/reload. The limits bound effect size and
traffic sources; they are not a measured TPS/FPS guarantee.

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
