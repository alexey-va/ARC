package ru.arc.board.guis

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.board.BoardActionResult
import ru.arc.board.BoardEntryData
import ru.arc.board.BoardManager
import ru.arc.config.BoardConfig
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuItemRenderContext

object RateBoardGuiFactory {
    private val config get() = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "board.yml")

    fun open(player: Player, entry: BoardEntryData) {
        ArcMenus.open(
            player,
            ArcMenuSchema.BOARD_RATE,
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(BoardConfig.rateGuiName),
            elements = mapOf(
                "up" to action("<green>Оценить положительно", Material.GREEN_STAINED_GLASS_PANE, entry.hasRated(player) == 1) {
                    handle(player, entry.tryRate(player, 1), "rate-menu.cant-rate", "rate-menu.already-rate", "rate-menu.success-rate") { BoardManager.saveEntry(entry) }
                    open(player, entry)
                },
                "down" to action("<red>Оценить отрицательно", Material.RED_STAINED_GLASS_PANE, entry.hasRated(player) == -1) {
                    handle(player, entry.tryRate(player, -1), "rate-menu.cant-rate", "rate-menu.already-rate", "rate-menu.success-rate") { BoardManager.saveEntry(entry) }
                    open(player, entry)
                },
                "report" to action("<dark_red>Пожаловаться", Material.PURPLE_STAINED_GLASS_PANE, entry.hasReported(player)) {
                    handle(player, entry.tryReport(player), "rate-menu.cant-rate", "rate-menu.already-report", "rate-menu.success-report") { BoardManager.saveEntry(entry) }
                    open(player, entry)
                },
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.BOARD_RATE, "back")) { BoardGuiFactory.open(it) },
            ),
        )
    }

    private fun action(name: String, material: Material, applied: Boolean, block: () -> Unit) = ArcMenus.entry(
        ArcMenus.item(
            "board-rate-action",
            PaperMenuItemRenderContext(
                values = mapOf("name" to net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(name)),
                flags = if (applied) setOf("applied") else emptySet(),
            ),
        ).withType(material),
    ) { block() }

    private fun handle(
        player: Player,
        result: BoardActionResult,
        notAllowed: String,
        already: String,
        success: String,
        save: () -> Unit,
    ) {
        val key = when (result) {
            BoardActionResult.NOT_ALLOWED -> notAllowed
            BoardActionResult.ALREADY_APPLIED -> already
            BoardActionResult.APPLIED -> success
        }
        if (result == BoardActionResult.APPLIED) save()
        player.sendActionBar(config.component(key, "<gray>$key"))
    }
}
