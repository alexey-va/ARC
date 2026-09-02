package ru.arc.board.guis

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.SkullMeta
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
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.gui.hasBalance
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.strip

object BoardGuiFactory {
    private val config: Config by lazy { ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/board.yml") }

    fun open(player: Player) {
        val contracts = ContractBoardCards.current()
        ContractBoardTelemetry.recordOpen(contracts)
        val entries = contracts.map { contractEntry(player, it) } + BoardManager.items().map { boardEntry(player, it) }
        val publish = ArcMenus.item(
            ArcMenuSchema.BOARD,
            "publish",
            values("cost" to formatAmount(BoardConfig.publishCost)),
        )
        (publish.itemMeta as? SkullMeta)?.let { meta ->
            meta.owningPlayer = Bukkit.getOfflinePlayer(player.uniqueId)
            publish.itemMeta = meta
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.BOARD,
            TextUtil.mm(BoardConfig.boardGuiName, true),
            elements = mapOf(
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.BOARD, "back")) {
                    it.closeInventory()
                    it.performCommand(BoardConfig.mainMenuBackCommand)
                },
                "previous" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.BOARD, "previous")) { it.session.previousPage() },
                "next" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.BOARD, "next")) { it.session.nextPage() },
                "publish" to ArcMenus.entry(publish) {
                    when {
                        !it.hasBalance(BoardConfig.publishCost) -> it.sendActionBar(config.component("not-enough-money", "<red>Недостаточно средств!"))
                        !it.hasPermission("arc.board.publish") -> it.sendMessage(TextUtil.noPermissions())
                        else -> AddBoardGui.open(it)
                    }
                },
            ),
            regions = mapOf(ArcMenuSchema.BOARD_ENTRIES to entries),
        )
    }

    private fun contractEntry(player: Player, card: ContractBoardCard): PaperMenuEntry = when (card) {
        is ContractBoardCard.Empty -> ArcMenus.entry(
            ArcMenus.item("board-contract-empty", PaperMenuItemRenderContext(values = mapOf(
                "state" to card.state,
                "budget" to Component.text(money(card.weeklyBudgetMinor)),
            ))),
            enabled = false,
        )
        is ContractBoardCard.Order -> {
            val view = card.view
            val item = ArcMenus.item("board-contract", PaperMenuItemRenderContext(values = mapOf(
                "name" to Component.text(view.displayName),
                "status" to card.status,
                "item" to Component.text(view.itemKey),
                "accepted" to Component.text(view.acceptedQuantity),
                "reserved" to Component.text(view.reservedQuantity),
                "target" to Component.text(view.targetQuantity),
                "progress" to Component.text(card.progressPercent),
                "remaining" to Component.text(view.remainingQuantity),
                "payout" to Component.text(money(view.payoutMinorPerUnit)),
                "budget" to Component.text(money(card.remainingBudgetMinor)),
                "ends" to Component.text(card.endsAt),
                "action" to card.action,
            ))).withType(card.material)
            ArcMenus.entry(item) { clicker ->
                if (card.canPrepareSubmission) {
                    ContractBoardTelemetry.recordInteraction(view.id, "submit_prompt")
                    clicker.closeInventory()
                    clicker.sendMessage(
                        TextUtil.mm(config.string("contracts.submit-prompt", "<yellow>Нажми сюда, введи количество и отправь команду сдачи."), true)
                            .clickEvent(ClickEvent.suggestCommand("/arc contracts submit ${view.id} ")),
                    )
                } else {
                    ContractBoardTelemetry.recordInteraction(view.id, "unavailable")
                    clicker.sendActionBar(TextUtil.mm(config.string("contracts.unavailable", "<yellow>Сдача предметов пока отключена"), true))
                }
            }
        }
    }

    private fun boardEntry(player: Player, boardItem: BoardItem): PaperMenuEntry =
        ArcMenus.entryWithContext(buildItemStack(boardItem, player)) { click ->
            when {
                click.event.isShiftClick && click.event.isLeftClick -> {
                    if (boardItem.entry.canEdit(player)) AddBoardGui.open(player, boardItem.entry)
                    else player.sendActionBar(config.component("board-menu.cannot-edit", "<red>Вы не можете это редактировать"))
                }
                click.event.isRightClick -> {
                    if (!boardItem.entry.canEdit(player) || player.hasPermission("arc.board.admin")) {
                        RateBoardGuiFactory.open(player, boardItem.entry)
                    } else player.sendActionBar(config.component("board-menu.cannot-rate", "<red>Вы не можете это оценить"))
                }
            }
        }

    private fun buildItemStack(boardItem: BoardItem, player: Player) = boardItem.stack.clone().also { result ->
        val meta = result.itemMeta
        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
        if (boardItem.entry.canEdit(player)) lore += BoardConfig.editBottom.map { strip(MiniMessage.miniMessage().deserialize(it)) }
        if (boardItem.entry.canRate(player)) lore += BoardConfig.rateBottom.map { strip(MiniMessage.miniMessage().deserialize(it)) }
        meta.lore(lore)
        result.itemMeta = meta
    }

    private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
        values = pairs.associate { (key, value) -> key to TextUtil.mm(value, true) },
    )

    @JvmStatic fun openForPlayer(player: Player) = open(player)
}
