package ru.arc.board.guis

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.board.BoardEntryData
import ru.arc.board.BoardManager
import ru.arc.configs.BoardConfig
import ru.arc.configs.ConfigManager
import ru.arc.gui.gui
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil.mm

/**
 * Factory for creating RateBoardGui.
 */
object RateBoardGuiFactory {
    private val boardConfig get() = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "board.yml")

    /**
     * Creates a rating GUI for the given board entry.
     */
    fun create(
        player: Player,
        entry: BoardEntryData,
    ): ChestGui {
        val hasUpRated = entry.hasRated(player) == 1
        val hasDownRated = entry.hasRated(player) == -1
        val hasReported = entry.hasReported(player)

        return gui(
            title = BoardConfig.rateGuiName,
            rows = 2,
            player = player,
            config = boardConfig,
        ) {
            background()

            staticPane(0, 0, 9, 2) {
                // Up vote button
                item(1, 0) {
                    material(Material.GREEN_STAINED_GLASS_PANE)
                    display("<green>Оценить положительно")
                    lore(emptyList())
                    fromConfig(boardConfig, "rate-menu.up")
                    if (hasUpRated) {
                        display(boardConfig.string("rate-menu.already-rate", "<dark_red>Вы уже поставили эту оценку"))
                    }
                    onClick { click ->
                        handleUpVote(player, entry, click.currentItem!!) {
                            GuiUtils.constructAndShowAsync({ create(player, entry) }, player)
                        }
                    }
                }

                // Down vote button
                item(3, 0) {
                    material(Material.RED_STAINED_GLASS_PANE)
                    display("<red>Оценить отрицательно")
                    lore(emptyList())
                    fromConfig(boardConfig, "rate-menu.down")
                    if (hasDownRated) {
                        display(boardConfig.string("rate-menu.already-rate", "<dark_red>Вы уже поставили эту оценку"))
                    }
                    onClick { click ->
                        handleDownVote(player, entry, click.currentItem!!) {
                            GuiUtils.constructAndShowAsync({ create(player, entry) }, player)
                        }
                    }
                }

                // Report button
                item(7, 0) {
                    material(Material.PURPLE_STAINED_GLASS_PANE)
                    display("<dark_red>Пожаловаться")
                    lore(emptyList())
                    fromConfig(boardConfig, "rate-menu.report")
                    if (hasReported) {
                        display(boardConfig.string("rate-menu.already-report", "<dark_red>Вы уже пожаловались!"))
                    }
                    onClick { click ->
                        handleReport(player, entry, click.currentItem!!) {
                            GuiUtils.constructAndShowAsync({ create(player, entry) }, player)
                        }
                    }
                }

                // Back button
                item(0, 1) {
                    material(Material.BLUE_STAINED_GLASS_PANE)
                    modelData(11013)
                    display("<gray>Назад")
                    onClick {
                        GuiUtils.constructAndShowAsync({ BoardGuiFactory.createForPlayer(player) }, player)
                    }
                }
            }
        }
    }

    // ==================== Vote Handlers ====================

    private fun handleUpVote(
        player: Player,
        entry: BoardEntryData,
        item: org.bukkit.inventory.ItemStack,
        refresh: () -> Unit,
    ) {
        if (!entry.canRate(player)) {
            showTemporaryMessage(item, "rate-menu.cant-rate", refresh)
            return
        }

        if (entry.hasRated(player) == 1) {
            showTemporaryMessage(item, "rate-menu.already-rate", refresh)
            return
        }

        entry.rate(player.name, 1)
        BoardManager.saveEntry(entry)
        showTemporaryMessage(item, "rate-menu.success-rate", refresh, permanent = true)
    }

    private fun handleDownVote(
        player: Player,
        entry: BoardEntryData,
        item: org.bukkit.inventory.ItemStack,
        refresh: () -> Unit,
    ) {
        if (!entry.canRate(player)) {
            showTemporaryMessage(item, "rate-menu.cant-rate", refresh)
            return
        }

        if (entry.hasRated(player) == -1) {
            showTemporaryMessage(item, "rate-menu.already-rate", refresh)
            return
        }

        entry.rate(player.name, -1)
        BoardManager.saveEntry(entry)
        showTemporaryMessage(item, "rate-menu.success-rate", refresh, permanent = true)
    }

    private fun handleReport(
        player: Player,
        entry: BoardEntryData,
        item: org.bukkit.inventory.ItemStack,
        refresh: () -> Unit,
    ) {
        if (!entry.canRate(player)) {
            showTemporaryMessage(item, "rate-menu.cant-rate", refresh)
            return
        }

        if (entry.hasReported(player)) {
            showTemporaryMessage(item, "rate-menu.already-report", refresh)
            return
        }

        entry.report(player.name)
        BoardManager.saveEntry(entry)
        showTemporaryMessage(item, "rate-menu.success-report", refresh)
    }

    private fun showTemporaryMessage(
        item: org.bukkit.inventory.ItemStack,
        configKey: String,
        refresh: () -> Unit,
        permanent: Boolean = false,
    ) {
        val ticks = if (permanent) -1L else 60L
        GuiUtils.temporaryChange(
            item,
            mm(boardConfig.string(configKey, configKey), true),
            null,
            ticks,
            refresh,
        )
    }
}
