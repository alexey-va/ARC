package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.Repairable
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

private val repairCostKey = NamespacedKey("advancedenchantments", "repaircost")

class AdvancedGrindstoneRepairCostTest : StringSpec({
    "native grindstone output resets the AE penalty and preserves every other item field" {
        MockBukkitTestRuntime.open().use {
            val result = katana(63)
            val original = result.clone()
            val event = grindstone(result = result)
            AdvancedGrindstoneRepairCost().onPrepare(event)

            event.result shouldBe original.clone().apply {
                editMeta { it.persistentDataContainer.remove(repairCostKey) }
            }
            result shouldBe original
        }
    }

    "AE penalty follows the native cost retained for curses" {
        MockBukkitTestRuntime.open().use {
            val result = katana(63).apply {
                addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1)
                editMeta { (it as Repairable).repairCost = 1 }
            }
            val expected = result.clone().apply {
                editMeta { it.persistentDataContainer.set(repairCostKey, PersistentDataType.INTEGER, 1) }
            }
            val event = grindstone(result = result)
            AdvancedGrindstoneRepairCost().onPrepare(event)
            event.result shouldBe expected
        }
    }

    "one already-disenchanted equipment item can clear only its orphan AE penalty" {
        MockBukkitTestRuntime.open().use {
            val source = katana(63)
            val original = source.clone()
            val listener = AdvancedGrindstoneRepairCost()
            for (slot in 0..1) {
                val event = grindstone(source.takeIf { slot == 0 }, source.takeIf { slot == 1 })
                listener.onRecoverOrphan(event)
                listener.onPrepare(event)
                val clean = original.clone().apply {
                    editMeta { it.persistentDataContainer.remove(repairCostKey) }
                }
                event.result shouldBe clean
                source shouldBe original
                val repeated = grindstone(clean)
                listener.onRecoverOrphan(repeated)
                repeated.result shouldBe null
            }
        }
    }

    "orphan recovery refuses stacks, merges, native costs, enchantments and malformed markers" {
        MockBukkitTestRuntime.open().use {
            val listener = AdvancedGrindstoneRepairCost()
            val malformed = katana(63).apply {
                editMeta { it.persistentDataContainer.set(repairCostKey, PersistentDataType.STRING, "63") }
            }
            val nativePenalty = katana(63).apply { editMeta { (it as Repairable).repairCost = 63 } }
            val enchanted = katana(63).apply { addUnsafeEnchantment(Enchantment.SHARPNESS, 1) }
            val customEnchanted = katana(63).apply {
                editMeta { it.persistentDataContainer.set(
                    NamespacedKey("advancedenchantments", "ae_enchantment-strike"), PersistentDataType.INTEGER, 1,
                ) }
            }
            for ((first, second) in listOf(
                katana(63).apply { amount = 2 } to null,
                katana(63) to ItemStack(Material.NETHERITE_SWORD),
                nativePenalty to null,
                enchanted to null,
                customEnchanted to null,
                malformed to null,
                katana(0) to null,
                katana(-1) to null,
                katana(63).apply { type = Material.PAPER } to null,
            )) {
                val event = grindstone(first, second)
                listener.onRecoverOrphan(event)
                event.result shouldBe null
            }
        }
    }

    "provider rejection and outputs without an INTEGER AE penalty stay unchanged" {
        MockBukkitTestRuntime.open().use {
            val listener = AdvancedGrindstoneRepairCost()
            val rejected = grindstone(katana(63))
            listener.onRecoverOrphan(rejected)
            rejected.result = null
            listener.onPrepare(rejected)
            rejected.result shouldBe null
            val clean = katana(63).apply { editMeta { it.persistentDataContainer.remove(repairCostKey) } }
            val unmarked = grindstone(result = clean)
            listener.onPrepare(unmarked)
            (unmarked.result === clean) shouldBe true
        }
    }

    "recovery precedes provider validation and final mirror synchronization follows it" {
        AdvancedGrindstoneRepairCost::class.java.getMethod("onRecoverOrphan", PrepareGrindstoneEvent::class.java)
            .getAnnotation(EventHandler::class.java).priority shouldBe EventPriority.LOWEST
        AdvancedGrindstoneRepairCost::class.java.getMethod("onPrepare", PrepareGrindstoneEvent::class.java)
            .getAnnotation(EventHandler::class.java).priority shouldBe EventPriority.HIGHEST
    }
})

private fun katana(cost: Int) = ItemStack(Material.NETHERITE_SWORD).apply {
    editMeta { meta ->
        meta.displayName(Component.text("Эндер Катана"))
        meta.setCustomModelData(10090)
        (meta as Damageable).damage = 37
        (meta as Repairable).repairCost = 0
        meta.persistentDataContainer.set(repairCostKey, PersistentDataType.INTEGER, cost)
        meta.persistentDataContainer.set(NamespacedKey("advancedenchantments", "mobtrak"), PersistentDataType.INTEGER, 259)
        meta.persistentDataContainer.set(NamespacedKey("itemsadder", "id"), PersistentDataType.STRING, "ender_eye_katana")
    }
}

private fun grindstone(first: ItemStack? = null, second: ItemStack? = null, result: ItemStack? = null): PrepareGrindstoneEvent {
    val inventory = mockk<GrindstoneInventory>()
    every { inventory.getItem(0) } returns first
    every { inventory.getItem(1) } returns second
    val view = mockk<InventoryView>()
    every { view.topInventory } returns inventory
    return PrepareGrindstoneEvent(view, result)
}
