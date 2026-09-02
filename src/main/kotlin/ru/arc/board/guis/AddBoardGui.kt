package ru.arc.board.guis

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.TitleInput
import ru.arc.ai.GPTManager
import ru.arc.ai.ModerResponse
import ru.arc.ai.ModerationResponse
import ru.arc.board.BoardEntryData
import ru.arc.board.BoardEntryType
import ru.arc.board.BoardManager
import ru.arc.board.ItemIcon
import ru.arc.config.BoardConfig
import ru.arc.core.Tasks
import ru.arc.core.modules.EconomyModule
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AddBoardGui private constructor(
    private val player: Player,
    private val entry: BoardEntryData? = null,
) : Inputable {
    @JvmField var title: String? = entry?.title
    @JvmField var description: String? = entry?.text
    private var icon = entry?.icon ?: ItemIcon.of(player.uniqueId)
    private var type = entry?.type ?: BoardEntryType.INFO
    private var color = entry?.color ?: BarColor.YELLOW
    private var confirmDelete = false
    private var submissionInProgress = false

    fun open() {
        val edit = entry != null
        val elements = buildMap {
            put("title", ArcMenus.entry(field(
                name = "<green>Короткое название",
                value = title ?: "не задано",
                action = "Нажмите — изменить (до ${BoardConfig.shortNameLength} символов)",
                material = Material.FLOWER_BANNER_PATTERN,
            )) { input(0) })
            val detailRows = description?.let { TextUtil.splitLoreString(it, 40, 0) }.orEmpty()
            put("description", ArcMenus.entry(field(
                name = "<green>Комментарий",
                value = if (detailRows.isEmpty()) "не задан" else "",
                action = "Нажмите — изменить",
                material = Material.PAPER,
                details = detailRows,
            )) { input(1) })
            val colorLabel = BoardConfig.config().map("boss-bar-colors", DEFAULT_COLORS)[color.name.lowercase()] ?: color.name
            put("color", ArcMenus.entry(ArcMenus.item(
                ArcMenuSchema.BOARD_EDIT,
                "color",
                values("color" to colorLabel),
            ).withType(Material.matchMaterial("${color.name}_DYE") ?: Material.YELLOW_DYE)) {
                val colors = BoardConfig.config().map("boss-bar-colors", DEFAULT_COLORS).keys.mapNotNull {
                    runCatching { BarColor.valueOf(it.uppercase()) }.getOrNull()
                }
                if (colors.isNotEmpty()) color = colors[(colors.indexOf(color).coerceAtLeast(0) + 1) % colors.size]
                open()
            })
            val iconPresentation = ArcMenus.item(ArcMenuSchema.BOARD_EDIT, "icon")
            put("icon", ArcMenus.entry(applyPresentation(icon.stack(), iconPresentation)) { clicker ->
                val cursor = clicker.itemOnCursor
                icon = if (cursor.type == Material.AIR) ItemIcon.of(player.uniqueId) else ItemIcon.of(cursor.clone())
                open()
            })
            put("type", ArcMenus.entry(ArcMenus.item(
                ArcMenuSchema.BOARD_EDIT,
                "type",
                PaperMenuItemRenderContext(values = mapOf("type" to type.displayName)),
            ).withType(type.icon)) {
                type = when (type) {
                    BoardEntryType.BUY -> BoardEntryType.INFO
                    BoardEntryType.INFO -> BoardEntryType.LOOKING_FOR
                    BoardEntryType.LOOKING_FOR -> BoardEntryType.SELL
                    BoardEntryType.SELL -> BoardEntryType.BUY
                }
                open()
            })
            put("back", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.BOARD_EDIT, "back")) { BoardGuiFactory.open(it) })
            if (edit) {
                put("delete", ArcMenus.entry(ArcMenus.item(
                    ArcMenuSchema.BOARD_EDIT,
                    "delete",
                    PaperMenuItemRenderContext(flags = if (confirmDelete) setOf("confirm") else emptySet()),
                )) {
                    if (confirmDelete) {
                        BoardManager.deleteEntry(checkNotNull(entry))
                        BoardGuiFactory.open(player)
                    } else {
                        confirmDelete = true
                        open()
                    }
                })
            }
            put("publish", ArcMenus.entry(ArcMenus.item(
                ArcMenuSchema.BOARD_EDIT,
                "publish",
                values(
                    "action" to if (edit) "Сохранить изменения" else "Опубликовать",
                    "cost" to TextUtil.formatAmount(if (edit) BoardConfig.editCost else BoardConfig.publishCost),
                ),
            )) { submit() })
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.BOARD_EDIT,
            TextUtil.mm(if (edit) BoardConfig.editEntryGuiName else BoardConfig.createEntryGuiName, true),
            elements = elements,
        )
    }

    private fun field(name: String, value: String, action: String, material: Material, details: List<String> = emptyList()): ItemStack =
        ArcMenus.item(
            "board-edit-field",
            PaperMenuItemRenderContext(
                values = mapOf(
                    "name" to TextUtil.mm(name, true),
                    "value" to Component.text(value),
                    "action" to Component.text(action),
                ),
                repeats = mapOf("details" to details.map { mapOf("line" to Component.text(it)) }),
            ),
        ).withType(material)

    private fun input(id: Int) {
        TitleInput(player, this, id)
        player.closeInventory()
    }

    override fun proceed() = open()

    override fun satisfy(input: String, id: Int): Boolean = id != 0 || input.length <= BoardConfig.shortNameLength

    override fun denyMessage(input: String, id: Int): Component =
        TextUtil.mm("<red>Длина не может превышать ${BoardConfig.shortNameLength} символов!")

    override fun startMessage(id: Int): Component = TextUtil.mm(
        if (id == 0) "<gray>> <green>Введите короткое название" else "<gray>> <green>Введите комментарий",
    )

    override fun setParameter(n: Int, s: String) {
        if (n == 0) title = s else if (n == 1) description = s
    }

    private fun submit() {
        if (submissionInProgress) return
        val currentTitle = title
        if (currentTitle.isNullOrBlank()) {
            player.sendActionBar(TextUtil.mm("<red>Сначала задайте короткое название"))
            return
        }
        val cost = if (entry == null) BoardConfig.publishCost else BoardConfig.editCost
        val economy = EconomyModule.getEconomy()
        if (economy == null || !economy.has(player, cost)) {
            player.sendActionBar(TextUtil.mm(BoardConfig.getString("not-enough-money"), true))
            return
        }
        moderateAndRun(currentTitle) {
            if (!takeMoney(cost)) {
                player.sendActionBar(TextUtil.mm(BoardConfig.getString("not-enough-money"), true))
                return@moderateAndRun
            }
            val current = entry
            if (current == null) {
                BoardManager.addEntry(BoardEntryData(
                    UUID.randomUUID(), player.uniqueId, player.name, type, description.orEmpty(), currentTitle, icon, color,
                    System.currentTimeMillis(), System.currentTimeMillis(), ConcurrentHashMap.newKeySet(),
                    ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(),
                ))
                player.sendMessage(TextUtil.mm(BoardConfig.getString("add-menu.published-successfully")))
            } else {
                current.changeText(description.orEmpty())
                current.changeTitle(currentTitle)
                current.changeIcon(icon)
                current.changeType(type)
                current.changeColor(color)
                BoardManager.saveEntry(current)
                player.sendMessage(TextUtil.mm(BoardConfig.getString("add-menu.edited-successfully")))
            }
            BoardGuiFactory.open(player)
        }
    }

    private fun moderateAndRun(currentTitle: String, action: () -> Unit) {
        submissionInProgress = true
        GPTManager.moderationResponse("$currentTitle\n${description.orEmpty()}").thenAccept { moderation ->
            Tasks.scheduler.runSync(Runnable {
                try {
                    when (boardModerationDecision(moderation)) {
                        BoardModerationDecision.ALLOW -> action()
                        BoardModerationDecision.REJECT -> player.sendActionBar(Component.text("Ваш текст не прошёл модерацию"))
                        BoardModerationDecision.UNAVAILABLE -> player.sendActionBar(Component.text("Модерация временно недоступна"))
                    }
                } catch (failure: Exception) {
                    error("Error while saving board entry", failure)
                    player.sendMessage(TextUtil.error())
                } finally {
                    submissionInProgress = false
                }
            })
        }
    }

    private fun takeMoney(cost: Double): Boolean =
        EconomyModule.getEconomy()?.withdrawPlayer(player, cost)?.transactionSuccess() == true

    private fun applyPresentation(base: ItemStack, presentation: ItemStack): ItemStack = base.clone().also { target ->
        val source = presentation.itemMeta
        target.editMeta { meta ->
            meta.displayName(source.displayName())
            meta.lore(source.lore())
        }
    }

    private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
        values = pairs.associate { (key, value) -> key to TextUtil.mm(value, true) },
    )

    companion object {
        private val DEFAULT_COLORS = mapOf(
            "blue" to "<blue>Синий", "red" to "<red>Красный", "green" to "<green>Зелёный",
            "pink" to "<light_purple>Розовый", "purple" to "<purple>Фиолетовый", "white" to "<white>Белый",
            "yellow" to "<yellow>Жёлтый",
        )

        fun open(player: Player, entry: BoardEntryData? = null) = AddBoardGui(player, entry).open()
    }
}

internal enum class BoardModerationDecision { ALLOW, REJECT, UNAVAILABLE }

internal fun boardModerationDecision(response: ModerResponse?): BoardModerationDecision = when (response?.message) {
    ModerationResponse.OK -> BoardModerationDecision.ALLOW
    ModerationResponse.BAD -> BoardModerationDecision.REJECT
    null -> BoardModerationDecision.UNAVAILABLE
}
