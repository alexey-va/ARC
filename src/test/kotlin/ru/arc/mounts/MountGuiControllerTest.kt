package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.util.customModelDataOrNull
import java.time.Duration

class MountGuiControllerTest : TestBase() {
    @Test
    fun `collection prioritizes unlocked mounts while preserving catalog order inside each group`() {
        val firstLocked = testMount().copy(id = "locked-first", displayName = "Первый закрытый")
        val firstOwned = testMount().copy(id = "owned-first", displayName = "Первый полученный")
        val secondLocked = testMount().copy(id = "locked-second", displayName = "Второй закрытый")
        val secondOwned = testMount().copy(id = "owned-second", displayName = "Второй полученный")
        val profiles = mapOf(
            firstLocked.id to MountProfile(0, false, false),
            firstOwned.id to MountProfile(1, false, false),
            secondLocked.id to MountProfile(0, false, false),
            secondOwned.id to MountProfile(2, false, false),
        )

        prioritizeUnlockedMounts(listOf(firstLocked, firstOwned, secondLocked, secondOwned)) {
            checkNotNull(profiles[it.id])
        }.map(MountDefinition::id).shouldContainExactly(
            "owned-first",
            "owned-second",
            "locked-first",
            "locked-second",
        )
    }

    @Test
    fun `collection renders the available mount first with calm separated lore`() {
        val locked = testMount().copy(id = "locked", displayName = "Закрытый")
        val owned = testMount().copy(id = "owned", displayName = "Полученный")
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), locked) } returns MountProfile(0, false, false)
            every { profile(any(), owned) } returns MountProfile(2, false, false)
        }
        val config = mockk<MountModuleConfig> {
            every { listTitle } returns "Коллекция маунтов"
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
        }
        val controller =
            MountGuiController(
                plugin = plugin,
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(locked, owned)) },
                ownership = ownership,
                wallet = mockk { every { balanceMinor(any()) } returns 1_000_000L },
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("LoreRider")

        controller.start()
        try {
            controller.openList(player)

            val first = checkNotNull(player.openInventory.topInventory.getItem(10))
            plainName(first) shouldBe "Полученный"
            val lore = checkNotNull(first.itemMeta.lore()).map(PlainTextComponentSerializer.plainText()::serialize)
            lore.none { "●" in it } shouldBe true
            lore.count(String::isEmpty).shouldBeGreaterThanOrEqual(3)
            lore.filter(String::isNotEmpty).first() shouldBe "✔ Получен"
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `category switch uses a distinct resource-pack icon for every mount type`() {
        val walking = testMount().copy(id = "walking", movement = MountMovement.WALKING)
        val flying = testMount().copy(id = "flying", movement = MountMovement.FLYING)
        val swimming = testMount().copy(id = "swimming", movement = MountMovement.SWIMMING)
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        val styles =
            mapOf(
                MountGuiItemRole.CATEGORY_ALL to MountGuiItemStyle(Material.COMPASS, 11023),
                MountGuiItemRole.CATEGORY_FLYING to MountGuiItemStyle(Material.FEATHER, 11024),
                MountGuiItemRole.CATEGORY_WALKING to MountGuiItemStyle(Material.SADDLE, 11025),
                MountGuiItemRole.CATEGORY_SWIMMING to MountGuiItemStyle(Material.HEART_OF_THE_SEA, 11026),
            )
        val ownership = mockk<MountOwnership> {
            every { profile(any(), any()) } returns MountProfile(1, false, false)
        }
        val config = mockk<MountModuleConfig> {
            every { listTitle } returns "Коллекция маунтов"
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } answers { styles[firstArg()] ?: MountGuiItemStyle() }
        }
        val controller =
            MountGuiController(
                plugin = plugin,
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(walking, flying, swimming)) },
                ownership = ownership,
                wallet = mockk { every { balanceMinor(any()) } returns 1_000_000L },
                purchases = mockk(relaxed = true),
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("IconRider")

        fun categoryIcon() = checkNotNull(player.openInventory.topInventory.getItem(49))

        controller.openList(player)
        categoryIcon().type shouldBe Material.COMPASS
        categoryIcon().customModelDataOrNull shouldBe 11023

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.FEATHER
        categoryIcon().customModelDataOrNull shouldBe 11024

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.SADDLE
        categoryIcon().customModelDataOrNull shouldBe 11025

        controller.onClick(clickEvent(player.openInventory, 49))
        categoryIcon().type shouldBe Material.HEART_OF_THE_SEA
        categoryIcon().customModelDataOrNull shouldBe 11026
    }

    @Test
    fun `progression submenu exposes selected tuning and routes a free speed change`() {
        val mount = testMount().copy(movement = MountMovement.WALKING)
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(110, 150, 200, 300, 400),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 200, 400),
            )
        val profile = MountProfile(level = 2, glowOwned = false, glowDisabled = false, selectedSpeedPercentage = 65, selectedStepHeightHundredths = 150)
        val ownership = mockk<MountOwnership> {
            every { profile(any(), mount) } returns profile
        }
        val config = mockk<MountModuleConfig> {
            every { detailTitle } returns "Маунт: <mount>"
            every { progressionTitle } returns "Развитие: <mount>"
            every { sessionDuration } returns Duration.ofHours(12)
            every { idleTimeout } returns Duration.ofMinutes(5)
            every { purchasesEnabled } returns true
            every { this@mockk.tuning } returns tuning
            every { guiStyle(any()) } returns MountGuiItemStyle()
            every { message(any(), any()) } answers { secondArg() }
        }
        val purchases = mockk<MountPurchaseCoordinator>(relaxed = true)
        every { purchases.setSpeedTuning(any(), mount, tuning, 90, any()) } answers {
            lastArg<(MountPurchaseResult) -> Unit>()(MountPurchaseResult.Success)
        }
        val controller =
            MountGuiController(
                plugin = plugin,
                configProvider = { config },
                catalogProvider = { MountCatalog(listOf(mount)) },
                ownership = ownership,
                wallet = mockk { every { balanceMinor(any()) } returns 1_000_000L },
                purchases = purchases,
                sessions = mockk(relaxed = true),
            )
        val player = server.addPlayer("TuningRider")

        controller.start()
        try {
            controller.openDetail(player, mount.id)
            controller.onClick(clickEvent(player.openInventory, 20))

            plainName(player.openInventory.topInventory.getItem(21)) shouldBe "Скорость: 65%"
            player.openInventory.topInventory.getItem(21)?.itemMeta?.enchantmentGlintOverride shouldBe true
            plainName(player.openInventory.topInventory.getItem(30)) shouldBe "Подъём: 1.50 блока"
            player.openInventory.topInventory.getItem(30)?.itemMeta?.enchantmentGlintOverride shouldBe true
            plainName(player.openInventory.topInventory.getItem(33)) shouldBe "Подъём: 4.00 блока"

            controller.onClick(clickEvent(player.openInventory, 33))
            verify(exactly = 0) { purchases.setStepHeightTuning(any(), any(), any(), any(), any()) }

            controller.onClick(clickEvent(player.openInventory, 23))

            verify(exactly = 1) { purchases.setSpeedTuning(any(), mount, tuning, 90, any()) }
        } finally {
            controller.shutdown()
        }
    }

    private fun clickEvent(view: org.bukkit.inventory.InventoryView, rawSlot: Int) =
        InventoryClickEvent(
            view,
            InventoryType.SlotType.CONTAINER,
            rawSlot,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL,
        )

    private fun plainName(stack: org.bukkit.inventory.ItemStack?): String =
        PlainTextComponentSerializer.plainText().serialize(checkNotNull(stack?.itemMeta?.displayName()))
}
