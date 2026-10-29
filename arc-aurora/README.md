# ArcAurora

First-party biome ambience for RusCrafting. The standalone Paper plugin sends
native particles to individual viewers through ArcCore. It uses vanilla client
particles and needs no resource pack, display entities or terrain changes.
The build targets **Java 25**, **Paper `1.21.11-R0.1-SNAPSHOT`** and **ArcCore
`2.7.69`**. The standalone plugin requires ArcCore and PacketEvents at runtime;
the PacketEvents compile version comes from the repository's dependency catalog.

## Visual direction

The bundled configuration contains **25 ordinary profiles** and a three-figure
`treasure-hunt` sky scene. Ordinary ambience uses small, separated forms.
Native fireflies blink, petals and snowflakes use their native motion, and
coloured dust supplies short threads, clusters and sparse halos. The hunt uses
a broad luminous wave, a wireframe cube and a falling halo, held above the player for several
seconds so their complete silhouettes can accumulate within a bounded budget.
These are original presets; Aurora code, configuration files and assets were
not imported.

| Environment | Bundled treatment | Main condition |
| --- | --- | --- |
| Unknown vanilla/custom biomes | Small neutral motes | Clear open air, or a quieter shaded fallback |
| Meadows and plains | Pollen patches by day; firefly constellations at dusk | Clear weather |
| Forests and taiga | Small spherical firefly groups | Dusk/night, some sky exposure |
| Birch / cherry / pale garden | Cream threads / native petals / pearl motes | Birch by day; pale garden at night |
| Snow, ice and frozen water | Native drifting snow; tiny frost constellations | Brighter constellations only on clear nights |
| Exposed highlands | Short blue-grey waves | Y ≥ 90, clear weather |
| Desert / badlands / savanna | Sand-coloured waves / ember clusters / isolated warm motes | Badlands clusters at night |
| Jungle / wetlands | Native spores under the canopy / small green-gold clusters | Wetland clusters on clear evenings/nights |
| Coasts, oceans and rivers | Short turquoise wisps above the surroundings | Clear night, exposed sky |
| Mushroom fields | Small violet-to-teal halos | Any time |
| Lush / dripstone / deep-dark caves | Spores / warm specks / quiet blue wavelets | Low skylight, actual 3D cave biome |
| Nether / End | Embers, red threads, teal wisps, soul lights / violet clusters | Dimension-specific biome keys |
| Treasure hunt | A 12-block sine ribbon, 5-block wire cube and 8-block falling halo in the sky | Explicit temporary event lease; three native full-bright particle types |

Ordinary shapes pick fresh anchors each pass. Sky profiles retain a world
anchor, initial facing and sampling cursor for their configured scene duration.
The worker generates a complete deterministic frame; successive passes draw
different parts of it, keeping all points eligible under the same packet cap.
Slow wave motion, gradual cube rotation and shallow halo tilt let the outlines
accumulate while previous particles follow their native motion and fade.
There are no persistent Minecraft entities.

Night visibility is a property of the chosen native particle. In the **1.21.11
client**, END_ROD, FIREWORK and TOTEM particles inherit
`SimpleAnimatedParticle.getLightColor`, which returns full-bright `15728880`.
Their native sprites and motion remain different: END_ROD starts white and
fades toward muted purple; FIREWORK supplies a spark sprite; TOTEM has native
green/yellow colour variation. `DUST` and `DUST_COLOR_TRANSITION` instead sample
world light. The sky preset uses zero spread/speed and does not promise
arbitrary RGB recolouring. Full brightness does not create light on blocks,
reflections or shader bloom, and ordinary dust profiles retain their existing
lighting behaviour.

Zero packet speed does not cancel native gravity. Inspected client lifetimes
are 60..71 ticks for END_ROD/TOTEM and 48..59 ticks for FIREWORK. From rest,
their respective falls after 32 ticks are approximately 0.124, 3.81 and 1.0
blocks. The wave therefore uses END_ROD, the cube rotates very slowly, and the
TOTEM halo is intentionally a falling green/gold cascade rather than a
stationary ring. These are source-derived client behaviours, not an in-game
measurement of this scene.

