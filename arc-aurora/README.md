# ArcAurora

First-party biome ambience for RusCrafting. The standalone Paper plugin sends
native particles to individual viewers through ArcCore. It uses vanilla client
particles and needs no resource pack, display entities or terrain changes.
The build targets **Java 25**, **Paper `1.21.11-R0.1-SNAPSHOT`** and **ArcCore
`2.7.69`**. The standalone plugin requires ArcCore and PacketEvents at runtime;
the PacketEvents compile version comes from the repository's dependency catalog.

## Visual direction

The bundled configuration contains **25 ordinary profiles** and eight sky
variants sampled into up to **nine retained world cells** for `treasure-hunt`.
Ordinary ambience keeps its small, separated forms. The sky uses two sizes of
travelling white wave, a wireframe spark cube, a blue soul-flame helix, a
warm flame arc, a faceted three-dimensional crystal, a five-point star and an
occasional meteor. Waves have a short native tail and one fresh point per visit;
cubes, crystals and stars use a sustained refresh. The old green/gold TOTEM ring is absent.
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
| Night sky / treasure hunt | White wavelets, spark cubes, blue curls, warm arcs, crystals, stars and occasional meteors | Automatic configured night window or explicit temporary event lease |

Ordinary shapes pick fresh nearby anchors each pass. Sky positions instead come
from a deterministic jittered world lattice. World UUID, cell coordinates,
profile and lifecycle epoch choose the geometry, orientation and variation.
The observer's position selects nearby cells; yaw and player altitude never
choose or rebase their anchors. Main resolves each anchor's loaded terrain
height once, then checks the full swept silhouette before starting it. Nearby
viewers using the same cell key receive the same world geometry; their admission
and emission phases may differ. There is no shared global scene registry.

Active cells remain selected while they are eligible, including across a new
field epoch. Admission delay does not shorten their own lifetime. Moving through
a cell boundary does not replace the nearest slots. Crossing the outer cull
radius stops the whole figure before consuming its next point; already emitted
particles expire naturally. A culled cell waits for both its lifecycle expiry
and a configurable cooldown before reuse. It never resumes a frozen partial
head or replays a backlog. A bounded retirement-map overflow conservatively
pauses new admission while existing eligible scenes continue.

Main releases one new point per advancing tip per eligible visit. The bundled
waves have no duplicate repair, halving their previous repeated-particle density
without deleting any path samples. Their unique paths and travel remain intact.
A configurable one-time fresh-head repair remains available for custom profiles.
The cube uses three separated tips along a connected twelve-edge walk and
bounded refresh behind its frontier. Two separated tips gradually draw each
crystal and star; the crystal follows all twelve edges of an elongated octahedron,
while the star has five sharp outer points. Their contours remain closed and
translate gently as a whole. The helix and arc are finite flame traces; the
bundled helix now shows a tighter curl within its short native tail. A meteor
flies rapidly through a short path, then splits into four diverging spark trails.
Reversing its spatial direction never reverses that sequence. No persistent
entities are used.

The exact 1.21.11 client gives END_ROD and FIREWORK full-bright light value
`15728880`, lifetimes of 60..71 and 48..59 ticks, and native gravity even at zero
packet speed. END_ROD starts white and changes toward purple; FIREWORK uses its
spark sprite. FLAME and SOUL_FIRE_FLAME share the same native provider with
12..44 tick lifetimes, no gravity, friction 0.96, small randomized spawn/motion,
and size shrinking to half its initial value. Their light increases with age
and is not unconditional full brightness. Blue and warm sprites provide colour
variation without arbitrary recolouring. Dust retains ordinary world lighting.
Full brightness does not illuminate blocks, create reflections or promise bloom.
These are client-source facts, not a native recording of the scene.

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

The bundled `night-sky` mode displays the selected sky preset to everyone in
normal worlds at world time **13000 through 23000**, without a view-permission
grant. Each world's clock is independent; the plugin never changes it. Personal
`/arcaurora off` still disables automatic night figures. Admin preview takes
priority over explicit temporary events, then automatic night mode, then
ordinary ambience. Explicit preview/events retain their existing preference
bypass. At dawn, new submissions stop and native tails finish fading.

