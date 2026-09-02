package ru.arc.treasure.core.gui

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.TitleInput
import ru.arc.board.guis.Inputable
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasureConfig
import ru.arc.treasure.core.TreasureMessage
import ru.arc.treasure.core.Treasures
import ru.arc.util.TextUtil

/**
 * GUI for editing a specific treasure.
 */
object TreasureGui {
    fun open(
        player: Player,
        poolId: String,
        treasure: Treasure,
    ) {
        val elements = buildMap {
            put("weight", ArcMenus.entry(ArcMenus.item(
                ArcMenuSchema.TREASURE_EDIT,
                "weight",
                values("weight" to treasure.weight.toString()),
            )) { start(player, WeightInput(player, poolId, treasure)) })
            amountEntry(player, poolId, treasure)?.let { put("amount", it) }
            val messages = treasure.messages.take(3).map { message ->
                mapOf("line" to Component.text("${message.destination.name.lowercase()}: ${message.text.take(20)}…"))
            }
            put("messages", ArcMenus.entryWithContext(ArcMenus.item(
                ArcMenuSchema.TREASURE_EDIT,
                "messages",
                PaperMenuItemRenderContext(
                    values = mapOf("count" to Component.text(treasure.messages.size)),
                    flags = if (treasure.messages.isEmpty()) setOf("empty") else emptySet(),
                    repeats = mapOf("messages" to messages),
                ),
            )) { click ->
                if (click.event.isRightClick) {
                    val updated = treasure.clearMessages()
                    Treasures.updateTreasure(poolId, updated)
                    refreshGui(player, poolId, updated)
                } else start(player, MessageInput(player, poolId, treasure))
            })
            put("delete", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.TREASURE_EDIT, "delete")) {
                Treasures.removeTreasure(poolId, treasure)
                backToPool(player, poolId)
            })
            put("back", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.TREASURE_EDIT, "back")) { backToPool(player, poolId) })
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.TREASURE_EDIT,
            TextUtil.mm(TreasureConfig.Gui.treasureTitle, true),
            elements = elements,
        )
    }

    private fun amountEntry(player: Player, poolId: String, treasure: Treasure) = when (treasure) {
        is Treasure.Item -> ArcMenus.entry(ArcMenus.item(
            ArcMenuSchema.TREASURE_EDIT,
            "amount",
            values("name" to "Количество", "value" to "${treasure.min}–${treasure.max}"),
        ).withType(Material.PAPER)) { start(player, AmountInput(player, poolId, treasure)) }
        is Treasure.Money -> ArcMenus.entry(ArcMenus.item(
            ArcMenuSchema.TREASURE_EDIT,
            "amount",
            values("name" to "Сумма", "value" to "${treasure.min}–${treasure.max}"),
        ).withType(Material.GOLD_NUGGET)) { start(player, MoneyAmountInput(player, poolId, treasure)) }
        is Treasure.Command -> ArcMenus.entry(ArcMenus.item(
            ArcMenuSchema.TREASURE_EDIT,
            "amount",
            values("name" to "Команда", "value" to (treasure.commands.firstOrNull() ?: "нет")),
        ).withType(Material.COMMAND_BLOCK)) { start(player, CommandInput(player, poolId, treasure)) }
        else -> null
    }

    private fun start(player: Player, input: Inputable) {
        TitleInput(player, input, 0)
        player.closeInventory()
    }

    // ==================== Helper Functions ====================

    private fun backToPool(
        player: Player,
        poolId: String,
    ) {
        Treasures.getPool(poolId)?.let { pool ->
            PoolGui.open(player, pool)
        }
    }

    private fun refreshGui(
        player: Player,
        poolId: String,
        treasure: Treasure,
    ) {
        open(player, poolId, treasure)
    }

    private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
        values = pairs.associate { (key, value) -> key to TextUtil.mm(value, true) },
    )

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
