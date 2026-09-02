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
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import ru.arc.util.TextUtil

object PoolGui {
    fun open(player: Player, pool: TreasurePool) {
        val entries = pool.treasures.map { treasure ->
            val original = TreasureGuiIcons.listIconStack(treasure)
            val meta = original.itemMeta
            val rendered = ArcMenus.item(
                "treasure-entry",
                PaperMenuItemRenderContext(
                    values = mapOf(
                        "name" to (meta.displayName() ?: Component.text(treasure.displayName)),
                        "type" to Component.text(treasure.type),
                        "weight" to Component.text(treasure.weight),
                    ),
                    repeats = mapOf("original" to meta.lore().orEmpty().map { mapOf("line" to it) }),
                ),
            )
            ArcMenus.entryWithContext(MainTreasuresGui.applyPresentation(original, rendered)) { click ->
                if (click.event.isRightClick) {
                    Treasures.removeTreasure(pool.id, treasure)
                    refreshPool(player, pool.id)
                } else {
                    TreasureGui.open(player, pool.id, treasure)
                }
            }
        }
        val messages = pool.messages.take(3).map { message ->
            mapOf("line" to Component.text("${message.target.name.lowercase()}: ${message.text.take(20)}…"))
        }
        val controls = mapOf(
            "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.TREASURE_POOL, "back")) { MainTreasuresGui.open(it) },
            "previous" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.TREASURE_POOL, "previous")) { it.session.previousPage() },
            "next" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.TREASURE_POOL, "next")) { it.session.nextPage() },
            "messages" to ArcMenus.entryWithContext(ArcMenus.item(
                ArcMenuSchema.TREASURE_POOL,
                "messages",
                PaperMenuItemRenderContext(
                    values = mapOf("count" to Component.text(pool.messages.size)),
                    flags = if (pool.messages.isEmpty()) setOf("empty") else emptySet(),
                    repeats = mapOf("messages" to messages),
                ),
            )) { click ->
                if (click.event.isRightClick) {
                    Treasures.manager.updatePool(pool.clearMessages())
                    refreshPool(player, pool.id)
                } else startInput(player, PoolMessageInput(player, pool.id))
            },
            "add-item" to action("add-item", TreasureConfig.Gui.poolAddItem, "Нажмите — добавить предмет в руке", Material.DIAMOND) {
                val held = player.inventory.itemInMainHand
                if (held.type != Material.AIR) {
                    Treasures.addTreasure(pool.id, Treasure.Item(stack = held.clone()))
                    refreshPool(player, pool.id)
                }
            },
            "add-money" to action("add-money", TreasureConfig.Gui.poolAddMoney, "Нажмите — добавить деньги", Material.GOLD_INGOT) {
                startInput(player, AddMoneyInput(player, pool.id))
            },
            "add-command" to action("add-command", TreasureConfig.Gui.poolAddCommand, "Нажмите — добавить команду", Material.COMMAND_BLOCK) {
                startInput(player, AddCommandInput(player, pool.id))
            },
            "delete" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.TREASURE_POOL, "delete")) { click ->
                if (click.event.isShiftClick) {
                    Treasures.delete(pool.id)
                    player.sendMessage(TextUtil.mm(TreasureConfig.Messages.poolDeleted.replace("%pool%", pool.id)))
                    MainTreasuresGui.open(player)
                }
            },
        )
        ArcMenus.open(
            player,
            ArcMenuSchema.TREASURE_POOL,
            TextUtil.mm(TreasureConfig.Gui.poolTitle.replace("%pool%", pool.id), true),
            elements = controls,
            regions = mapOf(ArcMenuSchema.TREASURE_ENTRIES to entries),
        )
    }

    private fun action(id: String, name: String, hint: String, material: Material, action: () -> Unit) =
        ArcMenus.entry(ArcMenus.item(
            ArcMenuSchema.TREASURE_POOL,
            id,
            values("name" to name, "action" to hint),
        ).withType(material)) { action() }

    private fun startInput(player: Player, input: Inputable) {
        TitleInput(player, input, 0)
        player.closeInventory()
    }

    private fun refreshPool(player: Player, poolId: String) {
        Treasures.getPool(poolId)?.let { open(player, it) }
    }

    private class PoolMessageInput(private val player: Player, private val poolId: String) : Inputable {
        override fun setParameter(n: Int, s: String) {
            if (n == 0 && s.isNotBlank()) {
                Treasures.getPool(poolId)?.let { Treasures.manager.updatePool(it.addMessage(TreasureMessage.chat(s))) }
            }
        }
        override fun proceed() = refresh()
        override fun satisfy(input: String, id: Int) = true
        override fun denyMessage(input: String, id: Int) = TextUtil.mm(TreasureConfig.Input.invalidMessage)
        override fun startMessage(id: Int) = TextUtil.mm(TreasureConfig.Input.inputMessage)
        private fun refresh() { Treasures.getPool(poolId)?.let { open(player, it) } }
    }

    private class AddMoneyInput(private val player: Player, private val poolId: String) : Inputable {
        override fun setParameter(n: Int, s: String) {
            if (n == 0) parseAmountDouble(s).let { (min, max) -> Treasures.addTreasure(poolId, Treasure.Money(min = min, max = max)) }
        }
        override fun proceed() = refresh()
        override fun satisfy(input: String, id: Int) = id == 0 && parseAmountDouble(input).first >= 0
        override fun denyMessage(input: String, id: Int) = TextUtil.mm(TreasureConfig.Input.invalidMoneyAmount)
        override fun startMessage(id: Int) = TextUtil.mm(TreasureConfig.Input.inputMoneyAmount)
        private fun refresh() { Treasures.getPool(poolId)?.let { open(player, it) } }
    }

    private class AddCommandInput(private val player: Player, private val poolId: String) : Inputable {
        override fun setParameter(n: Int, s: String) { if (n == 0) Treasures.addTreasure(poolId, Treasure.Command(commands = listOf(s))) }
        override fun proceed() = refresh()
        override fun satisfy(input: String, id: Int) = id == 0 && input.isNotBlank()
        override fun denyMessage(input: String, id: Int) = TextUtil.mm(TreasureConfig.Input.invalidCommand)
        override fun startMessage(id: Int) = TextUtil.mm(TreasureConfig.Input.inputCommand)
        private fun refresh() { Treasures.getPool(poolId)?.let { open(player, it) } }
    }

    private fun parseAmountDouble(value: String): Pair<Double, Double> {
        val parts = value.split("-")
        return if (parts.size == 2) (parts[0].toDoubleOrNull() ?: 0.0) to (parts[1].toDoubleOrNull() ?: 0.0)
        else (value.toDoubleOrNull() ?: 0.0).let { it to it }
    }

    private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
        values = pairs.associate { (key, value) -> key to TextUtil.mm(value, true) },
    )
}
