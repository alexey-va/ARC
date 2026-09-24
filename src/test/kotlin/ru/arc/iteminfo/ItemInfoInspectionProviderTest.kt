package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

class ItemInfoInspectionProviderTest : StringSpec({
    "excluded item-info IDs suppress only their exact target" {
        val excludedId = "elitecreatures:casino_decoration_v1_table_blackjack"
        val settings = ItemInfoSettings(
            enabled = true,
            targetDistance = 5.0,
            nameOnlyTemplate = "<name>",
            hologramTemplate = "<name>",
            bossbarTemplate = "<name>",
            excludedItemIds = setOf(excludedId),
        )
        val player = mockk<Player>()
        val preferences: (Player) -> ItemInfoPreferences = { ItemInfoPreferences() }

        val excludedProvider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = { ItemInfoTarget(Component.text("Blackjack"), excludedId) },
            preferences = preferences,
        )
        excludedProvider.resolve(player) shouldBe null

        val ordinaryProvider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = {
                ItemInfoTarget(Component.text("Стул"), "elitecreatures:casino_decoration_v1_chair_brown_extra")
            },
            preferences = preferences,
        )
        (ordinaryProvider.resolve(player) == null) shouldBe false
    }
})
