package ru.arc.drawers

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime

class DrawerTest : FunSpec({
    test("upgrades multiply compartment capacity and protect against downgrade overflow") {
        MockBukkitTestRuntime.open().use {
            val dirt = ItemStack.of(Material.DIRT)
            val drawer = Drawer(DrawerLayout.TWO, Capacity.QUADRUPLE)
            val result = drawer.insert(1, dirt, 6000)!!
            result.drawer.slots[0].count shouldBe 0
            result.drawer.slots[1].count shouldBe 4096
            result.consumed shouldBe 4096
            result.discarded shouldBe 0
            result.drawer.canUse(Capacity.DOUBLE) shouldBe false
            result.drawer.take(1, 3072).canUse(Capacity.BASE) shouldBe true
        }
    }

    test("the lock preserves the full sample even when the last item is removed") {
        MockBukkitTestRuntime.open().use {
            val named = ItemStack.of(Material.DIRT).apply {
                itemMeta = itemMeta.apply { displayName(Component.text("Образец")) }
            }
            val unassigned = Drawer(DrawerLayout.FOUR)
            val locked = unassigned.insert(3, named, 1)!!.drawer.toggleLock().take(3, 1)
            locked.slots[3].sample!!.item.isSimilar(named) shouldBe true
            locked.insert(3, ItemStack.of(Material.DIRT), 1) shouldBe null
            locked.insert(3, named, 2)!!.drawer.slots[3].count shouldBe 2
            locked.toggleLock().slots[3].sample shouldBe null
        }
    }

    test("void upgrade consumes excess only for a matching assigned sample") {
        MockBukkitTestRuntime.open().use {
            val sample = ItemStack.of(Material.COBBLESTONE)
            val empty = Drawer(DrawerLayout.FOUR, voidOverflow = true)
            val first = empty.insert(0, sample, 1024)!!
            first.consumed shouldBe 512
            first.discarded shouldBe 0
            first.drawer.insert(0, ItemStack.of(Material.DIRT), 10) shouldBe null
            val overflow = first.drawer.insert(0, sample, 64)!!
            (overflow.drawer === first.drawer) shouldBe true
            overflow.consumed shouldBe 64
            overflow.discarded shouldBe 64
        }
    }

    test("compact header round-trips quantities and flags while retaining native samples separately") {
        MockBukkitTestRuntime.open().use {
            val sample = ItemStack.of(Material.DIRT)
            val drawer = Drawer(DrawerLayout.FOUR, Capacity.OCTUPLE, locked = true, showCounts = false, voidOverflow = true)
                .insert(3, sample, 4095)!!.drawer
            val header = DrawerHeader.encode(drawer)
            header.size shouldBe 10
            DrawerHeader.decode(header) { drawer.slots[it].sample } shouldBe drawer
            shouldThrowAny { DrawerHeader.decode(header) { null } }
            shouldThrowAny { DrawerHeader.decode(header.copyOf(6)) { drawer.slots[it].sample } }
        }
    }

    test("contents with a small stack size use their own storage limit") {
        MockBukkitTestRuntime.open().use {
            val drawer = Drawer(DrawerLayout.FOUR)
            drawer.limit(ItemStack.of(Material.DIAMOND_SWORD)) shouldBe 8
            drawer.limit(ItemStack.of(Material.ENDER_PEARL)) shouldBe 128
        }
    }
})