| Command | Access | Result |
| --- | --- | --- |
| `/arcaurora`, `/arcaurora toggle` | Player with the view permission, or any player while night mode is enabled | Toggle personal ordinary/night ambience |
| `/arcaurora on`, `/arcaurora off` | Same | Save the explicit preference in the player's PDC |
| `/arcaurora biome` | Player with the configured view permission | Show the exact biome key at the player's block |
| `/arcaurora status` | `arcaurora.admin` | Configured ordinary profile count, active event leases and tracked players |
| `/arcaurora reload` | `arcaurora.admin` | Validate a candidate configuration, then replace the active engine |
| `/arcaurora preview [player]` | `arcaurora.admin` | Toggle a private, automatically expiring 60-second sky preview; console names an online player |

`/aaurora` is an alias. A failed configuration parse retains the previous active
settings. An event preset that is in use cannot be removed during reload.
Existing client particles may finish their lifetime after switching effects off;
the preference stops new ordinary and automatic night emissions.

`night-sky.enabled`, `preset`, `time-start`, `time-end`, `worlds` and
`environments` are reloadable. World names accept `*` globs; environments use
Paper names such as `NORMAL`. A time window crossing midnight is supported.
An enabled night preset must exist and contain profiles; an invalid reload
retains the running engine and settings.

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
| `radius`, `min-distance` | Horizontal admission range; ordinary `2..32`, sky `2..96`. New sky cells must lie in this band and inside `sky-field.view-distance`; active cells use the field's outer cull radius |
| `vertical-min`, `vertical-max` | Ordinary heights relative to feet (`-32..32`); sky heights above the cell anchor's loaded terrain surface (`-32..64`), independent of player Y |
| `shape-size` | Unposed radius/half-extent, ordinary `0.1..8`, sky `0.1..32` blocks |
| `points`, `sky-points` | Ordinary positions `1..24`; sky positions `1..192` across one finite stroke, acknowledged after Main attempts them |
| `sky-scene` | `false` by default; retain a sky anchor and use posed, progressively revealed geometry |
| `scene-duration-ticks` | Sky anchor lifetime after its delayed birth, `40..1200`; default `240`; unfinished strokes expire too |
| `spawn-delay-ticks` | Random delay before each sky scene starts, `0..400`; default `60`; selection prefers different birth times |
| `sky-tracers` | Advancing heads per sky figure, `1..4`; default `1`; each visits a contiguous path section. For `METEOR`, this is flight samples per visit followed by the number of fragment heads |
| `sky-refresh-ticks` | `0` (default) leaves a finite native tail; `1..200` spreads a refresh of revealed positions across this many eligible visits, within the cap |
| `sky-options` | Optional mapping overriding individual fields inherited from top-level `sky-defaults`; see below |
| `motion-period-ticks` | Sky geometric motion period, `40..2400`; default `400` |
| `update-ticks` | Minimum ticks between sky head emissions, `1..200`; default `4`; no catch-up; ordinary cadence is unchanged |
| `particle` | Namespaced native particle key supported by the running API |
| `count` | Native particles per emission, `0..16`; zero still requests one particle with type-specific semantics |
| `offset-x`, `offset-y`, `offset-z` | Native particle spread/direction inputs, each `0..2`; defaults zero |
| `speed` | Native `extra` value, `0..1`; meaning depends on the particle; default zero |
| `dust-color`, `dust-to-color` | `'#RRGGBB'`; dust requires the first colour; transition's second colour defaults to the first |
| `dust-size` | Native dust size, `0.1..2` |

`sky-defaults` provides one shared set of sky controls. An optional `sky-options`
mapping within any ordinary or event profile overrides only the named fields;
these controls affect only `sky-scene: true`. Every setting below is re-read by
`/arcaurora reload`. A successful reload starts a fresh engine generation;
already emitted native particles finish naturally. Invalid values keep the
previous engine and settings active. Admin previews stop on reload; run
`/arcaurora preview` again to view the new settings.

