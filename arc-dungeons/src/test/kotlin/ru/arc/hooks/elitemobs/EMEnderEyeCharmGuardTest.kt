package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config

class EMEnderEyeCharmGuardTest : FreeSpec({
    beforeSpec { mockkStatic(EliteItemManager::class) }
    afterSpec { unmockkStatic(EliteItemManager::class) }

    for (hand in listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
        for (action in listOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
            "denies vanilla eye use for an EliteMobs charm in $hand on $action" {
                val item = eyeItem()
                every { EliteItemManager.isEliteMobsItem(item) } returns true
                val event = eyeInteraction(item, action, hand)
                val blockUse = event.useInteractedBlock()

                EMListener(config = mockk<Config>()).guardEnderEyeCharm(event)

                event.useItemInHand() shouldBe Event.Result.DENY
                event.useInteractedBlock() shouldBe blockUse
                verify(exactly = 0) { item.setItemMeta(any()) }
                verify(exactly = 0) { item.amount = any() }
            }
        }
    }

    "leaves ordinary ender eyes usable" {
        val item = eyeItem()
        every { EliteItemManager.isEliteMobsItem(item) } returns false
        val event = eyeInteraction(item)
        val itemUse = event.useItemInHand()

        EMListener(config = mockk<Config>()).guardEnderEyeCharm(event)

        event.useItemInHand() shouldBe itemUse
    }

    "ignores empty hands, other materials and left clicks" {
        val listener = EMListener(config = mockk<Config>())
        for ((item, action) in listOf(
            null to Action.RIGHT_CLICK_AIR,
            eyeItem(Material.ENDER_PEARL) to Action.RIGHT_CLICK_AIR,
            eyeItem() to Action.LEFT_CLICK_AIR,
            eyeItem() to Action.LEFT_CLICK_BLOCK,
        )) {
            val event = eyeInteraction(item, action)
            val itemUse = event.useItemInHand()
            val blockUse = event.useInteractedBlock()

            listener.guardEnderEyeCharm(event)

            event.useItemInHand() shouldBe itemUse
            event.useInteractedBlock() shouldBe blockUse
        }
    }

    "preserves existing block denials and handles pre-cancelled air interactions" {
        val item = eyeItem()
        every { EliteItemManager.isEliteMobsItem(item) } returns true
        val event = eyeInteraction(item)
        event.setUseInteractedBlock(Event.Result.DENY)
        val handler = EMListener::class.java.getMethod("guardEnderEyeCharm", PlayerInteractEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        handler.ignoreCancelled shouldBe false

        EMListener(config = mockk<Config>()).guardEnderEyeCharm(event)

        event.useItemInHand() shouldBe Event.Result.DENY
        event.useInteractedBlock() shouldBe Event.Result.DENY
    }
})

private fun eyeItem(material: Material = Material.ENDER_EYE): ItemStack = mockk(relaxed = true) {
    every { type } returns material
}

private fun eyeInteraction(
    item: ItemStack?,
    action: Action = Action.RIGHT_CLICK_AIR,
    hand: EquipmentSlot = EquipmentSlot.HAND,
) = PlayerInteractEvent(
    mockk<Player>(), action, item,
    if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) mockk<Block>() else null,
    BlockFace.UP, hand,
)
