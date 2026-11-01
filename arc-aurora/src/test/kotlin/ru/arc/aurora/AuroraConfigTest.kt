package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.configuration.file.YamlConfiguration

class AuroraConfigTest : FreeSpec({
    "defaults form one complete detached settings snapshot" {
        val source = profileConfig()
        val settings = AuroraConfig.parse(source)
        settings.permission shouldBe "arcaurora.view"
        listOf(settings.intervalTicks, settings.viewersPerTick, settings.attemptsPerViewer,
            settings.packetsPerViewer, settings.nativeBudgetMicros) shouldBe listOf(10, 8, 8, 12, 1500)
        settings.skyPacketsPerViewer shouldBe 36
        settings.ping shouldBe AuroraPingSettings()
        val profile = settings.profiles.single()
        profile.id shouldBe "ambient"
        profile.particle shouldBe Particle.END_ROD
        profile.particleData shouldBe null
        profile.skyScene shouldBe false
        profile.skyOptions shouldBe AuroraSkyOptions()
        profile.skyPalette shouldBe emptyList()
        profile.skyModel shouldBe null
        listOf(profile.sceneDurationTicks, profile.motionPeriodTicks, profile.updateTicks) shouldBe listOf(240, 400, 4)
        profile.biomes shouldBe listOf("*")
        profile.matches("iris:unknown_biome", "world", 15000, false, false, 80.0, 12, false) shouldBe true
        profile.matches("minecraft:plains", "world", 0, false, false, 80.0, 12, true) shouldBe false
        source.set("profiles.ambient.biomes", listOf("minecraft:desert"))
        source.set("profiles.ambient.radius", 24)
        profile.biomes shouldBe listOf("*")
        profile.radius shouldBe 12.0
    }

    "sky packet cap is bounded independently from the ordinary packet cap" {
        listOf(1, 128).forEach { cap ->
            val settings = AuroraConfig.parse(yaml("sky-packets-per-viewer: $cap"))
            settings.skyPacketsPerViewer shouldBe cap
            settings.packetsPerViewer shouldBe 12
        }
        val ordinaryOverride = AuroraConfig.parse(yaml("packets-per-viewer: 7"))
        ordinaryOverride.packetsPerViewer shouldBe 7
        ordinaryOverride.skyPacketsPerViewer shouldBe 36
        listOf(0, 129, 1.5, "36").forEach { value ->
            val source = YamlConfiguration().apply { set("sky-packets-per-viewer", value) }
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(source) }
                .path shouldBe "sky-packets-per-viewer"
        }
    }

    "the aggregate celestial scene cap defaults to two and accepts only an integer in one through sixteen" {
        AuroraSkyFieldSettings().maxCelestialScenes shouldBe 2
        AuroraConfig.parse(YamlConfiguration()).skyField.maxCelestialScenes shouldBe 2
        AuroraConfig.parse(yaml("sky-field:\n  max-scenes: 4")).skyField.maxCelestialScenes shouldBe 2
        (1..16).forEach { cap ->
            val settings = AuroraConfig.parse(yaml("sky-field:\n  max-celestial-scenes: $cap"))
            settings.skyField.maxCelestialScenes shouldBe cap
            settings.skyField.maxScenes shouldBe 9
            settings.skyPacketsPerViewer shouldBe 36
        }
        listOf(0, 17, 1.5, "2").forEach { value ->
            val source = YamlConfiguration().apply { set("sky-field.max-celestial-scenes", value) }
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(source) }
                .path shouldBe "sky-field.max-celestial-scenes"
        }
    }

    "sky scene mode expands shape bounds while legacy profiles keep their existing limits" {
        val ordinary = AuroraConfig.parse(profileConfig("shape-size" to 8, "points" to 24)).profiles.single()
        ordinary.skyScene shouldBe false
        ordinary.shapeSize shouldBe 8.0
        ordinary.points shouldBe 24
        val sky = AuroraConfig.parse(profileConfig(
            "sky-scene" to true, "shape-size" to 32, "points" to 192,
            "scene-duration-ticks" to 1200, "motion-period-ticks" to 2400, "update-ticks" to 200,
        )).profiles.single()
        sky.skyScene shouldBe true
        sky.shapeSize shouldBe 32.0
        sky.points shouldBe 192
        listOf(sky.sceneDurationTicks, sky.motionPeriodTicks, sky.updateTicks) shouldBe listOf(1200, 2400, 200)
        sky.particle shouldBe Particle.END_ROD
        sky.count shouldBe 1

        listOf(false, true).forEach { skyScene ->
            val minimum = AuroraConfig.parse(profileConfig(
                "sky-scene" to skyScene, "shape-size" to 0.1, "points" to 1,
                "scene-duration-ticks" to 40, "motion-period-ticks" to 40, "update-ticks" to 1,
            )).profiles.single()
            minimum.shapeSize shouldBe 0.1
            minimum.points shouldBe 1
            listOf(minimum.sceneDurationTicks, minimum.motionPeriodTicks, minimum.updateTicks) shouldBe listOf(40, 40, 1)
        }
        listOf("shape-size" to 8.01, "points" to 25).forEach { (field, value) ->
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("sky-scene" to false, field to value)) }
                .path shouldBe "profiles.ambient.$field"
        }
    }

    "distant sky anchors and bounded tracers do not widen ordinary biome profile bounds" {
        val sky = AuroraConfig.parse(profileConfig("sky-scene" to true, "radius" to 96, "min-distance" to 72,
            "vertical-min" to 32, "vertical-max" to 64, "spawn-delay-ticks" to 400, "sky-tracers" to 4, "sky-refresh-ticks" to 24)).profiles.single()
        sky.radius shouldBe 96.0
        sky.minDistance shouldBe 72.0
        sky.verticalMax shouldBe 64.0
        sky.spawnDelayTicks shouldBe 400
        sky.skyTracers shouldBe 4
        sky.skyRefreshTicks shouldBe 24
        listOf("radius" to 32.01, "vertical-max" to 32.01).forEach { (field, value) ->
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig(field to value)) }
                .path shouldBe "profiles.ambient.$field"
        }
        listOf("radius" to 96.01, "vertical-max" to 64.01, "spawn-delay-ticks" to -1,
            "spawn-delay-ticks" to 401, "sky-tracers" to 0, "sky-tracers" to 5,
            "sky-refresh-ticks" to -1, "sky-refresh-ticks" to 201).forEach { (field, value) ->
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("sky-scene" to true, field to value)) }
                .path shouldBe "profiles.ambient.$field"
        }
    }

    "celestial shapes accept twelve tracers while legacy shapes retain their four tracer limit" {
        AuroraShape.entries.filter { it.isCelestial }.forEach { shape ->
            AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to shape.name, "sky-tracers" to 12))
                .profiles.single().skyTracers shouldBe 12
            shouldThrow<AuroraConfigException> {
                AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to shape.name, "sky-tracers" to 13))
            }.path shouldBe "profiles.ambient.sky-tracers"
        }
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "TRACK_FLYBY", "sky-tracers" to 5))
        }.path shouldBe "profiles.ambient.sky-tracers"
    }

    "invalid sky geometry and timing reject the complete candidate at their own field paths" {
        listOf(
            "shape-size" to 0.099, "shape-size" to 32.01, "shape-size" to Double.NaN,
            "shape-size" to Double.POSITIVE_INFINITY, "points" to 0, "points" to 193, "points" to 1.5,
            "scene-duration-ticks" to 39, "scene-duration-ticks" to 1201, "scene-duration-ticks" to 240.5,
            "motion-period-ticks" to 39, "motion-period-ticks" to 2401,
            "update-ticks" to 0, "update-ticks" to 201, "update-ticks" to "4",
        ).forEach { (field, value) ->
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("sky-scene" to true, field to value)) }
                .path shouldBe "profiles.ambient.$field"
        }
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("sky-scene" to "true")) }
            .path shouldBe "profiles.ambient.sky-scene"
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("update-ticks" to 0)) }
            .path shouldBe "profiles.ambient.update-ticks"
    }

    "global sky defaults resolve into profiles and local options override only their own fields" {
        val source = yaml("""
            sky-defaults:
              fan-degrees: 180
              angular-gap-degrees: 12
              anchor-candidates: 16
              terrain-clearance: 3
              scale-min: 0.6
              scale-max: 1.2
              roll-radians: 0.3
              wave-amplitude: 0.4
              wave-cycles: 2
              wave-slope: -0.1
              wave-depth: 0.2
              stroke-drift: -0.2
              stroke-lift: 0.1
              held-drift: -0.08
              held-lift: 0.04
              exit-ticks: 20
              fresh-head-repair: false
              fresh-head-max-age-ticks: 3
              meteor-split: 0.5
              meteor-spread: 0.6
              track-turns: 2
              track-tightening: 0.25
              track-branch-angle-degrees: 35
              track-bend-degrees: 95
              track-split: 0.4
              lens-spread: 0.7
              knot-radius: 0.4
              flyby-gap: 0.4
              flock-spread: 0.2
              kink-angle-degrees: 85
              ripple-spacing: 0.4
              eye-opening: 0.5
              slingshot-radius: 0.4
              skip-height: 0.7
            profiles:
              ambient:
                sky-scene: true
            event-presets:
              hunt:
                wave:
                  sky-scene: true
                  sky-options:
                    wave-amplitude: 0.3
                    fresh-head-repair: true
                    track-turns: 1.5
                    flyby-gap: 0.2
                    eye-opening: 0.25
                    slingshot-radius: 0.2
                    skip-height: 0.4
        """)
        val settings = AuroraConfig.parse(source)
        val defaults = AuroraSkyOptions(180.0, 12.0, 16, 3.0, 0.6, 1.2, 0.3, 0.4, 2.0, -0.1,
            0.2, -0.2, 0.1, -0.08, 0.04, 20, false, 3, meteorSplit = 0.5, meteorSpread = 0.6,
            trackTurns = 2.0, trackTightening = 0.25, trackBranchAngleDegrees = 35.0,
            trackBendDegrees = 95.0, trackSplit = 0.4, lensSpread = 0.7, knotRadius = 0.4,
            flybyGap = 0.4, flockSpread = 0.2, kinkAngleDegrees = 85.0, rippleSpacing = 0.4,
            eyeOpening = 0.5, slingshotRadius = 0.4, skipHeight = 0.7)
        settings.profiles.single().skyOptions shouldBe defaults
        settings.eventPresets.getValue("hunt").single().skyOptions shouldBe
            defaults.copy(waveAmplitude = 0.3, freshHeadRepair = true, trackTurns = 1.5, flybyGap = 0.2,
                eyeOpening = 0.25, slingshotRadius = 0.2, skipHeight = 0.4)
        source.set("sky-defaults.wave-amplitude", 0.7)
        settings.profiles.single().skyOptions shouldBe defaults // Detached snapshot until a successful reload.
        AuroraConfig.parse(source).profiles.single().skyOptions.waveAmplitude shouldBe 0.7
        source.set("sky-defaults.track-bend-degrees", 110.0)
        settings.profiles.single().skyOptions.trackBendDegrees shouldBe 95.0
        AuroraConfig.parse(source).profiles.single().skyOptions.trackBendDegrees shouldBe 110.0
    }

    "invalid sky options reject the candidate without changing the last complete snapshot" {
        val source = profileConfig("sky-scene" to true)
        val active = AuroraConfig.parse(source)
        listOf(
            "fan-degrees" to 360.01, "angular-gap-degrees" to -0.1, "anchor-candidates" to 33,
            "terrain-clearance" to 0.99, "scale-min" to 0.09, "scale-max" to 2.01,
            "roll-radians" to Double.NaN, "wave-amplitude" to Double.POSITIVE_INFINITY,
            "wave-cycles" to 0.0, "wave-slope" to -1.01, "wave-depth" to -0.1,
            "stroke-drift" to 1.01, "stroke-lift" to "0.05", "held-drift" to 0.251,
            "held-lift" to -0.251, "exit-ticks" to 1.5, "fresh-head-repair" to "true",
            "fresh-head-max-age-ticks" to 5,
            "meteor-split" to 0.24, "meteor-split" to 0.86,
            "meteor-spread" to Double.NaN, "meteor-spread" to 1.01,
            "track-turns" to 0.49, "track-turns" to 3.01,
            "track-tightening" to 0.049, "track-tightening" to 0.81,
            "track-branch-angle-degrees" to 9.9, "track-branch-angle-degrees" to 75.1,
            "track-bend-degrees" to 14.9, "track-bend-degrees" to 180.1,
            "track-split" to 0.09, "track-split" to Double.NaN,
            "lens-spread" to 0.14, "lens-spread" to 0.91,
            "knot-radius" to 0.14, "knot-radius" to 0.46,
            "flyby-gap" to 0.09, "flyby-gap" to 0.71,
            "flock-spread" to 0.09, "flock-spread" to 0.41,
            "kink-angle-degrees" to 34.9, "kink-angle-degrees" to 120.1,
            "ripple-spacing" to Double.NaN, "ripple-spacing" to 0.46,
            "eye-opening" to 0.14, "eye-opening" to 0.66, "eye-opening" to Double.NaN,
            "slingshot-radius" to 0.11, "slingshot-radius" to 0.46, "slingshot-radius" to Double.POSITIVE_INFINITY,
            "skip-height" to 0.19, "skip-height" to 0.81, "skip-height" to Double.NaN,
        ).forEach { (field, value) ->
            source.set("sky-defaults", null)
            source.set("sky-defaults.$field", value)
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(source) }.path shouldBe "sky-defaults.$field"
            active.profiles.single().skyOptions shouldBe AuroraSkyOptions()
        }
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(yaml("sky-defaults: []")) }.path shouldBe "sky-defaults"
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("sky-options" to false)) }
            .path shouldBe "profiles.ambient.sky-options"
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(profileConfig("sky-options.scale-min" to 1.1))
        }.path shouldBe "profiles.ambient.sky-options.scale-max"
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(profileConfig("sky-scene" to true, "shape-size" to 20, "sky-options.scale-max" to 2))
        }.path shouldBe "profiles.ambient.shape-size"
        val fixed = AuroraConfig.parse(profileConfig("sky-scene" to true, "shape-size" to 16,
            "sky-options.scale-min" to 2, "sky-options.scale-max" to 2, "sky-options.fan-degrees" to 0,
            "sky-options.roll-radians" to 0, "sky-options.exit-ticks" to 0)).profiles.single()
        fixed.shapeSize * fixed.skyOptions.scaleMax shouldBe 32.0
        fixed.skyOptions.exitTicks shouldBe 0
    }

    "only a sky flyby accepts exactly two no-data head particles and keeps their order" {
        val config = profileConfig("sky-scene" to true, "shape" to "TRACK_FLYBY",
            "sky-head-particles" to listOf("soul_fire_flame", "flame"))
        val accepted = AuroraConfig.parse(config).profiles.single()
        accepted.skyHeadParticles shouldBe listOf(Particle.SOUL_FIRE_FLAME, Particle.FLAME)
        for (bad in listOf(listOf("flame"), listOf("flame", "flame", "flame"),
            listOf("dust", "flame"), listOf("not_a_particle", "flame"))) {
            config.set("profiles.ambient.sky-head-particles", bad)
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(config) }.path shouldBe "profiles.ambient.sky-head-particles"
            accepted.skyHeadParticles shouldBe listOf(Particle.SOUL_FIRE_FLAME, Particle.FLAME)
        }
        config.set("profiles.ambient.sky-head-particles", listOf("flame", "flame"))
        AuroraConfig.parse(config).profiles.single().skyHeadParticles shouldBe listOf(Particle.FLAME, Particle.FLAME)
        config.set("profiles.ambient.sky-scene", false)
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(config) }.path shouldBe "profiles.ambient.sky-head-particles"
        config.set("profiles.ambient.sky-scene", true)
        config.set("profiles.ambient.shape", "TRACK_PAIR")
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(config) }.path shouldBe "profiles.ambient.sky-head-particles"
        config.set("profiles.ambient.sky-head-particles", emptyList<String>())
        AuroraConfig.parse(config).profiles.single().skyHeadParticles shouldBe emptyList()
        config.set("profiles.ambient.sky-head-particles", null)
        AuroraConfig.parse(config).profiles.single().skyHeadParticles shouldBe emptyList()
    }

    "sky lanterns default to a vanilla model while keeping their separate shape identity and particle DTO" {
        val profile = AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS")).profiles.single()
        profile.shape shouldBe AuroraShape.SKY_LANTERNS
        profile.shape.isCelestial shouldBe false
        profile.shape.isTrack shouldBe false
        profile.skyModel shouldBe AuroraSkyModel()
        profile.points shouldBe 1
        profile.skyTracers shouldBe 1
        profile.particle shouldBe Particle.END_ROD
        profile.particleData shouldBe null
        val emptyMapping = profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS")
        emptyMapping.createSection("profiles.ambient.sky-model")
        AuroraConfig.parse(emptyMapping).profiles.single().skyModel shouldBe AuroraSkyModel()
    }

    "sky model material custom model scale and pivot form a detached snapshot with bounded values" {
        val source = profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS",
            "sky-model.material" to "minecraft:paper", "sky-model.custom-model-data" to 12014,
            "sky-model.scale" to 1.5, "sky-model.pivot-x" to -1.0, "sky-model.pivot-y" to 2.0,
            "sky-model.pivot-z" to 0.25)
        val model = AuroraConfig.parse(source).profiles.single().skyModel
        model shouldBe AuroraSkyModel(Material.PAPER, 12014, 1.5, AuroraOffset(-1.0, 2.0, 0.25))
        source.set("profiles.ambient.sky-model.scale", 3.0)
        source.set("profiles.ambient.sky-model.custom-model-data", null)
        model!!.scale shouldBe 1.5
        model.customModelData shouldBe 12014
        val replacement = AuroraConfig.parse(source).profiles.single().skyModel!!
        replacement.scale shouldBe 3.0
        replacement.customModelData shouldBe null
        listOf(Triple(0, 0.1, -4.0), Triple(Int.MAX_VALUE, 4.0, 4.0)).forEach { (cmd, scale, pivot) ->
            val boundary = AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS",
                "sky-model.custom-model-data" to cmd, "sky-model.scale" to scale,
                "sky-model.pivot-x" to pivot, "sky-model.pivot-y" to pivot, "sky-model.pivot-z" to pivot))
                .profiles.single().skyModel
            boundary shouldBe AuroraSkyModel(Material.LANTERN, cmd, scale, AuroraOffset(pivot, pivot, pivot))
        }
    }

    "sky models reject unknown and air materials malformed sections and use outside the lantern shape" {
        listOf("AIR", "CAVE_AIR", "VOID_AIR", "unknown_material", "elitecreatures:chinese_lamp_lantern", 7).forEach { material ->
            shouldThrow<AuroraConfigException> {
                AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS", "sky-model.material" to material))
            }.path shouldBe "profiles.ambient.sky-model.material"
        }
        listOf(false, emptyList<String>()).forEach { value ->
            shouldThrow<AuroraConfigException> {
                AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS", "sky-model" to value))
            }.path shouldBe "profiles.ambient.sky-model"
        }
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("shape" to "SKY_LANTERNS")) }
            .path shouldBe "profiles.ambient.shape"
        listOf(AuroraShape.POINT, AuroraShape.TRACK_FLYBY, AuroraShape.MOON_FAN).forEach { shape ->
            val source = profileConfig("sky-scene" to true, "shape" to shape.name)
            source.createSection("profiles.ambient.sky-model")
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(source) }.path shouldBe "profiles.ambient.sky-model"
        }
        listOf(emptyList(), listOf("end_rod", "flame")).forEach { heads ->
            shouldThrow<AuroraConfigException> {
                AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS", "sky-head-particles" to heads))
            }.path shouldBe "profiles.ambient.sky-head-particles"
        }
        val palette = profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS")
        palette.createSection("profiles.ambient.sky-palette")
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(palette) }.path shouldBe "profiles.ambient.sky-palette"
    }

    "sky model numeric validation and the one through six lantern count reject invalid candidates at their own paths" {
        val invalid = listOf(
            "custom-model-data" to -1, "custom-model-data" to 1.5, "custom-model-data" to "12014",
            "custom-model-data" to Int.MAX_VALUE.toLong() + 1,
            "scale" to 0.09, "scale" to 4.01, "scale" to Double.NaN, "scale" to Double.POSITIVE_INFINITY,
            "scale" to "1.0",
        ) + listOf("pivot-x", "pivot-y", "pivot-z").flatMap { field ->
            listOf(-4.01, 4.01, Double.NaN, Double.NEGATIVE_INFINITY, "0").map { field to it }
        }
        invalid.forEach { (field, value) ->
            shouldThrow<AuroraConfigException> {
                AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS", "sky-model.$field" to value))
            }.path shouldBe "profiles.ambient.sky-model.$field"
        }
        listOf("points", "sky-points", "sky-tracers").forEach { field ->
            listOf(1, 6).forEach { value ->
                val profile = AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS", field to value))
                    .profiles.single()
                (if (field == "sky-tracers") profile.skyTracers else profile.points) shouldBe value
            }
            listOf(0, 7, 1.5, "6").forEach { value ->
                shouldThrow<AuroraConfigException> {
                    AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "SKY_LANTERNS", field to value))
                }.path shouldBe "profiles.ambient.$field"
            }
        }
    }

    "the six celestial shapes require sky scene mode and keep separate identities from tracks" {
        val celestial = listOf(AuroraShape.JELLYFISH, AuroraShape.MANTA_RAY, AuroraShape.LIGHT_KNOT,
            AuroraShape.REVERSE_LIGHTNING, AuroraShape.SKY_CORAL, AuroraShape.MOON_FAN)
        AuroraShape.entries.filter { it.isCelestial } shouldBe celestial
        celestial.forEach { shape ->
            shape.isTrack shouldBe false
            AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to shape.name))
                .profiles.single().shape shouldBe shape
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("shape" to shape.name)) }
                .path shouldBe "profiles.ambient.shape"
        }
    }

    "celestial palettes retain ordered typed particle payloads in an immutable detached snapshot" {
        val source = profileConfig("sky-scene" to true, "shape" to "JELLYFISH",
            "sky-palette.halo.particle" to "entity_effect", "sky-palette.halo.particle-color" to "#12aBcD",
            "sky-palette.flash.particle" to "flash", "sky-palette.flash.particle-color" to "#445566",
            "sky-palette.rim.particle" to "dust_color_transition", "sky-palette.rim.dust-color" to "#778899",
            "sky-palette.rim.dust-to-color" to "#aabbcc", "sky-palette.rim.dust-size" to 0.7,
            "sky-palette.spark.particle" to "glow")
        val profile = AuroraConfig.parse(source).profiles.single()
        profile.skyPalette.map { it.particle } shouldBe
            listOf(Particle.ENTITY_EFFECT, Particle.FLASH, Particle.DUST_COLOR_TRANSITION, Particle.GLOW)
        profile.skyPalette[0].data shouldBe Color.fromRGB(0x12abcd)
        profile.skyPalette[1].data shouldBe Color.fromRGB(0x445566)
        val transition = profile.skyPalette[2].data as Particle.DustTransition
        transition.color shouldBe Color.fromRGB(0x778899)
        transition.toColor shouldBe Color.fromRGB(0xaabbcc)
        transition.size shouldBe 0.7F
        profile.skyPalette[3].data shouldBe null
        source.set("profiles.ambient.sky-palette.halo.particle-color", "#ffffff")
        profile.skyPalette[0].data shouldBe Color.fromRGB(0x12abcd)
        AuroraConfig.parse(source).profiles.single().skyPalette[0].data shouldBe Color.WHITE
        shouldThrow<UnsupportedOperationException> { (profile.skyPalette as MutableList<AuroraParticleStyle>).clear() }

        val dust = AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "MOON_FAN",
            "sky-palette.rim.particle" to "dust", "sky-palette.rim.dust-color" to "#112233"))
            .profiles.single().skyPalette.single()
        dust.particle shouldBe Particle.DUST
        (dust.data as Particle.DustOptions).color shouldBe Color.fromRGB(0x112233)
    }

    "sky palettes reject unsupported payloads and malformed slot fields at their precise paths" {
        listOf(
            Triple("particle", "unknown_particle", "particle"),
            Triple("particle", "block", "particle"),
            Triple("particle", "sculk_charge", "particle"),
            Triple("particle", "trail", "particle"),
            Triple("particle", "entity_effect", "particle-color"),
            Triple("particle", "flash", "particle-color"),
            Triple("particle", "dust", "dust-color"),
            Triple("particle", "dust_color_transition", "dust-color"),
            Triple("particle-color", "#xyzxyz", "particle-color"),
            Triple("dust-to-color", "#123", "dust-to-color"),
            Triple("dust-size", 2.01, "dust-size"),
        ).forEach { (field, value, failedField) ->
            val source = profileConfig("sky-scene" to true, "shape" to "SKY_CORAL",
                "sky-palette.rim.particle" to "end_rod", "sky-palette.rim.$field" to value)
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(source) }
                .path shouldBe "profiles.ambient.sky-palette.rim.$failedField"
        }
        val malformed = profileConfig("sky-scene" to true, "shape" to "SKY_CORAL", "sky-palette.rim" to false)
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(malformed) }
            .path shouldBe "profiles.ambient.sky-palette.rim"
    }

    "sky palettes are bounded named mappings reserved for celestial sky scenes" {
        listOf(0, 5).forEach { count ->
            val source = profileConfig("sky-scene" to true, "shape" to "LIGHT_KNOT")
            source.createSection("profiles.ambient.sky-palette")
            repeat(count) { source.set("profiles.ambient.sky-palette.slot_$it.particle", "end_rod") }
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(source) }
                .path shouldBe "profiles.ambient.sky-palette"
        }
        listOf(false, true).forEach { skyScene ->
            shouldThrow<AuroraConfigException> {
                AuroraConfig.parse(profileConfig("sky-scene" to skyScene, "shape" to "TRACK_FLYBY",
                    "sky-palette.rim.particle" to "end_rod"))
            }.path shouldBe "profiles.ambient.sky-palette"
        }
        listOf(emptyList(), listOf("flame", "soul_fire_flame")).forEach { heads ->
            shouldThrow<AuroraConfigException> {
                AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "MANTA_RAY",
                    "sky-palette.rim.particle" to "end_rod", "sky-head-particles" to heads))
            }.path shouldBe "profiles.ambient.sky-palette"
        }
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(profileConfig("sky-scene" to true, "shape" to "MANTA_RAY",
                "sky-palette" to emptyList<String>()))
        }.path shouldBe "profiles.ambient.sky-palette"
    }

    "custom Iris and unknown biome keys use full namespaced globs without a registry" {
        val profile = AuroraConfig.parse(profileConfig(
            "biomes" to listOf("plains", "iris:deep/*_forest", "unknown_namespace:*"),
            "worlds" to listOf("survival.*"),
        )).profiles.single()
        profile.biomes shouldBe listOf("minecraft:plains", "iris:deep/*_forest", "unknown_namespace:*")
        fun matches(key: String, world: String = "survival.nether") =
            profile.matches(key, world, 0, false, false, 80.0, 15, false)
        listOf("plains", "minecraft:plains", "iris:deep/blue_forest", "unknown_namespace:new_biome")
            .forEach { matches(it) shouldBe true }
        listOf("iris:blue_forest", "iris:deep/blue_forest_hills", "prefixminecraft:plains", "minecraft:plains_suffix")
            .forEach { matches(it) shouldBe false }
        matches("plains", "survivalXnether") shouldBe false
        matches("plains", "prefixsurvival.nether") shouldBe false
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(profileConfig("biomes" to listOf("iris:forest?")))
        }.path shouldBe "profiles.ambient.biomes"
    }

    "aliases are normalized exact single-hop mappings and do not constrain unknown keys" {
        val settings = AuroraConfig.parse(yaml("""
            biome-aliases:
              plains: iris:lowland
              iris:lowland: iris:wetland
              iris.pack:deep.forest: iris:deep/forest
            profiles:
              ambient:
                biomes: [iris:lowland]
        """))
        settings.biomeAliases shouldBe mapOf("minecraft:plains" to "iris:lowland", "iris:lowland" to "iris:wetland",
            "iris.pack:deep.forest" to "iris:deep/forest")
        settings.canonicalBiomeKey("plains") shouldBe "iris:lowland"
        settings.canonicalBiomeKey("iris:unregistered") shouldBe "iris:unregistered"
        settings.canonicalBiomeKey("iris.pack:deep.forest") shouldBe "iris:deep/forest"
        settings.profiles.single().matches(settings.canonicalBiomeKey("plains"), "world", 0,
            false, false, 80.0, 15, false) shouldBe true
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(yaml("biome-aliases:\n  'iris:*': minecraft:plains"))
        }.path shouldBe "biome-aliases.iris:*"
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(yaml("biome-aliases:\n  plains: iris:first\n  minecraft:plains: iris:second"))
        }.path shouldBe "biome-aliases.minecraft:plains"
    }

    "time wraps midnight with inclusive endpoints and absolute time normalization" {
        val profile = AuroraConfig.parse(profileConfig("time-start" to 23000, "time-end" to 1000)).profiles.single()
        fun matches(time: Long) = profile.matches("plains", "world", time, false, false, 80.0, 15, false)
        listOf(23000L, 23999L, 0L, 1000L, 47000L, -1L).forEach { matches(it) shouldBe true }
        listOf(22999L, 1001L, 12000L).forEach { matches(it) shouldBe false }
        val instant = profile.copy(timeStart = 4000, timeEnd = 4000)
        instant.matches("plains", "world", 4000L, false, false, 80.0, 15, false) shouldBe true
        instant.matches("plains", "world", 4001L, false, false, 80.0, 15, false) shouldBe false
    }

    "weather and environment predicates preserve their individual boundaries" {
        val profile = AuroraConfig.parse(profileConfig(
            "weather" to "rain", "min-y" to 60, "max-y" to 90,
            "min-sky-light" to 10, "max-sky-light" to 14, "underwater" to true,
        )).profiles.single()
        fun matches(storm: Boolean = true, thunder: Boolean = false, y: Double = 60.0,
            light: Int = 10, water: Boolean = false) =
            profile.matches("plains", "world", 0, storm, thunder, y, light, water)
        matches(water = true) shouldBe true
        matches(y = 90.0, light = 14) shouldBe true
        matches(storm = false) shouldBe false
        matches(thunder = true) shouldBe false
        matches(y = 59.999) shouldBe false
        matches(y = 90.001) shouldBe false
        matches(y = Double.NaN) shouldBe false
        matches(light = 9) shouldBe false
        matches(light = 15) shouldBe false
        profile.copy(weather = AuroraWeather.THUNDER)
            .matches("plains", "world", 0, true, true, 60.0, 10, false) shouldBe true
        profile.copy(weather = AuroraWeather.CLEAR)
            .matches("plains", "world", 0, false, true, 60.0, 10, false) shouldBe true
    }

    "invalid numeric fields fail the entire candidate at the precise path" {
        listOf(
            "radius" to Double.NaN, "radius" to Double.POSITIVE_INFINITY, "radius" to 1.99,
            "min-distance" to 13.0, "points" to 1.5, "points" to 25, "count" to -1,
            "time-start" to 24000, "offset-x" to -0.1, "speed" to 1.01,
            "shape-size" to "large", "max-y" to -2049, "max-sky-light" to 16,
        ).forEach { (field, value) ->
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig(field to value)) }
                .path shouldBe "profiles.ambient.$field"
        }
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(profileConfig("min-y" to 100, "max-y" to 99))
        }.path shouldBe "profiles.ambient.max-y"
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(profileConfig("vertical-min" to 5, "vertical-max" to 4))
        }.path shouldBe "profiles.ambient.vertical-max"
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(yaml("viewers-per-tick: '8'"))
        }.path shouldBe "viewers-per-tick"
        shouldThrow<AuroraConfigException> {
            AuroraConfig.parse(yaml("permission: ''"))
        }.path shouldBe "permission"
    }

    "malformed sections and selectors are rejected rather than silently defaulted" {
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(yaml("profiles: []")) }
            .path shouldBe "profiles"
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(yaml("profiles:\n  ambient: false")) }
            .path shouldBe "profiles.ambient"
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("biomes" to listOf("plains", 7))) }
            .path shouldBe "profiles.ambient.biomes[1]"
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("worlds" to emptyList<String>())) }
            .path shouldBe "profiles.ambient.worlds"
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("underwater" to "false")) }
            .path shouldBe "profiles.ambient.underwater"
    }

    "dust payloads are ready for the exact Paper particle type and unsupported payloads fail parsing" {
        val dust = AuroraConfig.parse(profileConfig(
            "particle" to "minecraft:dust", "dust-color" to "#12aBcD", "dust-size" to 0.8,
        )).profiles.single()
        dust.particle shouldBe Particle.DUST
        val options = dust.particleData as Particle.DustOptions
        options.color shouldBe Color.fromRGB(0x12abcd)
        options.size shouldBe 0.8F
        val transition = AuroraConfig.parse(profileConfig(
            "particle" to "dust_color_transition", "dust-color" to "#112233", "dust-to-color" to "#445566",
        )).profiles.single().particleData as Particle.DustTransition
        transition.color shouldBe Color.fromRGB(0x112233)
        transition.toColor shouldBe Color.fromRGB(0x445566)
        listOf("minecraft:block", "minecraft:sculk_charge", "minecraft:trail", "minecraft:unknown_particle").forEach { particle ->
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("particle" to particle)) }
                .path shouldBe "profiles.ambient.particle"
        }
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("particle" to "dust")) }
            .path shouldBe "profiles.ambient.dust-color"
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("dust-color" to "#xyzxyz")) }
            .path shouldBe "profiles.ambient.dust-color"
    }

    "color particles use the same required RGB payload in ordinary profiles" {
        listOf(Particle.ENTITY_EFFECT, Particle.FLASH).forEach { particle ->
            val profile = AuroraConfig.parse(profileConfig("particle" to particle.key.toString(),
                "particle-color" to "#123456")).profiles.single()
            profile.particle shouldBe particle
            profile.particleData shouldBe Color.fromRGB(0x123456)
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(profileConfig("particle" to particle.key.toString())) }
                .path shouldBe "profiles.ambient.particle-color"
        }
    }

    "profile count is bounded across ordinary profiles and all event presets" {
        val source = YamlConfiguration()
        repeat(127) { source.createSection("profiles.biome_$it") }
        source.set("profiles.biome_0.sky-scene", true)
        source.createSection("event-presets.festival.spark")
        source.set("event-presets.festival.spark.sky-scene", true)
        val accepted = AuroraConfig.parse(source)
        accepted.profiles.size shouldBe 127
        accepted.eventPresets.getValue("festival").single().id shouldBe "spark"
        source.createSection("event-presets.festival.extra")
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(source) }
            .path shouldBe "event-presets.festival"
        accepted.eventPresets.getValue("festival").size shouldBe 1
    }

    "ping settings parse one validated nested policy" {
        AuroraConfig.parse(yaml("""
            ping:
              max-current-ms: 300
              max-average-ms: 200
              window-samples: 12
              sample-interval-ticks: 30
        """)).ping shouldBe AuroraPingSettings(300, 200, 12, 30)
        listOf("max-current-ms" to 0, "max-average-ms" to 10001,
            "window-samples" to 121, "sample-interval-ticks" to 1201).forEach { (field, value) ->
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(yaml("ping:\n  $field: $value")) }
                .path shouldBe "ping.$field"
        }
    }
})

private fun profileConfig(vararg values: Pair<String, Any>): YamlConfiguration = YamlConfiguration().apply {
    createSection("profiles.ambient")
    values.forEach { (field, value) -> set("profiles.ambient.$field", value) }
}

private fun yaml(text: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(text.trimIndent()) }
