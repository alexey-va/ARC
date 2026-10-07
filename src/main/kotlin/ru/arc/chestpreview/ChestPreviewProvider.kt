package ru.arc.chestpreview

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.block.Chest
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.ArcInspectionProvider
import ru.arc.paper.api.InspectionHologramAnchor

/** Authorized chest halves and their immutable top anchor; halves are already in first-slot order. */
internal data class ChestPreviewTarget(
    val halves: List<Chest>,
    val anchor: InspectionHologramAnchor,
)

/** Resolves a single viewer's authorized chest contents into the shared inspection renderer. */
internal class ChestPreviewProvider(
    private val settings: ChestPreviewSettings,
    private val resolveTarget: (Player, Double) -> ChestPreviewTarget?,
) : ArcInspectionProvider {
    private val miniMessage = MiniMessage.miniMessage()

    override fun resolve(player: Player): ArcInspectionFrame? {
        if (!hasPermission(player)) return null
        return try {
            val target = resolveTarget(player, settings.maxDistance) ?: return null
            if (target.halves.size !in 1..MAX_CHEST_HALVES || target.anchor.worldId != player.world.uid) return null

            val groups = groupContents(target.halves)
            ArcInspectionFrame(
                hologram = render(groups),
                // The shared service honors the viewer's selected mode: chest contents are hologram-only.
                bossbar = Component.empty(),
                hologramAnchor = target.anchor.copy(y = target.anchor.y + settings.verticalGap),
            )
        } catch (_: Exception) {
            // A transient block-state or formatting failure hides this source only.
            // Do not disable the shared inspection loop or log item names.
            null
        }
    }

    private fun hasPermission(player: Player): Boolean =
        try {
            player.hasPermission(PERMISSION)
        } catch (_: Exception) {
            false
        }

    private fun groupContents(halves: List<Chest>): List<ItemGroup> {
        val groups = mutableListOf<ItemGroup>()
        var inspectedSlots = 0
        for (chest in halves) {
            if (inspectedSlots >= MAX_STORAGE_SLOTS) break
            val contents = chest.blockInventory.contents
            val slots = minOf(contents.size, MAX_STORAGE_SLOTS - inspectedSlots)
            for (slot in 0 until slots) {
                val stack = contents[slot] ?: continue
                if (stack.type.isAir || stack.amount <= 0) continue
                val existing = groups.firstOrNull { it.stack.isSimilar(stack) }
                if (existing == null) groups += ItemGroup(stack.clone(), stack.amount.toLong())
                else existing.amount += stack.amount.toLong()
            }
            inspectedSlots += slots
        }
        return groups
    }

    private fun render(groups: List<ItemGroup>): Component {
        val lines = buildList {
            add(miniMessage.deserialize(settings.titleTemplate))
            if (groups.isEmpty()) {
                add(miniMessage.deserialize(settings.emptyTemplate))
            } else {
                groups.take(settings.maxItems).forEach { group ->
                    add(
                        miniMessage.deserialize(
                            settings.entryTemplate,
                            Placeholder.component("name", chestPreviewDisplayName(group.stack)),
                            Placeholder.unparsed("count", group.amount.toString()),
                        ),
                    )
                }
                val overflow = groups.size - settings.maxItems
                if (overflow > 0) {
                    add(miniMessage.deserialize(settings.overflowTemplate, Placeholder.unparsed("count", overflow.toString())))
                }
            }
        }
        return lines.drop(1).fold(lines.first()) { component, line -> component.append(Component.newline()).append(line) }
    }

    private data class ItemGroup(val stack: ItemStack, var amount: Long)

    private companion object {
        const val PERMISSION = "arc.chest-preview"
        const val MAX_CHEST_HALVES = 2
        const val MAX_STORAGE_SLOTS = 54
    }
}

internal fun chestPreviewDisplayName(stack: ItemStack): Component {
    val customName = stack.itemMeta?.displayName() ?: return Component.translatable(stack.type.translationKey())
    val value = PlainTextComponentSerializer.plainText().serialize(customName)
    val compact = compactCustomName(value)
    return compact.takeIf(String::isNotEmpty)?.let { Component.text(it) }
        ?: Component.translatable(stack.type.translationKey())
}

private fun compactCustomName(value: String): String {
    val result = StringBuilder()
    var index = 0
    var codePoints = 0
    var pendingSpace = false
    while (index < value.length && codePoints < MAX_CUSTOM_NAME_CODE_POINTS) {
        val point = value.codePointAt(index)
        index += Character.charCount(point)
        if (Character.isWhitespace(point) || Character.isISOControl(point)) {
            pendingSpace = result.isNotEmpty()
            continue
        }
        if (pendingSpace) result.append(' ')
        result.appendCodePoint(point)
        pendingSpace = false
        codePoints++
    }
    return result.toString().trim()
}

private const val MAX_CUSTOM_NAME_CODE_POINTS = 64