| Sky option | Default; range | Effect |
| --- | --- | --- |
| `yaw-min-degrees`, `yaw-max-degrees` | `0`, `360`; each `-360..360`, min ≤ max | Fixed-world orientation range; never relative to viewer yaw |
| `terrain-clearance` | `1`; `1..32` blocks | Extra surface clearance after native fall and sprite bounds; safety floor remains one block |
| `scale-min`, `scale-max` | `0.8`, `1`; each `0.1..2` | Random size multipliers, min ≤ max; `shape-size × scale-max` must stay ≤ 32 |
| `roll-radians` | `0.45`; `0..π` | Random tilt in either direction; zero disables roll |
| `wave-amplitude` | `0.22`; `0..1` | Vertical wave height as a fraction of randomized shape-size |
| `wave-cycles` | `1`; `0.1..8` | Spatial cycles across the route, before the configured motion phase |
| `wave-slope` | `0.07`; `-1..1` | Vertical slope across the route |
| `wave-depth` | `0.05`; `0..1` | Wave depth component |
| `stroke-drift`, `stroke-lift` | `0.12`, `0.05`; each `-1..1` | Travel and vertical arc added while a figure is first traced |
| `held-drift`, `held-lift` | `0.06`, `0.025`; each `-0.25..0.25` | Coherent translation of refreshed contours; zero leaves that axis still |
| `exit-ticks` | `40`; `0..200` | Staged exit for refreshed contours, capped at one quarter of scene duration; zero stops refresh at expiry |
| `fresh-head-repair` | Bundled `false`; omitted parser fallback `true` for older custom profiles | Optionally repeat the preceding fresh wave head once |
| `fresh-head-max-age-ticks` | `2`; `1..4` | Maximum age of that one-time repair; expired repairs are discarded |
| `helix-turns`, `helix-radius` | `2`, `0.3`; `0.25..8`, `0.05..1` | Coil count and radius fraction for `HELIX` |
| `arc-sweep-degrees` | `240`; `30..330` | Angular span of the open `ARC` |
| `meteor-split` | `0.6`; `0.25..0.85` | Fraction of meteor samples devoted to flight, rounded to a whole tracer group; remaining samples draw the fragments |
| `meteor-spread` | `0.35`; `0.1..1` | Fragment reach relative to meteor shape-size |

| `sky-field` option | Default; range | Meaning |
| --- | --- | --- |
| `cell-size` | `40`; `16..128` blocks | Width of each fixed world cell |
| `jitter` | `0.3`; `0..0.4` | Anchor displacement as a fraction of cell width |
| `view-distance` | `96`; `16..192` blocks | Horizontal radius admitting new cells |
| `cull-hysteresis` | `16`; `0..64` blocks | Extra outer radius for retaining active cells |
| `max-scenes` | `9`; `1..16` | Maximum retained scenes per viewer |
| `max-per-profile` | `2`; `1..16` | Limit on copies of one variant; promotes variety and bounds expensive contours |
| `cull-cooldown-ticks` | `80`; `72..1200` | Minimum whole-cell retirement period after culling, also respecting the old lifecycle expiry |

The 0.1.3 placement options `fan-degrees`, `angular-gap-degrees` and
`anchor-candidates` remain accepted for file compatibility but are obsolete in
the world field. They no longer affect placement. Remove them from a custom file
when convenient; merging missing defaults does not delete old keys. Use the
field controls and fixed-world yaw range instead.

For example, changing the shared wave amplitude and one figure's size variation
requires no copies of the other fields:

```yaml
sky-defaults:
  wave-amplitude: 0.28
  fresh-head-repair: false

event-presets:
  treasure-hunt:
    sky_wave_large:
      # Keep this existing profile's other fields.
      sky-options:
        scale-min: 0.9
        scale-max: 1.1
        roll-radians: 0.2
```

The profile entries define available variants; add/remove a uniquely named entry
to change the catalogue. `sky-field.max-scenes` and `max-per-profile` control how
many can coexist, subject to eligible loaded cells and the packet cap. Width, sampling,
speed, range, height, birth delay, tracers and refresh already use the profile
fields above. For a shorter wave tail, reduce `shape-size` or increase `sky-points` at the
same width. Increasing `update-ticks` also shortens the visible tail but lowers
its cadence. Keep `scene-duration-ticks` long enough for the resulting travel. Particle lifetime, gravity,
texture animation and native colour are client behaviour, not reloadable plugin
parameters. Native safety margins, loaded-chunk checks and workload caps remain
in force for every option set.

Only no-data particles, `minecraft:dust` and
`minecraft:dust_color_transition` are supported. Block, item, vibration and
other particle payloads are rejected. Profile field values are type-checked;
invalid values reject the candidate configuration.

