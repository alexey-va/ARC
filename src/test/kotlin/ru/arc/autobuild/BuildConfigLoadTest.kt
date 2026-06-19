package ru.arc.autobuild

import org.bukkit.Material
import org.bukkit.Particle
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.KotestTestBase
import ru.arc.autobuild.BuildConfig

/**
 * Verifies that [BuildConfig] reads values from the bundled `modules/auto-build.yml`.
 * Uses [KotestTestBase] so [ARC.plugin] is available for [BuildConfig]'s config getter.
 */
class BuildConfigLoadTest : KotestTestBase({

    describe("BuildConfig from bundled auto-build.yml") {

        it("disable-building is false in bundled YAML") {
            BuildConfig.isDisabled shouldBe false
        }

        it("cleanup interval is 20 ticks") {
            BuildConfig.cleanupIntervalTicks shouldBe 20L
        }

        it("confirm time is 180 seconds") {
            BuildConfig.confirmTimeSeconds shouldBe 180
        }

        it("construction blocks per tick from bundled YAML") {
            BuildConfig.blocksPerTick shouldBe 3
        }

        it("construction play sounds is true") {
            BuildConfig.playSounds shouldBe true
        }

        it("construction show particles is false") {
            BuildConfig.showParticles shouldBe false
        }

        it("display border particle interval is 5") {
            BuildConfig.borderParticleInterval shouldBe 5L
        }

        it("display border particle is FLAME") {
            BuildConfig.borderParticle shouldBe Particle.FLAME
        }

        it("display center particle is NAUTILUS") {
            BuildConfig.centerParticle shouldBe Particle.NAUTILUS
        }

        it("display max blocks is 20000 from bundled YAML") {
            BuildConfig.maxDisplayBlocks shouldBe 20000
        }

        it("confirm-gui cancel material is RED_STAINED_GLASS_PANE") {
            BuildConfig.ConfirmGui.cancelMaterial shouldBe Material.RED_STAINED_GLASS_PANE
        }

        it("confirm-gui confirm material is PAPER") {
            BuildConfig.ConfirmGui.confirmMaterial shouldBe Material.PAPER
        }

        it("building-gui fast finish material is BLAZE_POWDER") {
            BuildConfig.BuildingGui.fastFinishMaterial shouldBe Material.BLAZE_POWDER
        }

        it("npc skins map is non-empty") {
            BuildConfig.npcSkins shouldNotBe emptyMap<String, String>()
        }
    }
})
