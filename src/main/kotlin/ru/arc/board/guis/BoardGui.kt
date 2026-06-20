package ru.arc.board.guis

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.board.BoardEntryData
import ru.arc.board.BoardItem
import ru.arc.board.BoardManager
import ru.arc.configs.BoardConfig
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.gui.gui
import ru.arc.gui.hasBalance
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.strip

/**
 * Factory for creating main board GUI.
 * Players can view, rate, and edit entries they own.
 */
object BoardGuiFactory {
    private val config: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/board.yml")
    }

    fun create(player: Player): ChestGui {
        val cfg = config

        return gui(BoardConfig.boardGuiName, 6, player, cfg) {
            // Main area background (light gray)
            contentBackground(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
            // Nav bar background
            navBackground()

            // Board entries
            pagination(0 until 5) {
                items(BoardManager.items()) { boardItem ->
                    stack(buildItemStack(boardItem, player))

                    // Multi-action click handler
                    clicks {
                        shiftLeft { openEditor(player, boardItem.entry, it) }
                        right { openRating(player, boardItem.entry, it) }
                    }
                }
            }

            // Navigation bar
            navBar {
                // Back button
                button(0) {
                    material(Material.BLUE_STAINED_GLASS_PANE)
                    display("<gray>« Назад")
                    lore(emptyList())
                    modelData(11013)
                    fromConfig(cfg, "board-menu.back")

                    onClick { event ->
                        val clicker = event.whoClicked as Player
                        clicker.performCommand(BoardConfig.mainMenuBackCommand)
                    }
                }

                // Publish button (player skull)
                button(8) {
                    skull(player.uniqueId)
                    display("<green>Опубликовать объявление")
                    lore(
                        listOf(
                            "<gray>Стоимость: <gold><cost>",
                            "",
                            "<yellow>Нажмите для создания",
                        ),
                    )
                    tag("cost", formatAmount(BoardConfig.publishCost))
                    modelData(11010)
                    fromConfig(cfg, "board-menu.publish")

                    onClick { event ->
                        val clicker = event.whoClicked as Player

                        // Check balance
                        if (!clicker.hasBalance(BoardConfig.publishCost)) {
                            event.currentItem?.let { item ->
                                GuiUtils.temporaryChange(
                                    item,
                                    MiniMessage.miniMessage().deserialize(
                                        cfg.string("not-enough-money", "<red>Недостаточно средств!"),
                                    ),
                                    null,
                                    60L,
                                ) {}
                            }
                            return@onClick
                        }

                        // Check permission
                        if (clicker.hasPermission("arc.board.publish")) {
                            AddBoardGui(clicker).show(clicker)
                        } else {
                            clicker.sendMessage(TextUtil.noPermissions())
                        }
                    }
                }
            }
        }
    }

    /**
     * Build item stack with appropriate lore based on permissions.
     */
    private fun buildItemStack(
        boardItem: BoardItem,
        player: Player,
    ): org.bukkit.inventory.ItemStack {
        val res = boardItem.stack.clone()
        val meta = boardItem.stack.itemMeta ?: return res

        val lore = meta.lore()?.toMutableList() ?: mutableListOf()

        // Add edit hint if player can edit
        if (boardItem.entry.canEdit(player)) {
            lore.addAll(
                BoardConfig.editBottom
                    .map { MiniMessage.miniMessage().deserialize(it) }
                    .map { strip(it) },
            )
        }

        // Add rate hint if player can rate
        if (boardItem.entry.canRate(player)) {
            lore.addAll(
                BoardConfig.rateBottom
                    .map { MiniMessage.miniMessage().deserialize(it) }
                    .map { strip(it) },
            )
        }

        meta.lore(lore)
        res.itemMeta = meta
        return res
    }

    /**
     * Open editor for board entry.
     */
    private fun openEditor(
        player: Player,
        entry: BoardEntryData,
        event: org.bukkit.event.inventory.InventoryClickEvent,
    ) {
        if (entry.canEdit(player)) {
            AddBoardGui(player, entry).show(player)
        } else {
            event.currentItem?.let { item ->
                GuiUtils.temporaryChange(
                    item,
                    TextUtil.mm(config.string("board-menu.cannot-edit", "<red>Вы не можете это редактировать"), true),
                    null,
                    60L,
                ) {}
            }
        }
    }

    /**
     * Open rating GUI for board entry.
     */
    private fun openRating(
        player: Player,
        entry: BoardEntryData,
        event: org.bukkit.event.inventory.InventoryClickEvent,
    ) {
        if (!entry.canEdit(player) || player.hasPermission("arc.board.admin")) {
            GuiUtils.constructAndShowAsync({ RateBoardGuiFactory.create(player, entry) }, player)
        } else {
            event.currentItem?.let { item ->
                GuiUtils.temporaryChange(
                    item,
                    TextUtil.mm(config.string("board-menu.cannot-rate", "<red>Вы не можете это оценить"), true),
                    null,
                    60L,
                ) {}
            }
        }
    }

    /**
     * For Java compatibility.
     */
    @JvmStatic
    fun createForPlayer(player: Player): ChestGui = create(player)
}