For ordinary profiles, `POINT` repeats the anchor; `LINE` spans the X axis; `CUBE` distributes samples
along its **twelve wireframe edges**; `RING` samples an XZ perimeter;
`CIRCLE` samples its filled disk;
`SPHERE` samples a surface; `WAVE` spans X with a vertical sinusoid. Geometry
uses world axes. Sky geometry has local X width, Y height and Z depth; the
engine retains a deterministic world orientation. Its ring/disk sits in a
slightly tilted frontal plane, the cube uses a continuous edge walk with three repeated connecting edges, and the wave
has a shallow depth component. `HELIX` winds along its width, while `ARC` traces
an open curve. `CRYSTAL` follows the twelve edges of an elongated octahedron;
`STAR` follows a five-point outline with inner radius 43% of its outer radius.
The star is a fixed world plane and becomes foreshortened when viewed edge-on.
`METEOR` uses its dedicated flight-then-fragments sequence and ignores the generic
stroke drift/lift; shape-size, sky-points, update-ticks, sky-tracers and the two
meteor options control its path and speed. A rotated cube can extend up to `sqrt(3) ×
shape-size` from its anchor. Before a sky figure starts, Main checks its entire swept footprint against
loaded world-surface columns, including sprite padding and native falling
clearance. Validation is sliced across visits under the shared deadline; failed
candidates are rejected whole and replanned. Final per-point checks remain, and
a later terrain obstruction retires the whole figure. Ordinary shape points
retain their individual loaded/passable/profile checks. Native motion is client behaviour; transport rejection after submission belongs to the shared packet path.

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
| `viewers-per-tick` | `32` bundled | `1..128`; bounded round-robin audience scan; the Main deadline still applies |
| `attempts-per-viewer` | `8` | `1..64`; nearby anchors attempted per pass |
| `packets-per-viewer` | `12` | `1..128`; particle emission requests per viewer pass |
| `sky-packets-per-viewer` | `36` | `1..128`; per-visit request ceiling for retained sky figures, not a target batch size |
| `native-budget-micros` | `1500` | `1..10000`; cooperative Main-thread deadline per tick |
| `ping.max-current-ms` | `250` | `1..10000`; current-ping ceiling |
| `ping.max-average-ms` | `180` | `1..10000`; rolling-average ceiling |
| `ping.window-samples` | `10` | `1..120`; fixed-size rolling window |
| `ping.sample-interval-ticks` | `20` | `1..1200`; minimum sample spacing |

At 20 TPS, the default ordinary cadence allows at most 24 emission requests per
second per viewer before other caps. The bundled world field has at most nine
scenes and two of each profile. The shared ceiling stays **36 requests per visit**.
Two fully held cubes, two crystals, two stars and three one-point traces use
33 requests per visit. Simultaneous growth or a brief meteor can reach the cap;
per-viewer scene rotation retains fairness and unattempted heads stay pending.
Startup delays, finite tails, expiry, culling and gates reduce the usual load.
Each wave keeps the accepted single-head density. All bundled positions use `count=1` and zero
packet spread/speed. Increasing count does not fill gaps between path positions.

These are ceilings, not throughput or FPS measurements. `viewers-per-tick`
round-robin visitation can reduce each viewer's cadence when the audience is
larger. Native deadlines, ping, loaded terrain and ArcCore's shared byte budget
can reduce it further. A refresh period counts eligible visits: if 40 visits take
longer than END_ROD's 60-tick minimum lifetime, held contours can develop gaps.
A large audience therefore needs native cadence/visibility observation; this
configuration does not promise every viewer 20 Hz. Viewer and profile start order rotate independently so
a small cap cannot permanently prefer the same figure. Mixed sky/ordinary
profiles share the configured per-viewer allocation; ordinary output retains
its own cap.

Main owns bounded player/world capture, per-tip dispatch and final validation.
The lifecycle-owned worker prepares immutable paths of at most 192 positions
per scene and refreshes captured viewer inputs at the ordinary interval.
ArcCore encodes/transmits through its shared Netty path. One replaceable async
batch is discarded after four ticks; retained sky strokes continue independently
of later worker delays. A late visit advances its current tips and bounded refresh; it never catches up
missed timed frames. A wave's pending fresh repair expires after two ticks. Only
points actually attempted by Main advance the cursor; an unattempted tail is
kept, without replaying old timed frames. An attempt can still be rejected by
terrain, ping or the transport budget and is not a delivery acknowledgement.
Whole-scene admission checks at most 24 terrain columns per scene visit, with
a deadline check between each column. It requires the swept lower edge to be
above `WORLD_SURFACE + terrain-clearance` (default 1), including native fall and
0.22-block sprite padding for END_ROD/FIREWORK; flame uses 0.5 blocks to include
its larger quad, native spawn jitter and small random drift. Unloaded initial
surface/footprint columns leave admission pending and resumable. They do not
load chunks or permanently reject a cell. Invalid height/terrain rejects it.
The shared cooperative deadline covers dispatch, validation and capture;
it cannot preempt one native call. Lifecycle, world, revision and exact event
lease checks still fence every retained emission. The old four-block captured-
position guard applies only to ordinary emissions. Sky culling reads the current
observer position and stops whole figures; ordinary walking/flying and
same-world teleports never rebase a sky path. Cross-world changes invalidate it.

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

