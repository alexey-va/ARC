package ru.arc.treasure.core.gui

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import ru.arc.TitleInput
import ru.arc.board.guis.Inputable
import ru.arc.gui.gui
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasureConfig
import ru.arc.treasure.core.TreasureMessage
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.itemStack

/**
 * GUI for viewing and managing a specific treasure pool.
 */
object PoolGui {
    private const val ROWS = 6

    fun create(
        player: Player,
        pool: TreasurePool,
    ): ChestGui =
        gui(
            title = TreasureConfig.Gui.poolTitle.replace("%pool%", pool.id),
            rows = ROWS,
            player = player,
        ) {
            navBackground()

            pagination(0 until (ROWS - 1)) {
                // Build GuiItems for each treasure
                guiItems(
                    pool.treasures.map { treasure ->
                        buildTreasureGuiItem(treasure) { event ->
                            if (event.click == ClickType.RIGHT || event.click == ClickType.SHIFT_RIGHT) {
                                Treasures.removeTreasure(pool.id, treasure)
                                refreshPool(player, pool.id)
                            } else {
                                GuiUtils.constructAndShowAsync(
                                    { TreasureGui.create(player, pool.id, treasure) },
                                    player,
                                )
                            }
                        }
                    },
                )
            }

            navBar {
                back(slot = 0) {
                    GuiUtils.constructAndShowAsync({ MainTreasuresGui.create(player) }, player)
                }

                prevPage(slot = 2)
                nextPage(slot = 3)

                // Pool messages button
                button(4) {
                    material(Material.BOOK)
                    display(TreasureConfig.Gui.poolMessages)
                    lore(
                        buildList {
                            if (pool.messages.isEmpty()) {
                                add("<gray>Нет сообщений пула")
                            } else {
                                add("<gray>Сообщений: <white>${pool.messages.size}")
                                pool.messages.take(3).forEach { msg ->
                                    add("<dark_gray>• ${msg.target.name.lowercase()}: ${msg.text.take(20)}...")
                                }
                            }
                            add("")
                            add("<yellow>ЛКМ - добавить сообщение")
                            add("<red>ПКМ - очистить все")
                        },
                    )
                    onClick { event ->
                        if (event.isRightClick) {
                            val updated = pool.clearMessages()
                            Treasures.manager.updatePool(updated)
                            refreshPool(player, pool.id)
                        } else {
                            TitleInput(player, PoolMessageInput(player, pool.id), 0)
                            player.closeInventory()
                        }
                    }
                }

                // Add item treasure
                button(5) {
                    material(Material.DIAMOND)
                    display(TreasureConfig.Gui.poolAddItem)
                    lore(listOf("<gray>ЛКМ - добавить предмет в руке"))
                    onClick {
                        val itemInHand = player.inventory.itemInMainHand
                        if (itemInHand.type != Material.AIR) {
                            val treasure = Treasure.Item(stack = itemInHand.clone())
                            Treasures.addTreasure(pool.id, treasure)
                            refreshPool(player, pool.id)
                        }
                    }
                }

                // Add money treasure
                button(6) {
                    material(Material.GOLD_INGOT)
                    display(TreasureConfig.Gui.poolAddMoney)
                    lore(listOf("<gray>ЛКМ - добавить деньги"))
                    onClick {
                        TitleInput(player, AddMoneyInput(player, pool.id), 0)
                        player.closeInventory()
                    }
                }

                // Add command treasure
                button(7) {
                    material(Material.COMMAND_BLOCK)
                    display(TreasureConfig.Gui.poolAddCommand)
                    lore(listOf("<gray>ЛКМ - добавить команду"))
                    onClick {
                        TitleInput(player, AddCommandInput(player, pool.id), 0)
                        player.closeInventory()
                    }
                }

                // Delete pool
                button(8) {
                    material(Material.BARRIER)
                    display(TreasureConfig.Gui.poolDelete)
                    lore(listOf(TreasureConfig.Gui.poolDeleteConfirm))
                    onClick { event ->
                        if (event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT) {
                            Treasures.delete(pool.id)
                            player.sendMessage(
                                TextUtil.mm(
                                    TreasureConfig.Messages.poolDeleted.replace(
                                        "%pool%",
                                        pool.id,
                                    ),
                                ),
                            )
                            GuiUtils.constructAndShowAsync({ MainTreasuresGui.create(player) }, player)
                        }
                    }
                }
            }
        }

