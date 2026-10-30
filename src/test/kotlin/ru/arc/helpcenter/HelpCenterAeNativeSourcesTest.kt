package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration

class HelpCenterAeNativeSourcesTest : StringSpec({
    "native dust range follows exact AE exclusive upper bound without consuming random state" {
        HelpCenterAeNativeSources.nativeDustRange(byteArrayOf(1, 15)) shouldBe (1..14)
        HelpCenterAeNativeSources.nativeDustRange(byteArrayOf(3, 3)) shouldBe (3..3)
    }

    "native mob chance gates every configured item without normalizing counts or weights" {
        val config = YamlConfiguration()
        config.set("mobs.ZOMBIE.chance", 25.0)
        config.set("mobs.ZOMBIE.items", listOf("whitescroll:2", "blackscroll:1"))
        HelpCenterAeNativeSources.nativeMobFacts("whitescroll", config).single().values shouldBe
            mapOf("mob" to "ZOMBIE", "chance" to "25", "amount" to "2")
        HelpCenterAeNativeSources.nativeMobFacts("blackscroll", config).single().values["chance"] shouldBe "25"

        config.set("mobs.ZOMBIE.chance", 2.5)
        HelpCenterAeNativeSources.nativeMobFacts("whitescroll", config).single().values["chance"] shouldBe "2,5"
        config.set("mobs.ZOMBIE.chance", 0.0)
        HelpCenterAeNativeSources.nativeMobFacts("whitescroll", config) shouldBe emptyList()
    }

    "tinkerer source follows the loaded book reward replacement flag" {
        val config = YamlConfiguration()
        config.set("general.give-item-instead-of-dust.enabled", false)
        HelpCenterAeNativeSources.tinkererFacts(config).single().values shouldBe mapOf("input" to "1", "output" to "1")
        config.set("general.give-item-instead-of-dust.enabled", true)
        HelpCenterAeNativeSources.tinkererFacts(config) shouldBe emptyList()
    }
})