The treasure-hunt preset supplies ambience above the surrounding terrain. Its sky figures
are not treasure markers and do not change discovery, rewards or gameplay.

| Variant | New-cell distance; height above terrain | Growth and activity at one eligible visit per tick |
| --- | --- | --- |
| Medium wave | 28..96 blocks; Y +26..54 | 192 END_ROD positions over 9.6 s; no hold; 220 tick lifecycle, delay 0..24 |
| Large wave | 36..96 blocks; Y +32..64 | 192 END_ROD positions over 9.6 s; no hold; 212 tick lifecycle, delay 0..30 |
| Cube | 28..96 blocks; Y +28..60 | 192 FIREWORK positions / three heads: 3.2 s growth, about 11.8 s refresh, 2 s staged exit; delay 0..70 |
| Soul helix | 30..96 blocks; Y +36..60 | 144 SOUL_FIRE_FLAME positions over 7.2 s; 3.5 spatial turns, short native curl, no hold; 180 tick lifecycle, delay 0..30 |
| Warm arc | 28..96 blocks; Y +32..56 | 96 FLAME positions over 4.8 s; short native curved tail, no hold; 132 tick lifecycle, delay 0..25 |
| Crystal | 30..96 blocks; Y +30..58 | 160 END_ROD positions / two heads: 4 s growth, about 12 s held contour, 2 s staged exit; delay 0..55 |
| Five-point star | 30..96 blocks; Y +32..60 | 120 END_ROD positions / two heads: 3 s growth, about 12 s held contour, 2 s staged exit; delay 0..75 |
| Meteor | 40..96 blocks; Y +36..62 | 80 FIREWORK positions over 1 s: 0.6 s flight then 0.4 s four-fragment spread; no refresh, 600 tick lifecycle and delay 0..160 make it occasional |

Timings assume 20 TPS and one eligible visit each tick. All scenes start their
lifetime after full terrain admission and their birth delay; a field epoch
change never cuts an already admitted lifecycle short. Old wave points are
never refreshed. The medium/large wave half-sizes remain 9/13 blocks, with native
60..71 tick lifetimes leaving short moving wakes. Random scale is 80..100%;
roll is ±0.45 radians. Cube half-size is 4, helix half-width 7, arc radius 5.
Crystal half-height is 5; star outer radius is 5.5. Their roll is ±0.2 radians,
with zero stroke drift/lift to keep the contour closed. They refresh every
40 eligible visits, leaving a margin before END_ROD expiration at normal cadence.
Meteor half-length is 8, roll is ±0.25 radians; existing particles finish their
48..59 tick native lifetimes after its one-second emission sequence. Increase
its scene-duration or spawn-delay for a less frequent accent, update-ticks or
sky-points for slower travel, and meteor-spread for longer fragments.

Ranges describe anchors, not every particle. A separate two-block eye clearance
remains: approaching an actual point can retire the entire scene. It never
carves out just that point. No chunks are loaded. Retired cells cool down rather
than reviving an old partial tail. Native clouds, texture rendering and transport
loss still require real-client observation; source playback is not that proof.

## Build and publication

Run from the repository root with JDK 25:

```sh
./gradlew :arc-aurora:test :arc-aurora:shadowJar
```

The expected local runtime artifact is
`arc-aurora/build/libs/ArcAurora-0.1.5.jar`. The public Maven API artifact
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
Our travelling sine wavelets, helixes and arcs are original interpretations, not exact
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

`sky-points` overrides `points` only for sky scenes. The nightly mode and current shape catalog
require ArcAurora 0.1.5; do not reload this configuration into an older runtime.
The 25 ordinary biome profiles and their sampling behavior are unchanged.
