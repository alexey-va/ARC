package ru.arc.backwings

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.minimessage.MiniMessage
import ru.arc.config.Config
import java.nio.file.Files

class BackWingsConfigTest : FreeSpec({
    "text merge preserves operator values and obsolete pose keys without retaining a custom renderer" {
        val root = Files.createTempDirectory("arc-back-wings-config-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve(BackWingsConfig.RESOURCE), """
            text:
              equipped: '<green>Готово'
            models:
              dreadknight:
                scale: -1
            operator-note: retained
        """.trimIndent())
        val source = Config(root, BackWingsConfig.RESOURCE)
        source.mergeMissingFromBundled(BackWingsConfig.RESOURCE) shouldBe true
        val settings = BackWingsConfig(source).snapshot()
        settings.equippedText shouldBe MiniMessage.miniMessage().deserialize("<green>Готово")
        settings.unavailableText shouldBe BackWingsSettings().unavailableText
        source.string("operator-note", "") shouldBe "retained"
        source.mergeMissingFromBundled(BackWingsConfig.RESOURCE) shouldBe false
    }

    "only the three exact original item IDs map to bounded native cosmetics and permissions" {
        BackWing.entries.associate { it.itemId to it.cosmeticId } shouldBe mapOf(
            "dreadknight:dreadknight_wings" to "arc_dreadknight_wings",
            "galaxy_set:galaxy_wings" to "arc_galaxy_wings",
            "deceaseset:decease_wings" to "arc_decease_wings",
        )
        BackWing.entries.flatMap { it.permissions }.toSet() shouldBe setOf(
            "cosmeticscore.user.cosmetics.wear.arc_dreadknight_wings",
            "cosmeticscore.user.cosmetics.seeingui.arc_dreadknight_wings",
            "cosmeticscore.user.cosmetics.wear.arc_galaxy_wings",
            "cosmeticscore.user.cosmetics.seeingui.arc_galaxy_wings",
            "cosmeticscore.user.cosmetics.wear.arc_decease_wings",
            "cosmeticscore.user.cosmetics.seeingui.arc_decease_wings",
        )
        BackWing.fromItemId("another:wings") shouldBe null
        BackWing.fromItemId("DREADKNIGHT:dreadknight_wings") shouldBe null
        BackWing.fromItemId(null) shouldBe null
    }
})
