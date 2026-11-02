package ru.arc.graves

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec
import java.util.Base64
import kotlin.math.floor
import kotlin.math.roundToLong

/** Grave-specific item/XP transition; all native item operations run on Paper. */
internal object NativeGraveItems {
    fun encode(item: ItemStack): String = Base64.getEncoder().encodeToString(NativePaperItemStackBinaryCodec.encodeItem(item))
    fun decode(value: String): ItemStack = NativePaperItemStackBinaryCodec.decodeItem(Base64.getDecoder().decode(value))

    fun capture(player: Player) = NativeGraveInventory(
        (0..40).map { player.inventory.getItem(it)?.takeUnless(ItemStack::isEmpty)?.let(::encode) },
        player.level, player.exp, player.totalExperience,
    )

    fun apply(player: Player, value: NativeGraveInventory) {
        val items = value.slots.map { it?.let(::decode) }
        items.forEachIndexed { slot, item -> player.inventory.setItem(slot, item) }
        player.level = value.level
        player.exp = value.progress
        player.totalExperience = value.totalExperience
        player.updateInventory()
        check(capture(player) == value) { "Grave inventory application did not match its durable intent" }
    }

    /** Remove only actual death drops; plugin-retained/soulbound items remain with their owner. */
    fun afterDeath(before: NativeGraveInventory, drops: List<ItemStack>, keepExperience: Boolean): NativeGraveInventory {
        val slots = before.slots.map { it?.let(::decode) }.toMutableList()
        drops.forEach { drop ->
            var remaining = drop.amount
            for (slot in slots.indices) {
                val item = slots[slot] ?: continue
                if (!item.isSimilar(drop)) continue
                val removed = minOf(remaining, item.amount)
                item.amount -= removed
                remaining -= removed
                if (item.amount == 0) slots[slot] = null
                if (remaining == 0) break
            }
        }
        return NativeGraveInventory(slots.map { it?.let(::encode) },
            if (keepExperience) before.level else 0,
            if (keepExperience) before.progress else 0f,
            if (keepExperience) before.totalExperience else 0)
    }

    data class Pickup(val after: NativeGraveInventory, val remaining: List<String>)

    /** Fill storage only, keeping overflow in the same grave and preserving native metadata. */
    fun pickup(before: NativeGraveInventory, contents: List<String>, experience: Int): Pickup {
        val slots = before.slots.map { it?.let(::decode) }.toMutableList()
        val remaining = mutableListOf<String>()
        contents.forEach { raw ->
            val incoming = decode(raw)
            for (slot in 0..35) {
                val existing = slots[slot] ?: continue
                if (!existing.isSimilar(incoming)) continue
                val amount = minOf(incoming.amount, (existing.maxStackSize - existing.amount).coerceAtLeast(0))
                existing.amount += amount
                incoming.amount -= amount
                if (incoming.amount == 0) break
            }
            for (slot in 0..35) {
                if (incoming.amount == 0) break
                if (slots[slot] != null) continue
                val amount = minOf(incoming.amount, incoming.maxStackSize)
                slots[slot] = incoming.clone().also { it.amount = amount }
                incoming.amount -= amount
            }
            if (incoming.amount > 0) remaining += encode(incoming)
        }
        val total = (experiencePoints(before.level, before.progress).toLong() + experience).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val (level, progress) = experienceState(total)
        return Pickup(NativeGraveInventory(slots.map { it?.let(::encode) }, level, progress,
            (before.totalExperience.toLong() + experience).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), remaining)
    }

    fun experiencePoints(level: Int, progress: Float): Int {
        val base = when {
            level <= 16 -> level.toLong() * level + 6L * level
            level <= 31 -> (5L * level * level - 81L * level + 720) / 2
            else -> (9L * level * level - 325L * level + 4440) / 2
        }
        val next = when { level >= 30 -> 9L * level - 158; level >= 15 -> 5L * level - 38; else -> 2L * level + 7 }
        return (base + (progress * next.toDouble()).roundToLong()).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    fun experienceState(total: Int): Pair<Int, Float> {
        var low = 0
        var high = 1_000_000
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (experiencePoints(mid, 0f) <= total && experiencePoints(mid, 0f) != Int.MAX_VALUE) low = mid else high = mid - 1
        }
        val base = experiencePoints(low, 0f)
        val next = when { low >= 30 -> 9L * low - 158; low >= 15 -> 5L * low - 38; else -> 2L * low + 7 }
        return low to ((total.toLong() - base).toDouble() / next).toFloat().coerceIn(0f, .99999994f)
    }
}