The original author's published documentation describes a separate
`particle.enableLighting` option and warns
that it can leave ghost light after chunk unload; `/aurora fixlighting` refreshes
nearby chunk lighting. Its published `END_ROD_WAVE` preset disables that option,
while `FALLING_NECTAR_WAVE` enables it. Those facts support selecting a luminous
native sprite without reproducing world-light manipulation. Sources:
[author's particle configuration](https://gitlab.com/Zenya4/aurora/-/wikis/Particle-Configuration),
[commands](https://gitlab.com/Zenya4/aurora/-/wikis/Commands),
[END_ROD wave preset](https://gitlab.com/Zenya4/aurora/-/blob/master/core/src/main/resources/particles/END_ROD_WAVE.json),
[nectar wave preset](https://gitlab.com/Zenya4/aurora/-/blob/master/core/src/main/resources/particles/FALLING_NECTAR_WAVE.json).

## Player access and commands

Ordinary ambience requires `arcaurora.view`, whose bundled permission default
is **false**. Grant it to the intended audience through the server's permission
owner. `arcaurora.admin` defaults to operators and controls reload/status.

| Command | Access | Result |
| --- | --- | --- |
| `/arcaurora`, `/arcaurora toggle` | Player with the configured view permission | Toggle ordinary ambience |
| `/arcaurora on`, `/arcaurora off` | Same | Save the explicit preference in the player's PDC |
| `/arcaurora biome` | Same | Show the exact biome key at the player's block |
| `/arcaurora status` | `arcaurora.admin` | Configured ordinary profile count, active event leases and tracked players |
| `/arcaurora reload` | `arcaurora.admin` | Validate a candidate configuration, then replace the active engine |
| `/arcaurora preview [player]` | `arcaurora.admin` | Toggle a private, automatically expiring 60-second sky preview; console names an online player |

`/aaurora` is an alias. A failed configuration parse retains the previous active
settings. An event preset that is in use cannot be removed during reload.
Existing client particles may finish their lifetime after switching effects off;
the preference stops new ordinary emissions.

The admin preview displays the hunt's sky preset only to its target. It does
not start a treasure hunt, change rewards, grant permissions or create a
world-wide event. It ends after 60 seconds or when toggled again, and still
obeys both ping gates. Look above the horizon to inspect the silhouettes.

Messages are MiniMessage scalars in `locales/ru.yml` and `locales/en.yml`.
Russian client locales receive Russian; other clients receive English; console
uses Russian. The `<prefix>` placeholder inserts the localized prefix once.
Diagnostic values are supplied as literal Adventure components.

## Configuration and custom biomes

Edit `plugins/ArcAurora/config.yml`. Profiles are mappings under `profiles`;
event profiles live under `event-presets.<preset>.<profile-id>`. The loader
accepts at most **128 profiles in total**, including event profiles. Missing
global defaults are merged from the bundled file; profile, event-preset and
alias sections remain administrator-owned.

Ordinary profiles must match at both the viewer's current block and each
emission position. The highest matching priority wins; equal-priority profiles
share the emission budget in configuration order. World time supplies the
ordinary time filter. Time bounds are inclusive and can wrap midnight, such as
`time-start: 23000` and `time-end: 1000`.

Biome matching uses actual namespaced 3D biome keys. Bare names such as
`forest` normalize to `minecraft:forest`; `*` is the only wildcard.
`minecraft:*ocean` matches vanilla ocean families, while `*` covers any
namespace. Exact aliases are applied once before matching; aliases do not chain
or change the world biome.

Iris namespaces and paths depend on the installed world-generation pack.
No specific Iris key is assumed to exist. First read `/arcaurora biome`, then
either add the observed key to a profile, use an intentional family glob, or
define an exact alias. For example, **after checking real keys**, an
`iris:*forest*` selector could join the woodland profile. The config includes
commented examples; unknown custom keys already receive the restrained
fallback profiles when their environment matches.

```yaml
# Illustrative only: replace the source with an observed key.
biome-aliases:
  'iris:your_verified_woodland': 'minecraft:forest'
```

| Profile field | Meaning and accepted range |
| --- | --- |
| `priority` | Integer; only the highest matching ordinary priority is used |
| `biomes`, `worlds` | Nonempty lists of selectors; default `['*']`; world names are case-sensitive |
| `time-start`, `time-end` | Inclusive world-time bounds, each `0..23999`; defaults cover the full day |
| `weather` | `ANY`, `CLEAR`, `RAIN`, `THUNDER`; rain excludes thunder; weather reads world state |
| `min-y`, `max-y` | Absolute Y bounds, each `-2048..2048`; actual world build height is also enforced |
| `min-sky-light`, `max-sky-light` | Raw block skylight, `0..15`; this is exposure, not a second clock |
| `underwater` | `false` by default; `true` also permits water, not other liquids |
| `radius`, `min-distance` | Horizontal anchor distances in blocks, `2..32`; minimum must not exceed radius |
| `vertical-min`, `vertical-max` | Anchor heights relative to the viewer's feet, each `-32..32` |
| `shape-size` | Unposed radius/half-extent, ordinary `0.1..8`, sky `0.1..32` blocks |
| `points`, `sky-points` | Positions in the complete shape, ordinary `1..24`, sky `1..192`; sky passes resume a cyclic cursor |
| `sky-scene` | `false` by default; retain a sky anchor and use posed, slowly moving geometry |
| `scene-duration-ticks` | Sky anchor lifetime, `40..1200`; default `240` |
| `motion-period-ticks` | Sky geometric motion period, `40..2400`; default `400` |
| `update-ticks` | Sky planning cadence, `1..200`; default `4`; ordinary profiles retain the global interval |
| `particle` | Namespaced native particle key supported by the running API |
| `count` | Native particles per emission, `0..16`; zero still requests one particle with type-specific semantics |
| `offset-x`, `offset-y`, `offset-z` | Native particle spread/direction inputs, each `0..2`; defaults zero |
| `speed` | Native `extra` value, `0..1`; meaning depends on the particle; default zero |
| `dust-color`, `dust-to-color` | `'#RRGGBB'`; dust requires the first colour; transition's second colour defaults to the first |
| `dust-size` | Native dust size, `0.1..2` |

Only no-data particles, `minecraft:dust` and
`minecraft:dust_color_transition` are supported. Block, item, vibration and
other particle payloads are rejected. Profile field values are type-checked;
invalid values reject the candidate configuration.

For ordinary profiles, `POINT` repeats the anchor; `LINE` spans the X axis; `CUBE` distributes samples
along its **twelve wireframe edges**; `RING` samples an XZ perimeter;
`CIRCLE` samples its filled disk;
`SPHERE` samples a surface; `WAVE` spans X with a vertical sinusoid. Geometry
uses world axes. Sky geometry has local X width, Y height and Z depth; the
engine retains its initial facing toward the viewer. Its ring/disk sits in a
slightly tilted frontal plane, the cube rotates as a wireframe, and the wave
has a shallow depth component. A rotated cube can extend up to `sqrt(3) ×
shape-size` from its anchor. Shape points are independently
checked against loaded chunks, passable blocks and the selected ordinary
profile. Occluded or over-budget points can be omitted. Native sprite movement
and randomized spread after emission remain client behaviour.

## Ping and workload limits

Both ping checks must pass, including for event effects. Defaults are a current
ping of at most **250 ms** and a rolling average of at most **180 ms**. The
window holds **10 samples**, nominally sampled every **20 ticks** when the
viewer is visited. Busy viewer rotation or a longer emission interval can make
sampling less frequent. The average uses the samples already available; an
empty window or negative sample rejects admission. A negative sample remains
rejecting until it leaves the window. Current and rolling-average ping are
checked on Main before particle submission. A spike after that capture and
before an asynchronous socket write cannot be observed atomically. Already
emitted particles finish their natural client lifetime. The plugin observes
ping and does not change networking settings.

| Global field | Default | Accepted range / purpose |
| --- | --- | --- |
| `interval-ticks` | `10` | `1..1200`; minimum time between ordinary viewer planning passes |
| `viewers-per-tick` | `8` | `1..128`; bounded round-robin audience scan |
| `attempts-per-viewer` | `8` | `1..64`; nearby anchors attempted per pass |
| `packets-per-viewer` | `12` | `1..128`; particle emission requests per viewer pass |
| `sky-packets-per-viewer` | `36` | `1..128`; separate request cap for retained sky figures |
| `native-budget-micros` | `1500` | `1..10000`; cooperative Main-thread deadline per tick |
| `ping.max-current-ms` | `250` | `1..10000`; current-ping ceiling |
| `ping.max-average-ms` | `180` | `1..10000`; rolling-average ceiling |
| `ping.window-samples` | `10` | `1..120`; fixed-size rolling window |
| `ping.sample-interval-ticks` | `20` | `1..1200`; minimum sample spacing |

At 20 TPS, the default ordinary cadence allows at most 24 emission requests
per second per viewer before other caps. The hunt's four-tick sky cadence and
36-request cap allow at most 180 requests per second. Both use `count=1`, so
these presets request one native particle per position. Increasing `count`
increases client particle work without increasing the request count. This is
not a TCP-packet count, throughput guarantee or FPS benchmark.
Actual output may be lower because of permissions, ping, terrain, audience
rotation, deadlines or ArcCore's shared byte budget.

The Main thread owns bounded player/world capture and final validation.
Detached geometry and emission preparation run on a lifecycle-owned async
worker. ArcCore encodes/transmits through its Netty packet path. The engine
keeps one replaceable completed batch, discards batches older than four ticks,
and does not replay a cosmetic backlog. Its deadline is checked between work
items; it is not a preemptive hard limit on an individual native call.

No chunk is deliberately loaded for ambience. No entities are spawned, and no
world lighting, blocks or biome data are changed. Closing the runtime removes
pending work and viewer emitters; already-sent native particles dissipate on
the client. These design limits do not replace measurements of server time,
bandwidth or client frame time.

## Temporary event API

Consumers use `ru.ruscrafting.arc:arc-aurora-api:0.1.0` as a compile-only API
and obtain `ru.arc.aurora.api.ArcAuroraService` from Bukkit's ServicesManager.
ArcAurora owns the provider; an unavailable provider means the integration must
continue without ambience.

```kotlin
// On Paper's primary thread; retain the handle for the event's lifetime.
val service = Bukkit.getServicesManager().load(ArcAuroraService::class.java)
val lease: AutoCloseable? = service?.startWorldEvent(world.uid, "treasure-hunt")

// On completion, cancellation, consumer reload or shutdown, on the same thread:
lease?.close()
```

The lease covers every present and later-joining player in that world. It
explicitly bypasses ordinary view permission, personal toggle and ordinary
profile selection. Event presets are used as complete layers; their biome,
time, weather, priority and skylight selectors are not applied. This supports a
hunt that presents night through per-player time while world time stays at
day. Ping checks, loaded/passable terrain, world build height, each profile's
water allowance and packet/work budgets still apply.

The newest open event wins per world; closing it reveals an older still-open
event or ordinary ambience. Closing is idempotent and retires only its own
lease. Start and close must run on Paper's primary thread. Unknown worlds or
presets fail before adding a lease; there can be at most 128 open leases.
Provider shutdown retires all leases. The caller remains responsible for
closing its handle on every terminal path. The source uses the shared
`PaperPluginHotload` prepare/status contract and creates the Main-thread runtime
on startup. A real loader prepare/unload/load cycle has not been tested here.

The treasure-hunt preset supplies ambience above viewers. Its sky figures
are not treasure markers and do not change discovery, rewards or gameplay.

The three figures have **144 exact positions in total**, one native particle
per position, and no random spread. Sky passes run every four ticks with a
separate 36-request cap; ordinary cadence/cap and the 1500 µs cooperative Main
budget remain unchanged. At 20 TPS that allows up to **180 requests/particles
per second per viewer**: drawing all positions once takes nominally
**1.2 seconds** before terrain, ping and runtime gates. This replaces the former
near-eye dust clusters (96 particles in 24 requests per second) with larger
coherent silhouettes and distinct native sprites.

| Sky figure | Anchor footprint relative to feet | Geometry and movement |
| --- | --- | --- |
| Wave | Horizontal 24..30 blocks; Y +12..15; held for 240 ticks | 48 END_ROD points; width 12 blocks, roughly 3-block vertical span; one sine cycle travelling over 800 ticks |
| Cube | Horizontal 24..30 blocks; Y +11..14; held for 320 ticks | 72 FIREWORK points across twelve 5-block edges; a slow 2400-tick rotation preserves the outline despite native downward drift |
| Falling halo | Horizontal 24..30 blocks; Y +14..17; held for 400 ticks | 24 green/yellow TOTEM points on an 8-block tilted ring; shallow 600-tick tilt and native gravity create a falling cascade |

Vertical ranges and the 24..30-block horizontal range describe anchors, not
every emitted point. Sky points have a separate two-block eye clearance so
nearer edges of a large shape are not clipped by its anchor distance. Ordinary
points retain their configured minimum eye distance. Player movement can change the
viewing angle; loaded terrain and build-height checks can remove parts of a
figure. Native particles finish their own lifetime after an anchor expires.
Source geometry, a mathematical projection and client light-code inspection
do not establish native-client visual acceptance or a performance benchmark.

## Build and publication

Run from the repository root with JDK 25:

```sh
./gradlew :arc-aurora:test :arc-aurora:shadowJar
```

The expected local runtime artifact is
`arc-aurora/build/libs/ArcAurora-0.1.1.jar`. The public Maven API artifact
`ru.ruscrafting.arc:arc-aurora-api:0.1.0` was verified on 2026-10-29 in the
[RusCrafting repository](https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/arc/arc-aurora-api/0.1.0/arc-aurora-api-0.1.0.pom).
That API publication does not establish a runtime-plugin build, delivery,
activation or a successful hotload cycle. Those results must be recorded
separately when the corresponding operation is performed.

## Reference and verification scope

Aurora's original author documents seven geometric particle families,
per-biome groups, relative/fixed height, configurable intervals and counts in
the [particle wiki](https://gitlab.com/Zenya4/aurora/-/wikis/Particle-Configuration).
The [global config](https://gitlab.com/Zenya4/aurora/-/wikis/General-Configuration)
uses a three-**chunk** radius and an all-day window; those are historical Aurora
defaults, not ArcAurora values. Its [permissions](https://gitlab.com/Zenya4/aurora/-/wikis/Permissions)
and [WorldGuard rules](https://gitlab.com/Zenya4/aurora/-/wikis/WorldGuard-Compatibility)
describe a different access/region model. ArcAurora has no WorldGuard adapter.
Weather, skylight, ping gates and the supplied colours/densities are our choices.

The authenticated [Spigot Aurora page](https://www.spigotmc.org/resources/☄️aurora☄️-ambient-particle-display-customisable-per-biome.89399/)
and publisher's [Aurora listing](https://voxel.shop/p/9484) link the inspected
promotional GIFs: [ice](https://imgur.com/xqjEWXI),
[desert](https://imgur.com/KCWIpGz), [badlands](https://imgur.com/JriJyP3) and
[coast](https://imgur.com/6Z3y7QK). The ice and coast GIFs from the Spigot page
were inspected at four phases across their 6.3-second loops: separated luminous
wire cubes and short curved travelling streaks occupy the sky, with substantial
empty space. They establish the reference composition, not ArcAurora rendering.
Our longer sine ribbon and falling halo are original interpretations, not exact
copies of the GIF motion. The linked [showcase video](https://www.youtube.com/watch?v=neFruXTTRNc)
was located but its playback was not reviewed. The public Zenya wiki is
historical; it is not proof of the current commercial release's behaviour.

Source checks can establish configuration validity, selectors, particle data,
geometry and lifecycle rules. They do not establish native appearance or cost.
The bundled presets still need a real-client comparison of a forest night and
a coastal night, with turning/movement, clear/rain transitions, toggle, event
end and the user's particle settings. No native-client visual acceptance or
measured performance result is claimed by this README. Publication, artifact
delivery and runtime activation require separate operational evidence.

`sky-points` overrides `points` only for sky scenes. Bundled sky profiles retain a legacy-safe `points: 24` so the configuration can be delivered before the 0.1.1 JAR without breaking the running 0.1.0 parser.
