package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class PortalAnimationConfigTest : FreeSpec({
    afterTest { ConfigManager.clear() }

    "merges new motion defaults, preserves overrides, and reloads the next portal's settings" {
        val root = Files.createTempDirectory("arc-portal-config")
        try {
            Files.createDirectories(root.resolve("modules"))
            val file = root.resolve("modules/misc.yml")
            Files.writeString(file, """
                portal:
                  origin-gate:
                    enabled: true
                    items:
                      origin: origin_gate_portals:origin_portal
                      astral: origin_gate_portals:astral_portal
                      void: origin_gate_portals:void_portal
                    opening-duration-ticks: 66
                    width: 5.5
                operator-key: preserved
            """.trimIndent())
            val config = ConfigManager.of(root, "modules/misc.yml")
            config.mergeMissingFromBundled("modules/misc.yml") shouldBe true
            val first = PortalOriginGateSettings.load(config).shouldNotBeNull()
            first.openingDurationTicks shouldBe 66
            first.width shouldBe 5.5f
            first.entryTick shouldBe 12
            first.animation.chargeProgress shouldBe 0.25f
            first.animation.snapExponent shouldBe 6f
            first.suctionMotion.cycleTicks shouldBe 24
            config.string("operator-key") shouldBe "preserved"
            val merged = Files.readString(file)
            config.mergeMissingFromBundled("modules/misc.yml") shouldBe false
            Files.readString(file) shouldBe merged

            Files.writeString(file, merged
                .replace("entry-delay-ticks: 12", "entry-delay-ticks: 4")
                .replace("opening-duration-ticks: 66", "opening-duration-ticks: 24")
                .replace("snap-exponent: 6.0", "snap-exponent: 9.0")
                .replace("cycle-ticks: 24", "cycle-ticks: 10")
                .replace("entry-tick: 12", "entry-tick: 5"))
            ConfigManager.reloadAll()
            val second = PortalOriginGateSettings.load(config).shouldNotBeNull()
            second.openingDurationTicks shouldBe 24
            second.entryTick shouldBe 4
            second.animation.snapExponent shouldBe 9f
            second.suctionMotion.cycleTicks shouldBe 10
            PortalTimingSettings.load(config).entryTick shouldBe 5
            first.entryTick shouldBe 12
            first.suctionMotion.cycleTicks shouldBe 24
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "uses safe motion fallbacks for invalid operator values" {
        val root = Files.createTempDirectory("arc-portal-invalid")
        try {
            val file = root.resolve("portal.yml")
            Files.writeString(file, """
                portal:
                  animation:
                    lifetime-ticks: -1
                    legacy:
                      border-duration-ticks: 0
                  origin-gate:
                    minimum-scale: .nan
                    dramatic:
                      charge-progress: 1.0
                      snap-exponent: -2.0
                    idle:
                      amplitude: 0.0
                    suction:
                      cycle-ticks: 0
                      rotation-speed: .inf
                      interval-ticks: 0
            """.trimIndent())
            val config = ConfigManager.of(root, "portal.yml")
            val animation = PortalOriginGateAnimation.load(config, "portal.origin-gate")
            animation.minimumScale shouldBe 0.02f
            animation.chargeProgress shouldBe 0.25f
            animation.snapExponent shouldBe 6f
            animation.idleAmplitude shouldBe 0f
            val suction = PortalSuctionMotion.load(config, "portal.origin-gate.suction")
            suction.cycleTicks shouldBe 24
            suction.rotationSpeed shouldBe 0.18
            suction.intervalTicks shouldBe 1
            val timing = PortalTimingSettings.load(config)
            timing.lifetimeTicks shouldBe 400
            timing.borderDurationTicks shouldBe 8
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "new motion defaults reveal the portal before early entry and retain bounded particles" {
        val animation = PortalOriginGateAnimation()
        val readyScale = originGateOpeningScale(12, 24, OriginGateOpeningCurve.DRAMATIC, animation)
        (readyScale > 0.9f && readyScale < 1f).shouldBeTrue()
        val quiet = animation.copy(idleAmplitude = 0f)
        originGateOpeningScale(30, 24, OriginGateOpeningCurve.DRAMATIC, quiet) shouldBe 1f
        val tuned = PortalSuctionMotion(cycleTicks = 10, trailSpacing = 0.1, rotationSpeed = -0.4)
        val offsets = originGateParticleOffsets(7, 10, 3, 6.0, 7.0, 2.25, tuned)
        offsets.size shouldBe 30
        offsets.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }.shouldBeTrue()
        (offsets != originGateParticleOffsets(7, 10, 3, 6.0, 7.0, 2.25)).shouldBeTrue()
    }
})
