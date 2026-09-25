package ru.arc.investigation

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.block.BlockFace
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import ru.arc.KotestTestBase
import java.util.UUID

class InvestigationCaseFileInteractionTest : KotestTestBase({
    describe("investigation case file right click routing") {
        it("opens a bound file when its click ray lands on an entity") {
            val player = server.addPlayer("CaseFileEntityClick")
            val clickedEntity = server.addPlayer("PedestalHitbox")
            val transactionId = UUID.randomUUID().toString()
            val file = caseFile(plugin, transactionId, player.uniqueId)
            player.inventory.setItemInMainHand(file)

            mockkObject(InvestigationModule)
            justRun { InvestigationModule.openCaseFile(player, transactionId) }
            try {
                val event = PlayerInteractEntityEvent(player, clickedEntity, EquipmentSlot.HAND)

                InvestigationCaseFile.onUseEntity(event)

                event.isCancelled shouldBe true
                verify(exactly = 1) { InvestigationModule.openCaseFile(player, transactionId) }
            } finally {
                unmockkObject(InvestigationModule)
            }
        }

        it("routes precise and paired generic entity interactions but opens once") {
            val player = server.addPlayer("CaseFilePreciseEntityClick")
            val clickedEntity = server.addPlayer("PrecisePedestalHitbox")
            val transactionId = UUID.randomUUID().toString()
            val file = caseFile(plugin, transactionId, player.uniqueId)
            player.inventory.setItemInMainHand(file)

            mockkObject(InvestigationModule)
            justRun { InvestigationModule.openCaseFile(player, transactionId) }
            try {
                val event = PlayerInteractAtEntityEvent(
                    player, clickedEntity, Vector(0.2, 0.5, 0.1), EquipmentSlot.HAND,
                )

                event.hand shouldBe EquipmentSlot.HAND
                event.handlers.registeredListeners.any { it.listener === InvestigationCaseFile } shouldBe true
                server.pluginManager.callEvent(event)
                val genericEvent = PlayerInteractEntityEvent(player, clickedEntity, EquipmentSlot.HAND)
                server.pluginManager.callEvent(genericEvent)

                event.isCancelled shouldBe true
                genericEvent.isCancelled shouldBe true
                verify(exactly = 1) { InvestigationModule.openCaseFile(player, transactionId) }
            } finally {
                unmockkObject(InvestigationModule)
            }
        }

        it("keeps air and block right clicks routed through the same case file flow") {
            val player = server.addPlayer("CaseFileBlockClick")
            val transactionId = UUID.randomUUID().toString()
            val file = caseFile(plugin, transactionId, player.uniqueId)
            player.inventory.setItemInMainHand(file)

            mockkObject(InvestigationModule)
            justRun { InvestigationModule.openCaseFile(player, transactionId) }
            try {
                val air = PlayerInteractEvent(
                    player, Action.RIGHT_CLICK_AIR, file, null, BlockFace.SELF, EquipmentSlot.HAND,
                )
                val block = PlayerInteractEvent(
                    player, Action.RIGHT_CLICK_BLOCK, file, null, BlockFace.SELF, EquipmentSlot.HAND,
                )

                InvestigationCaseFile.onUse(air)
                InvestigationCaseFile.onUse(block)

                air.isCancelled shouldBe true
                block.isCancelled shouldBe true
                verify(exactly = 2) { InvestigationModule.openCaseFile(player, transactionId) }
            } finally {
                unmockkObject(InvestigationModule)
            }
        }

        it("ignores offhand entity clicks so one held case file opens once") {
            val player = server.addPlayer("CaseFileOffhandClick")
            val clickedEntity = server.addPlayer("OffhandPedestalHitbox")
            val transactionId = UUID.randomUUID().toString()
            val file = caseFile(plugin, transactionId, player.uniqueId)
            player.inventory.setItemInMainHand(file)

            mockkObject(InvestigationModule)
            justRun { InvestigationModule.openCaseFile(player, transactionId) }
            try {
                val event = PlayerInteractEntityEvent(player, clickedEntity, EquipmentSlot.OFF_HAND)

                InvestigationCaseFile.onUseEntity(event)

                event.isCancelled shouldBe false
                verify(exactly = 0) { InvestigationModule.openCaseFile(any(), any()) }
            } finally {
                unmockkObject(InvestigationModule)
            }
        }
    }
})

private fun caseFile(
    plugin: org.bukkit.plugin.Plugin,
    transactionId: String,
    owner: UUID,
): ItemStack = ItemStack(Material.BOOK).also { item ->
    item.editMeta { meta ->
        meta.persistentDataContainer.set(
            NamespacedKey(plugin, "investigation_case"), PersistentDataType.STRING, transactionId,
        )
        meta.persistentDataContainer.set(
            NamespacedKey(plugin, "investigation_case_owner"), PersistentDataType.STRING, owner.toString(),
        )
    }
}
