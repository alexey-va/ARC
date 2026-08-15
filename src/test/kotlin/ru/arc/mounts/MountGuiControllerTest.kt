package ru.arc.mounts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.time.Duration

class MountGuiControllerTest : TestBase() {
    @Test
    fun `progression submenu exposes selected tuning and routes a free speed change`() {
        val mount = testMount().copy(movement = MountMovement.WALKING)
        val tuning =
            MountTuningDefinition(
                speedPercentages = listOf(50, 65, 80, 90, 100),
                walkingStepHeightsHundredths = listOf(60, 80, 110, 125, 140),
                walkingMaxStepHeightByLevelHundredths = listOf(110, 125, 140),
            )
        val profile = MountProfile(level = 2, glowOwned = false, glowDisabled = false, selectedSpeedPercentage = 65, selectedStepHeightHundredths = 80)
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
            plainName(player.openInventory.topInventory.getItem(30)) shouldBe "Подъём: 0.80 блока"
            player.openInventory.topInventory.getItem(30)?.itemMeta?.enchantmentGlintOverride shouldBe true
            plainName(player.openInventory.topInventory.getItem(33)) shouldBe "Подъём: 1.40 блока"

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
