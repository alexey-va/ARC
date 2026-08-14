package ru.arc.board.guis

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.board.BoardEntryData
import ru.arc.board.BoardItem
import ru.arc.board.BoardManager
import ru.arc.board.ContractBoardCard
import ru.arc.board.ContractBoardCards
import ru.arc.board.ContractBoardTelemetry
import ru.arc.board.money
import ru.arc.config.BoardConfig
import ru.arc.config.Config
import ru.arc.config.ConfigManager
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
        val contractCards = ContractBoardCards.current()
        ContractBoardTelemetry.recordOpen(contractCards)

        return gui(BoardConfig.boardGuiName, 6, player, cfg) {
            // Main area background (light gray)
            contentBackground(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
            // Nav bar background
            navBackground()

            // Board entries
            pagination(0 until 5) {
                items(contractCards) { card ->
                    when (card) {
                        is ContractBoardCard.Order -> buildContractCard(card, cfg)
                        is ContractBoardCard.Empty -> buildEmptyContractCard(card, cfg)
                    }
                }

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

    private fun ru.arc.gui.ItemBuilder.buildContractCard(
        card: ContractBoardCard.Order,
        cfg: Config,
    ) {
        val view = card.view
        material(card.material)
        display("<gold>Заказ сервера: <white><contract_name>")
        lore(
            listOf(
                "<dark_gray>Системное объявление · <status>",
                "",
                "<gray>Ресурс: <white><item_key>",
                "<gray>Сдано: <white><accepted><gray>/<white><target> <dark_gray>(<progress>%)",
                "<gray>В обработке: <white><reserved>",
                "<gray>Осталось: <white><remaining>",
                "<gray>Награда: <green><payout><gray>/шт.",
                "<gray>Остаток бюджета: <green><remaining_budget>",
                "<gray>До: <white><ends_at>",
                "",
                "<action>",
            ),
        )
        tag("contract_name", Component.text(view.displayName))
        tag("status", card.status)
        tag("item_key", Component.text(view.itemKey))
        tag("accepted", Component.text(view.acceptedQuantity.toString()))
        tag("reserved", Component.text(view.reservedQuantity.toString()))
        tag("target", Component.text(view.targetQuantity.toString()))
        tag("progress", Component.text(card.progressPercent))
        tag("remaining", Component.text(view.remainingQuantity.toString()))
        tag("payout", Component.text(money(view.payoutMinorPerUnit)))
        tag("remaining_budget", Component.text(money(card.remainingBudgetMinor)))
        tag("ends_at", Component.text(card.endsAt))
        tag("action", card.action)
        fromConfig(cfg, "contracts.order")

        onClick { event ->
            val clicker = event.whoClicked as? Player ?: return@onClick
            if (card.canPrepareSubmission) {
                ContractBoardTelemetry.recordInteraction(view.id, "submit_prompt")
                clicker.closeInventory()
                val prompt =
                    TextUtil.mm(
                        cfg.string(
                            "contracts.submit-prompt",
                            "<yellow>Нажми сюда, введи количество и отправь команду сдачи.",
                        ),
                        true,
                    ).clickEvent(ClickEvent.suggestCommand("/arc contracts submit ${view.id} "))
                clicker.sendMessage(prompt)
            } else {
                ContractBoardTelemetry.recordInteraction(view.id, "unavailable")
                event.currentItem?.let { item ->
                    GuiUtils.temporaryChange(
                        item,
                        TextUtil.mm(
                            cfg.string(
                                "contracts.unavailable",
                                "<yellow>Сдача предметов пока отключена",
                            ),
                            true,
                        ),
                        null,
                        60L,
                    ) {}
                }
            }
        }
    }

    private fun ru.arc.gui.ItemBuilder.buildEmptyContractCard(
        card: ContractBoardCard.Empty,
        cfg: Config,
    ) {
        material(Material.WRITABLE_BOOK)
        display("<gold>Заказы сервера")
        lore(
            listOf(
                "<gray>Статус: <state>",
                "",
                "<gray>Активных ресурсных заказов пока нет.",
                "<gray>Недельный лимит: <green><weekly_budget>",
                "",
                "<dark_gray>Предметы и деньги не меняются.",
            ),
        )
        tag("state", card.state)
        tag("weekly_budget", Component.text(money(card.weeklyBudgetMinor)))
        fromConfig(cfg, "contracts.empty")
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
