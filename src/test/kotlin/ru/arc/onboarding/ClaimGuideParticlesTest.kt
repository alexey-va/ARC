package ru.arc.onboarding

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration

class ClaimGuideParticlesTest : StringSpec({
    "matches native border palette aliases but excludes selection and other particle types" {
        val config = YamlConfiguration()
        config.set("visualization.type.wilderness.particle", "REDSTONE")
        config.set("visualization.type.wilderness.color", "#00bd0a")
        config.set("visualization.type.own.particle_3", "DUST")
        config.set("visualization.type.own.color_3", "#1eff00")
        config.set("visualization.type.trusted.particle_4", "FLAME")
        config.set("visualization.type.trusted.color_4", "#f2f200")
        config.set("visualization.type.selection.particle_8", "DUST")
        config.set("visualization.type.selection.color_8", "#ff9d00")
        claimGuideParticleColors(config) shouldBe setOf(0x00bd0a, 0x1eff00)
    }
})
