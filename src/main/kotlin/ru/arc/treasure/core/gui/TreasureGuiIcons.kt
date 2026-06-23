package ru.arc.treasure.core.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.HookRegistry
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasureConfig
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import ru.arc.util.ItemStackDslBuilder
import ru.arc.util.itemStack
import ru.arc.util.modify

/**
 * Icon stacks for treasure admin GUIs — preserves item meta (custom model data, display, etc.).
 */
object TreasureGuiIcons {
    /**
     * Pool row icon in the main menu: first treasure preview, or chest when empty.
     */
    fun poolPreviewIcon(pool: TreasurePool): ItemStack {
        if (pool.isEmpty()) return ItemStack(Material.CHEST)
        return iconStack(pool.treasures.first())
    }

    /**
     * Base display stack for a treasure (amount = 1).
     */
    fun iconStack(treasure: Treasure): ItemStack =
        when (treasure) {
            is Treasure.Item -> {
                treasure.stack.clone().apply {
                    amount = 1
                }
            }

            is Treasure.Money -> {
                moneyIconStack(treasure)
            }

            is Treasure.Command -> {
                commandIconStack(treasure)
            }

            is Treasure.SubPool -> {
                subPoolIconStack(treasure)
            }

            is Treasure.Enchant -> {
                enchantIconStack(treasure)
            }

            is Treasure.Potion -> {
                potionIconStack(treasure)
            }

            is Treasure.Ae -> {
                aeIconStack(treasure)
            }

            is Treasure.Slimefun -> {
                slimefunIconStack(treasure)
            }
        }

    /**
     * Stack for pool list rows — keeps item appearance and appends editor lore.
     */
    fun listIconStack(treasure: Treasure): ItemStack =
        iconStack(treasure).modify {
            appendManagementLore(treasure)
        }

    fun toGuiItem(
        treasure: Treasure,
        onClick: (InventoryClickEvent) -> Unit,
    ): GuiItem = GuiItem(listIconStack(treasure)) { onClick(it) }

    private fun moneyIconStack(treasure: Treasure.Money): ItemStack =
        itemStack(Material.GOLD_INGOT) {
            display(TreasureConfig.GuiIcons.moneyDisplay)
            lore(
                formatTemplate(
                    TreasureConfig.GuiIcons.moneyAmountLore,
                    mapOf("min" to treasure.min.toString(), "max" to treasure.max.toString()),
                ),
            )
        }

    private fun commandIconStack(treasure: Treasure.Command): ItemStack =
        itemStack(Material.COMMAND_BLOCK) {
            display(TreasureConfig.GuiIcons.commandDisplay)
            treasure.commands.firstOrNull()?.let { lore("<gray>> <white>$it") }
        }

    private fun subPoolIconStack(treasure: Treasure.SubPool): ItemStack {
        val preview = Treasures.getPool(treasure.poolId)?.let { poolPreviewIcon(it) }
        if (preview != null) {
            return preview.modify {
                if (!hasCustomDisplayName(preview)) {
                    display(TreasureConfig.GuiIcons.subPoolDisplay)
                }
                lore(
                    formatTemplate(
                        TreasureConfig.GuiIcons.subPoolLoreId,
                        mapOf("pool_id" to treasure.poolId),
                    ),
                )
            }
        }
        return itemStack(Material.CHEST_MINECART) {
            display(TreasureConfig.GuiIcons.subPoolDisplay)
            lore(
                formatTemplate(
                    TreasureConfig.GuiIcons.subPoolLoreId,
                    mapOf("pool_id" to treasure.poolId),
                ),
            )
        }
    }

    private fun enchantIconStack(treasure: Treasure.Enchant): ItemStack =
        itemStack(Material.ENCHANTED_BOOK) {
            display(TreasureConfig.GuiIcons.enchantDisplay)
            lore(
                formatTemplate(
                    TreasureConfig.GuiIcons.enchantAmountLore,
                    mapOf("min" to treasure.min.toString(), "max" to treasure.max.toString()),
                ),
            )
        }

    private fun potionIconStack(treasure: Treasure.Potion): ItemStack =
        itemStack(Material.POTION) {
            display(TreasureConfig.GuiIcons.potionDisplay)
            lore(
                formatTemplate(
                    TreasureConfig.GuiIcons.potionAmountLore,
                    mapOf("min" to treasure.min.toString(), "max" to treasure.max.toString()),
                ),
            )
        }