    private fun buildTreasureGuiItem(
        treasure: Treasure,
        onClick: (org.bukkit.event.inventory.InventoryClickEvent) -> Unit,
    ): com.github.stefvanschie.inventoryframework.gui.GuiItem {
        val stack =
            when (treasure) {
                is Treasure.Item -> {
                    itemStack(treasure.stack.type) {
                        display("<yellow>Предмет: <white>${treasure.stack.type.name}")
                        lore {
                            +"<gray>Количество: <white>${treasure.min}-${treasure.max}"
                            +"<gray>Вес: <white>${treasure.weight}"
                            if (treasure.messages.isNotEmpty()) {
                                +"<gray>Сообщений: <white>${treasure.messages.size}"
                            }
                            +""
                            +"<yellow>ЛКМ - редактировать"
                            +"<red>ПКМ - удалить"
                        }
                    }
                }

                is Treasure.Money -> {
                    itemStack(Material.GOLD_INGOT) {
                        display("<gold>Деньги: <white>${treasure.min}-${treasure.max}")
                        lore {
                            +"<gray>Вес: <white>${treasure.weight}"
                            if (treasure.messages.isNotEmpty()) {
                                +"<gray>Сообщений: <white>${treasure.messages.size}"
                            }
                            +""
                            +"<yellow>ЛКМ - редактировать"
                            +"<red>ПКМ - удалить"
                        }
                    }
                }

                is Treasure.Command -> {
                    itemStack(Material.COMMAND_BLOCK) {
                        display("<aqua>Команда")
                        lore {
                            treasure.commands.forEach { +"<gray>> <white>$it" }
                            +"<gray>Вес: <white>${treasure.weight}"
                            if (treasure.messages.isNotEmpty()) {
                                +"<gray>Сообщений: <white>${treasure.messages.size}"
                            }
                            +""
                            +"<yellow>ЛКМ - редактировать"
                            +"<red>ПКМ - удалить"
                        }
                    }
                }

                is Treasure.SubPool -> {
                    itemStack(Material.CHEST_MINECART) {
                        display("<purple>Подпул: <white>${treasure.poolId}")
                        lore {
                            +"<gray>Вес: <white>${treasure.weight}"
                            if (treasure.messages.isNotEmpty()) {
                                +"<gray>Сообщений: <white>${treasure.messages.size}"
                            }
                            +""
                            +"<yellow>ЛКМ - редактировать"
                            +"<red>ПКМ - удалить"
                        }
                    }
                }

                is Treasure.Enchant -> {
                    itemStack(Material.ENCHANTED_BOOK) {
                        display("<light_purple>Зачарование: <white>${treasure.min}-${treasure.max}")
                        lore {
                            +"<gray>Вес: <white>${treasure.weight}"
                            if (treasure.messages.isNotEmpty()) {
                                +"<gray>Сообщений: <white>${treasure.messages.size}"
                            }
                            +""
                            +"<yellow>ЛКМ - редактировать"
                            +"<red>ПКМ - удалить"
                        }
                    }
                }

                is Treasure.Potion -> {
                    itemStack(Material.POTION) {
                        display("<dark_purple>Зелье: <white>${treasure.min}-${treasure.max}")
                        lore {
                            +"<gray>Вес: <white>${treasure.weight}"
                            if (treasure.messages.isNotEmpty()) {
                                +"<gray>Сообщений: <white>${treasure.messages.size}"
                            }
                            +""
                            +"<yellow>ЛКМ - редактировать"
                            +"<red>ПКМ - удалить"
                        }
                    }
                }

                is Treasure.Ae -> {
                    itemStack(Material.ENCHANTED_BOOK) {
                        display("<gold>${treasure.displayName}")
                        lore {
                            +"<gray>Вес: <white>${treasure.weight}"
                            if (treasure.args.isNotEmpty()) {
                                +"<gray>Аргументов: <white>${treasure.args.size}"
                            }
                            +""
                            +"<yellow>ЛКМ - редактировать"
                            +"<red>ПКМ - удалить"
                        }
                    }
                }

                is Treasure.Slimefun -> {
                    itemStack(Material.IRON_INGOT) {
                        display("<green>${treasure.displayName}")
                        lore {
                            +"<gray>Вес: <white>${treasure.weight}"
                            +""
                            +"<yellow>ЛКМ - редактировать"
                            +"<red>ПКМ - удалить"
                        }
                    }
                }
            }
        return com.github.stefvanschie.inventoryframework.gui
            .GuiItem(stack) { onClick(it) }
    }

    private fun refreshPool(
        player: Player,
        poolId: String,
    ) {
        val pool = Treasures.getPool(poolId) ?: return
        GuiUtils.constructAndShowAsync({ create(player, pool) }, player)
    }

    // ==================== Input Handlers ====================

    private class PoolMessageInput(
        private val player: Player,
        private val poolId: String,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            if (n == 0 && s.isNotBlank()) {
                val pool = Treasures.getPool(poolId) ?: return
                val message = TreasureMessage.chat(s)
                val updated = pool.addMessage(message)
                Treasures.manager.updatePool(updated)
            }
        }

        override fun proceed() {
            Treasures.getPool(poolId)?.let {
                GuiUtils.constructAndShowAsync({ create(player, it) }, player)
            }
        }

        override fun satisfy(
            input: String,
            id: Int,
        ): Boolean = true

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Input.invalidMessage)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Input.inputMessage)
    }

    private class AddMoneyInput(
        private val player: Player,
        private val poolId: String,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            if (n == 0) {
                val (min, max) = parseAmountDouble(s)
                val treasure = Treasure.Money(min = min, max = max)
                Treasures.addTreasure(poolId, treasure)
            }
        }

        override fun proceed() {
            Treasures.getPool(poolId)?.let {
                GuiUtils.constructAndShowAsync({ create(player, it) }, player)
            }
        }

        override fun satisfy(
            input: String,
            id: Int,
        ): Boolean = id == 0 && parseAmountDouble(input).first >= 0

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Input.invalidMoneyAmount)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Input.inputMoneyAmount)
    }

    private class AddCommandInput(
        private val player: Player,
        private val poolId: String,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            if (n == 0) {
                val treasure = Treasure.Command(commands = listOf(s))
                Treasures.addTreasure(poolId, treasure)
            }
        }

        override fun proceed() {
            Treasures.getPool(poolId)?.let {
                GuiUtils.constructAndShowAsync({ create(player, it) }, player)
            }
        }

        override fun satisfy(
            input: String,
            id: Int,
        ): Boolean = id == 0 && input.isNotBlank()

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Input.invalidCommand)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Input.inputCommand)
    }

    // ==================== Helpers ====================

    private fun parseAmountDouble(value: String): Pair<Double, Double> {
        val parts = value.split("-")
        return if (parts.size == 2) {
            (parts[0].toDoubleOrNull() ?: 0.0) to (parts[1].toDoubleOrNull() ?: 0.0)
        } else {
            val v = value.toDoubleOrNull() ?: 0.0
            v to v
        }
    }
}
