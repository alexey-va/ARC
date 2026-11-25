package ru.arc.drawers

import org.bukkit.inventory.ItemStack

internal enum class DrawerLayout(val slotCount: Int, val stacksPerSlot: Int) {
    ONE(1, 32), TWO(2, 16), FOUR(4, 8),
}

internal enum class Capacity(val multiplier: Int) {
    BASE(1), DOUBLE(2), QUADRUPLE(4), OCTUPLE(8),
}

internal class DrawerSample(item: ItemStack) {
    val item: ItemStack = item.asOne()
}

internal data class DrawerSlot(val sample: DrawerSample? = null, val count: Int = 0)

internal data class Drawer(
    val layout: DrawerLayout,
    val capacity: Capacity = Capacity.BASE,
    val locked: Boolean = false,
    val showCounts: Boolean = true,
    val voidOverflow: Boolean = false,
    val slots: List<DrawerSlot> = List(layout.slotCount) { DrawerSlot() },
) {
    fun limit(item: ItemStack, tier: Capacity = capacity): Int =
        layout.stacksPerSlot * tier.multiplier * item.maxStackSize

    fun canUse(tier: Capacity): Boolean = slots.all { slot ->
        slot.sample == null || slot.count <= limit(slot.sample.item, tier)
    }

    fun withSlot(index: Int, slot: DrawerSlot): Drawer = copy(
        slots = slots.toMutableList().apply { this[index] = slot },
    )

    fun toggleLock(): Drawer = if (locked) {
        copy(locked = false, slots = slots.map { if (it.count == 0) DrawerSlot() else it })
    } else {
        copy(locked = true)
    }

    fun take(index: Int, amount: Int): Drawer {
        val slot = slots[index]
        require(amount in 1..slot.count)
        val remaining = slot.count - amount
        return withSlot(index, if (remaining == 0 && !locked) DrawerSlot() else slot.copy(count = remaining))
    }

    fun insert(index: Int, item: ItemStack, amount: Int): Deposit? {
        require(amount > 0)
        val slot = slots[index]
        if (slot.sample != null && !slot.sample.item.isSimilar(item)) return null
        val stored = minOf(amount, limit(item) - slot.count)
        // The void upgrade only discards excess of an already assigned item.
        val consumed = if (voidOverflow && slot.sample != null) amount else stored
        if (consumed == 0) return null
        val next = if (stored == 0) this else withSlot(
            index, DrawerSlot(slot.sample ?: DrawerSample(item), slot.count + stored),
        )
        return Deposit(next, consumed, consumed - stored)
    }

    data class Deposit(val drawer: Drawer, val consumed: Int, val discarded: Int)
}
