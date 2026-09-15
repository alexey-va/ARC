package ru.arc.iteminfo

import dev.lone.itemsadder.api.CustomBlock
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.HookRegistry
import java.util.Locale

/** Resolves only block identities owned by supported custom-content plugins. */
internal class ItemInfoBlockResolver(
    private val itemsAdder: (Block) -> ItemInfoTarget?,
    private val slimefun: (Block) -> ItemInfoTarget?,
) {
    fun resolve(block: Block): ItemInfoTarget? = itemsAdder(block) ?: slimefun(block)
}

internal class BukkitItemInfoTargetResolver(
    private val distance: Double,
) {
    private val resolver = ItemInfoBlockResolver(::itemsAdder, ::slimefun)

    fun resolve(player: Player): ItemInfoTarget? {
        val block = player.rayTraceBlocks(distance, FluidCollisionMode.NEVER)?.hitBlock ?: return null
        return resolver.resolve(block)
    }

    private fun itemsAdder(block: Block): ItemInfoTarget? {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null
        return runCatching {
            val custom = CustomBlock.byAlreadyPlaced(block) ?: return null
            val id = custom.namespacedID?.trim()?.takeIf(String::isNotEmpty) ?: return null
            ItemInfoTarget(displayName(custom.itemStack, id), id)
        }.getOrNull()
    }

    private fun slimefun(block: Block): ItemInfoTarget? {
        val hook = HookRegistry.sfHook ?: return null
        return runCatching {
            val rawId = hook.getSlimefunBlockId(block)?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val namespacedId = "slimefun:${rawId.lowercase(Locale.ROOT)}"
            ItemInfoTarget(displayName(hook.getSlimefunItemStack(rawId), rawId), namespacedId)
        }.getOrNull()
    }

    private fun displayName(stack: ItemStack?, fallbackId: String): Component {
        val meta = stack?.itemMeta
        if (meta?.hasDisplayName() == true) return meta.displayName() ?: Component.text(humanize(fallbackId))
        return Component.text(humanize(fallbackId))
    }

    private fun humanize(id: String): String = id.substringAfter(':')
        .split('_', '-', ' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { part -> part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }
        .ifBlank { id }
}
