package ru.arc.treasure.core.gui

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.TitleInput
import ru.arc.board.guis.Inputable
import ru.arc.gui.gui
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasureConfig
import ru.arc.treasure.core.TreasureMessage
import ru.arc.treasure.core.Treasures
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil

/**
 * GUI for editing a specific treasure.
 */
object TreasureGui {
    private const val ROWS = 2

    fun create(
        player: Player,
        poolId: String,
        treasure: Treasure,
    ): ChestGui =
        gui(
            title = TreasureConfig.Gui.treasureTitle,
            rows = ROWS,
            player = player,
        ) {
            navBackground()

            staticPane(0, 0, 9, ROWS - 1) {
                // Weight button
                item(1, 0) {
                    material(Material.ANVIL)
                    display(TreasureConfig.Gui.treasureWeight.replace("%weight%", treasure.weight.toString()))
                    lore(listOf("<gray>ЛКМ - изменить вес"))
                    onClick {
                        TitleInput(player, WeightInput(player, poolId, treasure), 0)
                        player.closeInventory()
                    }
                }

                // Amount button (for treasures with amount)
                when (treasure) {
                    is Treasure.Item -> {
                        item(2, 0) {
                            material(Material.PAPER)
                            display(
                                TreasureConfig.Gui.treasureAmount
                                    .replace("%min%", treasure.min.toString())
                                    .replace("%max%", treasure.max.toString()),
                            )
                            lore(listOf("<gray>ЛКМ - изменить количество"))
                            onClick {
                                TitleInput(player, AmountInput(player, poolId, treasure), 0)
                                player.closeInventory()
                            }
                        }
                    }

                    is Treasure.Money -> {
                        item(2, 0) {
                            material(Material.GOLD_NUGGET)
                            display(
                                TreasureConfig.Gui.treasureAmount
                                    .replace("%min%", treasure.min.toString())
                                    .replace("%max%", treasure.max.toString()),
                            )
                            lore(listOf("<gray>ЛКМ - изменить сумму"))
                            onClick {
                                TitleInput(player, MoneyAmountInput(player, poolId, treasure), 0)
                                player.closeInventory()
                            }
                        }
                    }

                    is Treasure.Command -> {
                        item(2, 0) {
                            material(Material.COMMAND_BLOCK)
                            display("<yellow>Команда")
                            lore(
                                listOf(
                                    "<gray>Текущая: <white>${treasure.commands.firstOrNull() ?: "нет"}",
                                    "<gray>ЛКМ - изменить команду",
                                ),
                            )
                            onClick {
                                TitleInput(player, CommandInput(player, poolId, treasure), 0)
                                player.closeInventory()
                            }
                        }
                    }

                    else -> {}
                }

                // Messages button
                item(4, 0) {
                    material(Material.WRITABLE_BOOK)
                    display(TreasureConfig.Gui.treasureMessages)
                    lore(
                        buildList {
                            if (treasure.messages.isEmpty()) {
                                add("<gray>Нет сообщений")
                            } else {
                                add("<gray>Сообщений: <white>${treasure.messages.size}")
                                treasure.messages.take(3).forEach { msg ->
                                    add("<dark_gray>• ${msg.destination.name.lowercase()}: ${msg.text.take(20)}...")
                                }
                            }
                            add("")
                            add("<yellow>ЛКМ - добавить сообщение")
                            add("<red>ПКМ - очистить все")
                        },
                    )
                    onClick { event ->
                        if (event.isRightClick) {
                            val updated = treasure.clearMessages()
                            Treasures.updateTreasure(poolId, updated)
                            refreshGui(player, poolId, updated)
                        } else {
                            TitleInput(player, MessageInput(player, poolId, treasure), 0)
                            player.closeInventory()
                        }
                    }
                }

                // Delete button
                item(7, 0) {
                    material(Material.BARRIER)
                    display(TreasureConfig.Gui.treasureDelete)
                    lore(listOf("<gray>ЛКМ - удалить"))
                    onClick {
                        Treasures.removeTreasure(poolId, treasure)
                        backToPool(player, poolId)
                    }
                }
            }

            navBar {
                back(slot = 0) { backToPool(player, poolId) }
            }
        }

    // ==================== Helper Functions ====================

    private fun backToPool(
        player: Player,
        poolId: String,
    ) {
        Treasures.getPool(poolId)?.let { pool ->
            GuiUtils.constructAndShowAsync({ PoolGui.create(player, pool) }, player)
        }
    }

