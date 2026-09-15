package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager

class ItemInfoConfigTest : StringSpec({
    "empty config uses the supported player-facing defaults" {
        val settings = ItemInfoConfig(ConfigManager.empty()).snapshot()

        settings.enabled shouldBe true
        settings.targetDistance shouldBe 5.0
        settings.nameOnlyTemplate shouldBe "<white><name>"
        settings.hologramTemplate shouldBe "<white><name><newline><gray><id>"
        settings.bossbarTemplate shouldBe "<white><name> <dark_gray>· <gray><id>"
    }
})
