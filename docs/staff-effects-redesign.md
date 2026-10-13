# Staff sequence and guard design — ARC 1.4.387

The sample catalog contains sixteen available staves (Inferno is temporarily retired). Seven of them use staged,
multi-frame Arsenal sequences; Gravity uses a held channel; the
remaining casts retain their spell-specific attack paths. Left-click controls
are defenses, not the former collection of quick attacks: normal left-click
raises a frontal guard, and Shift + left-click raises an all-around guard.

## Visual references

These references inform timing, silhouette and staged action; they are not
copied assets.

- [GGG: Ball Lightning](https://www.youtube.com/watch?v=LTHoIQyrgQA), 0:02:
  bright cores with short electrical offshoots; informs branching lightning.
- [GGG: Witch](https://www.youtube.com/watch?v=82CGiyshJ0c), 1:36–1:48:
  temporary bone constructs; informs the finite Necromancer squad and Ossuary.
- [GGG: Druid walkthrough](https://www.pathofexile.com/forum/view-thread/3416717/filter-account-type/staff),
  Tornado 5:24–5:40 and Volcano 6:08–6:32: sustained volume and staged
  eruptions; informs Gale's open-center funnel and Tide's travelling water
  sphere and arrival shape.
- [GGG: Disciple of Varashta](https://www.youtube.com/watch?v=bLa90dXVDWE),
  0:35–1:18: spectral summons with distinct attacks; informs soul missiles
  and the stronger Necromancer commander.
- [Blizzard: Season of Witchcraft](https://news.blizzard.com/en-us/article/24167111/master-the-occult-in-season-of-witchcraft):
  written growth and converging-wave descriptions inform the thorn growth.
  This reference was text, not an observed animation.

## Staged Arsenal spells

| Staff | Right-click | Shift + right-click |
| --- | --- | --- |
| Lunar | A large silver crescent falls, then opens a ground shockwave | Two moon blades converge on the target |
| Gale | A stationary open-center funnel forms at the aimed point and lifts nearby enemies | A twisting windscrew ram sweeps up to 36 blocks forward, clipped by cover |
| Tide | A fast water sphere slides above the floor for up to 36 blocks and bursts at its first enemy, cover or range endpoint | A broad, curled foamy crest wave travels forward |
| Requiem | A copper bell assembles over the target point and releases one expanding soul wave after 0.8 seconds | A bell assembles over the caster and rings three expanding waves |
| Prism | A crystal grows at the aim point and sends rays toward up to three visible enemies | Three rays converge on the selected point |
| Nova | Roots travel along the ground, then close and slow enemies | A thorn cage closes around the selected point |
| Ossuary | Dense clusters of five angled bone spikes erupt along a fixed 24-block path | A warning precedes a burst of bone spikes from the ground |

The other nine catalog spells keep their own right-click attacks. Inferno is
retired from issuance and casting; old Elite Inferno items migrate to Ember.
Gravity holds a display boulder in front of the caster while tracking aim, then
throws it on release; it never changes the selected world block. Shift selects
the named secondary cast for ordinary attacks; for Gravity it runs the
same held operation.

## Guards and charge

The frontal guard protects the direction the caster faces, lasts up to 1.2
seconds and absorbs two hits. Fault, Gravity, Ossuary, Nova and Necromancer
guards last up to 1.6 seconds and absorb three. Shift + left-click covers all
directions for up to twenty seconds and absorbs eight HP after armor and
resistance, before native absorption hearts. These defenses use a
separate recovery track from right-click attacks: four seconds after the
frontal guard or twenty-five seconds after the all-around guard. Both guard forms share
the same recovery track with each other.

Only primary Ember and Icicle casts reach full charge in 36 ticks; their
projectile formations grow while held. Other primary and non-channel Shift
attacks launch on right-click. Gravity uses its own held timing, at most eight seconds before release
or timeout. In particular,
Gravity's flight is a bounded display sequence over a clipped path, not a
placed, broken or moving block.

Ember and Icicle release the same display handles and body geometry that grew
during charge. Their local transforms interpolate over the four-tick charge
update interval. Other casts have short, spell-specific preparations above or
ahead of the caster without adding an input delay. The arena and ordinary-world
paths use the same animation code and geometry.

## Runtime bounds and evidence limits

- Authored animation frames are emitted on the main thread in two-tick steps.
  Particle packets are sent only to nearby viewers; each viewer has a shared
  ceiling of 480 packets and 4096 particles per two-tick window. Excess is
  dropped rather than queued. Minecraft controls each native particle's own
  motion and lifetime.
- Damage attempts revalidate captured combat context, target eligibility and
  visibility when the hit is applied. Moving sequences use spell-specific
  collision paths, and each sequence attempts a target at most once per authored damage pulse.
  Requiem explicitly allows one hit per bell wave, with reduced secondary damage. The duel
  bridge still limits duel-only casts to the registered opponent in the
  captured match and round.
- Necromancer's primary cast creates two temporary skeletons; Shift summons one
  larger wither-skeleton commander. These actors have native
  movement with ordinary targeting and attack goals removed, then move and
  attack through the staff controller. They require safe supported ground,
  avoid obstacles rather than pathfind around them, expire after forty-five seconds,
  and stack up to ten actors per caster and thirty-two globally. Their attack
  cadence is sixty ticks plus windup, with sixteen attacks per actor and a
  forty-eight-hit ceiling per squad. Their attacks use the same staff damage and
  protection path.
- Ossuary and Gravity create only visual geometry; neither edits blocks.
  Explosion and gravity impacts may briefly hide up to twelve nearby blocks
  from clients and animate packet-only debris in configured worlds. The real
  block state stays intact and is resent after forty ticks; no drops are created.
  The visual redesign does not set fire or change weather.
- `exportStaffRedesignPreview` can produce source-driven clips for the seven
  Arsenal spells, baseline and enlarged visual states for the held gravity channel,
  and both guard shapes
  for all sixteen available staves. Its offline geometry view does not simulate
  damage, target selection, collision, sound, client lighting or exact vanilla
  particle physics. Changed ground attacks use their configured full range on an unobstructed flat lane;
  other moving attacks use a shorter illustrative lane. A preview
  is not evidence of ordinary-client appearance or audio acceptance.

This document describes source design only. It does not claim that a build,
test run, artifact delivery, runtime activation or ordinary-client review has
passed.