    private fun refreshGui(
        player: Player,
        poolId: String,
        treasure: Treasure,
    ) {
        GuiUtils.constructAndShowAsync({ create(player, poolId, treasure) }, player)
    }

    // ==================== Input Handlers ====================

    private class WeightInput(
        private val player: Player,
        private val poolId: String,
        private val treasure: Treasure,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            val weight = s.toIntOrNull() ?: return
            val updated = treasure.withWeight(weight)
            Treasures.updateTreasure(poolId, updated)
        }

        override fun proceed() {
            Treasures.getPool(poolId)?.findById(treasure.id)?.let {
                refreshGui(player, poolId, it)
            } ?: backToPool(player, poolId)
        }

        override fun satisfy(
            input: String,
            id: Int,
        ) = input.toIntOrNull()?.let { it > 0 } ?: false

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Input.invalidWeight)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Input.inputWeight)
    }

    private class AmountInput(
        private val player: Player,
        private val poolId: String,
        private val treasure: Treasure.Item,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            val (min, max) = parseAmount(s)
            val updated = treasure.withAmount(min, max)
            Treasures.updateTreasure(poolId, updated)
        }

        override fun proceed() {
            Treasures.getPool(poolId)?.findById(treasure.id)?.let {
                refreshGui(player, poolId, it)
            } ?: backToPool(player, poolId)
        }

        override fun satisfy(
            input: String,
            id: Int,
        ): Boolean {
            val (min, max) = parseAmount(input)
            return min > 0 && max >= min
        }

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Input.invalidAmount)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Input.inputAmount)

        private fun parseAmount(s: String): Pair<Int, Int> {
            val parts = s.split("-")
            return if (parts.size == 2) {
                (parts[0].toIntOrNull() ?: 0) to (parts[1].toIntOrNull() ?: 0)
            } else {
                val v = s.toIntOrNull() ?: 0
                v to v
            }
        }
    }

    private class MoneyAmountInput(
        private val player: Player,
        private val poolId: String,
        private val treasure: Treasure.Money,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            val (min, max) = parseAmount(s)
            val updated = treasure.withAmount(min, max)
            Treasures.updateTreasure(poolId, updated)
        }

        override fun proceed() {
            Treasures.getPool(poolId)?.findById(treasure.id)?.let {
                refreshGui(player, poolId, it)
            } ?: backToPool(player, poolId)
        }

        override fun satisfy(
            input: String,
            id: Int,
        ): Boolean {
            val (min, max) = parseAmount(input)
            return min >= 0 && max >= min
        }

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Input.invalidMoneyAmount)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Input.inputMoneyAmount)

        private fun parseAmount(s: String): Pair<Double, Double> {
            val parts = s.split("-")
            return if (parts.size == 2) {
                (parts[0].toDoubleOrNull() ?: 0.0) to (parts[1].toDoubleOrNull() ?: 0.0)
            } else {
                val v = s.toDoubleOrNull() ?: 0.0
                v to v
            }
        }
    }

    private class CommandInput(
        private val player: Player,
        private val poolId: String,
        private val treasure: Treasure.Command,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            val updated = treasure.withCommands(listOf(s))
            Treasures.updateTreasure(poolId, updated)
        }

        override fun proceed() {
            Treasures.getPool(poolId)?.findById(treasure.id)?.let {
                refreshGui(player, poolId, it)
            } ?: backToPool(player, poolId)
        }

        override fun satisfy(
            input: String,
            id: Int,
        ) = input.isNotBlank()

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Input.invalidCommand)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Input.inputCommand)
    }

    private class MessageInput(
        private val player: Player,
        private val poolId: String,
        private val treasure: Treasure,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            if (s.isNotBlank() && !s.equals("нет", ignoreCase = true)) {
                val message = TreasureMessage.chat(s)
                val updated = treasure.addMessage(message)
                Treasures.updateTreasure(poolId, updated)
            }
        }

        override fun proceed() {
            Treasures.getPool(poolId)?.findById(treasure.id)?.let {
                refreshGui(player, poolId, it)
            } ?: backToPool(player, poolId)
        }

        override fun satisfy(
            input: String,
            id: Int,
        ) = true

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Input.invalidMessage)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Input.inputMessage)
    }
}