    private fun aeIconStack(treasure: Treasure.Ae): ItemStack =
        itemStack(Material.ENCHANTED_BOOK) {
            display(TreasureConfig.GuiIcons.aeDisplay)
            lore("<white>${treasure.displayName}")
        }

    private fun slimefunIconStack(treasure: Treasure.Slimefun): ItemStack {
        val resolved = resolveSlimefunStack(treasure.itemId)
        if (resolved != null) {
            return resolved.modify {
                if (!hasCustomDisplayName(resolved)) {
                    display(slimefunFallbackDisplay(treasure))
                }
                lore(slimefunBaseLore(treasure))
            }
        }
        return itemStack(Material.IRON_INGOT) {
            display(slimefunFallbackDisplay(treasure))
            lore(slimefunBaseLore(treasure))
        }
    }

    private fun resolveSlimefunStack(itemId: String): ItemStack? =
        HookRegistry.sfHook
            ?.getSlimefunItemStack(itemId)
            ?.clone()
            ?.apply { amount = 1 }

    private fun slimefunFallbackDisplay(treasure: Treasure.Slimefun): String =
        "${TreasureConfig.GuiIcons.slimefunDisplay}: <white>${treasure.itemId}"

    private fun slimefunBaseLore(treasure: Treasure.Slimefun): List<String> =
        listOf(
            formatTemplate(
                TreasureConfig.GuiIcons.slimefunLoreId,
                mapOf("item_id" to treasure.itemId),
            ),
            formatTemplate(
                TreasureConfig.GuiIcons.slimefunAmountLore,
                mapOf("min" to treasure.min.toString(), "max" to treasure.max.toString()),
            ),
        )

    private fun hasCustomDisplayName(stack: ItemStack): Boolean {
        val name = stack.itemMeta?.displayName() ?: return false
        return PlainTextComponentSerializer.plainText().serialize(name).isNotBlank()
    }

    private fun formatTemplate(
        template: String,
        placeholders: Map<String, String>,
    ): String {
        var result = template
        placeholders.forEach { (key, value) ->
            result = result.replace("<$key>", value).replace("{$key}", value)
        }
        return result
    }

    private fun ItemStackDslBuilder.appendManagementLore(treasure: Treasure) {
        appendLore("")

        when (treasure) {
            is Treasure.Item -> {
                appendLore("<gray>Количество: <white>${treasure.min}-${treasure.max}")
                appendLore("<gray>Вес: <white>${treasure.weight}")
                if (treasure.messages.isNotEmpty()) {
                    appendLore("<gray>Сообщений: <white>${treasure.messages.size}")
                }
            }

            is Treasure.Money -> {
                appendLore("<gray>Вес: <white>${treasure.weight}")
                if (treasure.messages.isNotEmpty()) {
                    appendLore("<gray>Сообщений: <white>${treasure.messages.size}")
                }
            }

            is Treasure.Command -> {
                appendLore("<gray>Вес: <white>${treasure.weight}")
                if (treasure.messages.isNotEmpty()) {
                    appendLore("<gray>Сообщений: <white>${treasure.messages.size}")
                }
            }

            is Treasure.SubPool -> {
                appendLore("<gray>Вес: <white>${treasure.weight}")
                if (treasure.messages.isNotEmpty()) {
                    appendLore("<gray>Сообщений: <white>${treasure.messages.size}")
                }
            }

            is Treasure.Enchant -> {
                appendLore("<gray>Вес: <white>${treasure.weight}")
                if (treasure.messages.isNotEmpty()) {
                    appendLore("<gray>Сообщений: <white>${treasure.messages.size}")
                }
            }

            is Treasure.Potion -> {
                appendLore("<gray>Вес: <white>${treasure.weight}")
                if (treasure.messages.isNotEmpty()) {
                    appendLore("<gray>Сообщений: <white>${treasure.messages.size}")
                }
            }

            is Treasure.Ae -> {
                appendLore("<gray>Вес: <white>${treasure.weight}")
                if (treasure.args.isNotEmpty()) {
                    appendLore("<gray>Аргументов: <white>${treasure.args.size}")
                }
            }

            is Treasure.Slimefun -> {
                appendLore("<gray>Вес: <white>${treasure.weight}")
            }
        }

        appendLore("")
        appendLore("<yellow>ЛКМ - редактировать")
        appendLore("<red>ПКМ - удалить")
    }
}
