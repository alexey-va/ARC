# Staff sequence redesign — ARC 1.4.374

The original seven RMB abilities remain recognizable; the ember body grows over
its first 16 ticks to avoid appearing at full size when it crosses camera clearance.
Six later RMB families and all LMB gestures now have separate sequence owners.

## Visual references

These are references for timing, silhouette and staged action, not copied assets.

- [GGG: Ball Lightning](https://www.youtube.com/watch?v=LTHoIQyrgQA), 0:02:
  bright cores with short electrical offshoots; informs branching quick lightning.
- [GGG: Witch](https://www.youtube.com/watch?v=82CGiyshJ0c), 1:36–1:48:
  temporary bone constructs; informs the finite necromancer squad.
- [GGG: Druid walkthrough](https://www.pathofexile.com/forum/view-thread/3416717/filter-account-type/staff),
  Tornado 5:24–5:40 and Volcano 6:08–6:32: sustained volume and staged eruptions.
  The adaptation uses a moving cloud funnel and sequential water geysers.
- [GGG: Disciple of Varashta](https://www.youtube.com/watch?v=bLa90dXVDWE), 0:35–1:18:
  spectral summons with distinct attacks; informs soul ribbons and a stronger commander.
- [Blizzard: Season of Witchcraft](https://news.blizzard.com/en-us/article/24167111/master-the-occult-in-season-of-witchcraft):
  the written growth/withering and converging-wave descriptions inform the grove bloom.
  This last reference was text, not observed animation.

## Ability identities

| Staff | RMB | Shift + RMB |
|---|---|---|
| Lunar | Falling silver crescent, then ground shockwave | Two crossing moon blades |
| Gale | Moving cloud funnel | Broad rolling gust |
| Tide | Four to six staggered geysers | Curling wall of water |
| Requiem | Converging soul ribbons | Outward soul lament |
| Prism | Growing focus crystal, then three rays | Three rays converge at the target |
| Grove | Sequential branching roots | Growing flower releases a pollen wave |
| Necromancer | Two to four temporary skeletons | One armored wither-skeleton commander |

All 28 LMB/Shift-LMB gestures use native particles and independent contact shapes.
They do not replay a shortened RMB Display scene. Examples include frost breath,
a solar cross-cleave, an ice shotgun, water whip, soul stream and bone volley.
The charge meter and cooldown remain boss bars; full charge changes RMB size,
formation or count. Native particles retain their Minecraft lifetime and motion.

## Runtime bounds and validation

- New particle sequence frames run every two ticks with one audience lookup per frame.
- New authored frames share a per-viewer ceiling of 480 packets / 4096 particles
  per two ticks. Excess is discarded immediately, not queued.
- Each target is struck once per ordinary sequence, at its contact phase; every
  hit rechecks combat context, eligibility and line of sight.
- Summons have AI, pickup, persistence and drops disabled. They live for ten
  seconds, use bounded native velocity on supported ground, and deal damage only
  through the staff damage bridge after a visible windup. One squad per caster,
  four actors per caster, sixteen globally, six successful hits per squad.
- Summons do not pathfind around obstacles. They stop when the next footprint is unsafe.
- Geometry previews establish shape and timing. Browser bot captures establish
  received packets and approximate rendering, not vanilla-client visual/audio acceptance.
- Deployment for this iteration is scoped to Spawn only.
