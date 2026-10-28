# ArcAurora

First-party biome ambience for RusCrafting. The standalone Paper plugin sends
native particles to individual viewers through ArcCore. It uses vanilla client
particles and needs no resource pack, display entities or terrain changes.
The build targets **Java 25**, **Paper `1.21.11-R0.1-SNAPSHOT`** and **ArcCore
`2.7.69`**. The standalone plugin requires ArcCore and PacketEvents at runtime;
the PacketEvents compile version comes from the repository's dependency catalog.

## Visual direction

The bundled configuration contains **25 ordinary profiles** and a two-layer
`treasure-hunt` event preset. Small, separated forms leave the player's useful
view open. Native fireflies blink, petals and snowflakes use their native
motion, and coloured dust supplies short threads, clusters and sparse halos.
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
| Treasure hunt | Gold constellations and higher blue ribbons | Explicit temporary event lease |

Shapes are emissions, not persistent scene objects. Each pass selects fresh
anchors near the viewer; existing particles then follow the client's native
lifetime and motion. A WAVE changes its sampled sine phase over 40 ticks, but
the plugin does not maintain a travelling ribbon or rotate a persistent cube.
Lighting, reflections and bloom in reference footage are not supplied by
particle packets.

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

`/aaurora` is an alias. A failed configuration parse retains the previous active
settings. An event preset that is in use cannot be removed during reload.
Existing client particles may finish their lifetime after switching effects off;
the preference stops new ordinary emissions.

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
| `shape-size` | Radius/half-extent, `0.1..8` blocks |
| `points` | Native emission positions per sampled shape, `1..24` |
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

`POINT` repeats the anchor; `LINE` spans the X axis; `CUBE` distributes samples
along its **twelve wireframe edges**; `RING` samples an XZ perimeter;
`CIRCLE` samples its filled disk;
`SPHERE` samples a surface; `WAVE` spans X with a vertical sinusoid. Geometry
uses world axes and has no rotation setting. Shape points are independently
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
| `interval-ticks` | `10` | `1..1200`; minimum time between viewer planning passes |
| `viewers-per-tick` | `8` | `1..128`; bounded round-robin audience scan |
| `attempts-per-viewer` | `8` | `1..64`; nearby anchors attempted per pass |
| `packets-per-viewer` | `12` | `1..128`; particle emission requests per viewer pass |
| `native-budget-micros` | `1500` | `1..10000`; cooperative Main-thread deadline per tick |
| `ping.max-current-ms` | `250` | `1..10000`; current-ping ceiling |
| `ping.max-average-ms` | `180` | `1..10000`; rolling-average ceiling |
| `ping.window-samples` | `10` | `1..120`; fixed-size rolling window |
| `ping.sample-interval-ticks` | `20` | `1..1200`; minimum sample spacing |

At 20 TPS, the default cadence allows at most 24 emission requests per second
per viewer before other caps. All bundled profiles use `count=1`; increasing
`count` increases client particle work without increasing the emission-request
count. This is not a TCP-packet count, throughput guarantee or FPS benchmark.
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

The treasure-hunt preset supplies ambience around viewers. Its random shapes
are not treasure markers and do not change discovery, rewards or gameplay.

## Build and publication

Run from the repository root with JDK 25:

```sh
./gradlew :arc-aurora:test :arc-aurora:shadowJar
```

The expected local runtime artifact is
`arc-aurora/build/libs/ArcAurora-0.1.0.jar`. The public Maven API artifact
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

The publisher's [Aurora listing](https://voxel.shop/p/9484) links the inspected
promotional GIFs: [ice](https://imgur.com/xqjEWXI),
[desert](https://imgur.com/KCWIpGz), [badlands](https://imgur.com/JriJyP3) and
[coast](https://imgur.com/6Z3y7QK). They establish the reference composition,
not ArcAurora rendering. The linked [showcase video](https://www.youtube.com/watch?v=neFruXTTRNc)
was located but its playback was not reviewed. The public Zenya wiki is
historical; it is not proof of the current commercial release's behaviour.

Source checks can establish configuration validity, selectors, particle data,
geometry and lifecycle rules. They do not establish native appearance or cost.
The bundled presets still need a real-client comparison of a forest night and
a coastal night, with turning/movement, clear/rain transitions, toggle, event
end and the user's particle settings. No native-client visual acceptance or
measured performance result is claimed by this README. Publication, artifact
delivery and runtime activation require separate operational evidence.
