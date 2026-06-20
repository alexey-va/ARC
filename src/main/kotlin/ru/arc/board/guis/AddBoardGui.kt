package ru.arc.board.guis

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.TitleInput
import ru.arc.ai.GPTManager
import ru.arc.ai.ModerationResponse
import ru.arc.board.BoardEntryData
import ru.arc.board.BoardEntryType
import ru.arc.board.BoardManager
import ru.arc.board.ItemIcon
import ru.arc.configs.BoardConfig
import ru.arc.core.modules.EconomyModule
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm
import ru.arc.util.TextUtil.strip
import ru.arc.util.fromConfig
import ru.arc.util.guiItem
import ru.arc.util.guiSkull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AddBoardGui(
    private val player: Player,
    private val entry: BoardEntryData? = null,
) : ChestGui(
    2,
    TextHolder.deserialize(if (entry == null) BoardConfig.createEntryGuiName else BoardConfig.editEntryGuiName)
), Inputable {

    companion object {
        private val defaultBossBarColors = mapOf(
            "blue" to "<blue>Синий</blue>",
            "red" to "<red>Красный</red>",
            "green" to "<green>Зелёный</green>",
            "pink" to "<light_purple>Розовый</light_purple>",
            "purple" to "<purple>Фиолетовый</purple>",
            "white" to "<white>Белый</white>",
            "yellow" to "<yellow>Жёлтый</yellow>",
        )
    }

    @JvmField var title: String? = null
    @JvmField var description: String? = null
    private var icon: ItemIcon = ItemIcon.of(player.uniqueId)
    private var type: BoardEntryType = BoardEntryType.INFO
    private var color: BarColor = BarColor.YELLOW
    private var confirmDelete = false

    private lateinit var descriptionItem: GuiItem
    private lateinit var bossBarColorItem: GuiItem
    private lateinit var shortNameItem: GuiItem
    private lateinit var iconItem: GuiItem
    private lateinit var typeItem: GuiItem
    private lateinit var publishItem: GuiItem
    private var deleteItem: GuiItem? = null

    init {
        if (entry != null) {
            title = entry.title
            description = entry.text
            icon = entry.icon
            type = entry.type
            color = entry.color
        }

        val pane = StaticPane(9, 2)
        setupBackground()

        publishItem = if (entry != null) editItem() else publishItem()
        if (entry != null) deleteItem = deleteItem()

        descriptionItem = descriptionItem()
        bossBarColorItem = bossBarColor()
        shortNameItem = shortNameItem()
        iconItem = iconItem()
        typeItem = typeItem()

        pane.addItem(shortNameItem, 1, 0)
        pane.addItem(descriptionItem, 3, 0)
        pane.addItem(bossBarColorItem, 4, 0)
        pane.addItem(iconItem, 5, 0)
        pane.addItem(typeItem, 7, 0)
        pane.addItem(publishItem, 8, 1)
        deleteItem?.let { pane.addItem(it, 4, 1) }
        pane.addItem(backItem(), 0, 1)

        this.addPane(Slot.fromXY(0, 0), pane)
    }

    override fun proceed() {
        shortNameItem.setItem(shortNameItem().item)
        descriptionItem.setItem(descriptionItem().item)
        this.update()
        this.show(player)
    }

    override fun satisfy(input: String, id: Int): Boolean {
        if (id == 0) return input.length <= BoardConfig.shortNameLength
        return true
    }

    override fun denyMessage(input: String, id: Int): Component =
        TextUtil.mm("<red>Длина не может превышать ${BoardConfig.shortNameLength} символов!")

    override fun startMessage(id: Int): Component = when (id) {
        0 -> TextUtil.mm("<gray>> <green>Введите короткое название")
        else -> TextUtil.mm("<gray>> <green>Введите комментарий")
    }

    override fun setParameter(n: Int, s: String) {
        if (n == 0) title = s
        else if (n == 1) description = s
    }

    private fun backItem(): GuiItem = guiItem(Material.BLUE_STAINED_GLASS_PANE) {
        onClick { click ->
            click.isCancelled = true
            GuiUtils.constructAndShowAsync({ BoardGuiFactory.createForPlayer(player) }, click.whoClicked)
        }
        display("<gray>Назад")
        modelData(11013)
        fromConfig(BoardConfig.config(), "board-menu.back")
    }

    private fun shortNameItem(): GuiItem {
        val shortNameResolver = TagResolver.builder()
            .resolver(TagResolver.resolver("short_name", Tag.inserting(Component.text(title ?: "Нету"))))
            .resolver(TagResolver.resolver("short_name_length", Tag.inserting(Component.text(BoardConfig.shortNameLength.toString()))))
            .build()
        val configPath = if (title == null) "add-menu.empty.short-name" else "add-menu.full.short-name"

        return guiItem(Material.FLOWER_BANNER_PATTERN) {
            onClick { click ->
                click.isCancelled = true
                TitleInput(player, this@AddBoardGui, 0)
                click.whoClicked.closeInventory()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            tagResolver(shortNameResolver)
            if (title == null) {
                display("<green>Короткое название <gray>(макс. длина <short_name_length>)")
                lore(listOf("<gray>Нажмите чтобы установить"))
            } else {
                display("<short_name>")
                lore(listOf("<gray>Нажмите чтобы поменять"))
            }
            fromConfig(BoardConfig.config(), configPath)
        }
    }

    private fun descriptionItem(): GuiItem {
        val descriptionResolver = TagResolver.resolver("description", Tag.inserting(Component.text(description ?: "Нету")))
        val configPath = if (description == null) "add-menu.empty.description" else "add-menu.full.description"

        return guiItem(Material.PAPER) {
            onClick { click ->
                click.isCancelled = true
                TitleInput(player, this@AddBoardGui, 1)
                click.whoClicked.closeInventory()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            tagResolver(descriptionResolver)
            display("<green>Комментарий")
            if (description == null) {
                lore(listOf("<gray>Нажмите чтобы установить"))
            } else {
                lore(listOf("<description>"))
            }
            fromConfig(BoardConfig.config(), configPath)
            if (description != null) {
                val lore = mutableListOf<Component>()
                for (s in BoardConfig.config().stringList("$configPath.lore", listOf("<description>"))) {
                    if (s.contains("<description>")) {
                        lore.addAll(BoardEntryData.description(TagResolver.standard(), description))
                        continue
                    }
                    lore.add(MiniMessage.miniMessage().deserialize(s, descriptionResolver))
                }
                loreComponents(lore)
            }
        }
    }

    private fun bossBarColor(): GuiItem {
        val colors: Map<String, String> = BoardConfig.config().map("boss-bar-colors", defaultBossBarColors)
        val colorStr = colors[color.name.lowercase()] ?: color.name
        val material = Material.getMaterial("${color.name.uppercase()}_DYE")
        val colorResolver = TagResolver.resolver("color", Tag.inserting(TextUtil.mm(colorStr, true)))
        return guiItem(material ?: Material.YELLOW_DYE) {
            onClick { click ->
                click.isCancelled = true
                val colorList: Map<String, String> = BoardConfig.config().map("boss-bar-colors", defaultBossBarColors)
                val barColors = colorList.keys.mapNotNull { s ->
                    try { BarColor.valueOf(s.uppercase()) } catch (e: IllegalArgumentException) {
                        error("Error while parsing boss bar color", e)
                        null
                    }
                }
                this@AddBoardGui.color = barColors[(barColors.indexOf(this@AddBoardGui.color) + 1) % barColors.size]
                updateBossBarItem()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            tagResolver(colorResolver)
            display("<green>Цвет")
            lore(listOf("<gray>Цвет панели: <white><color>"))
            fromConfig(BoardConfig.config(), "add-menu.full.boss-bar-color")
        }
    }

    private fun iconItem(): GuiItem {
        val onIconClick: (InventoryClickEvent) -> Unit = { click ->
            click.isCancelled = true
            val st: ItemStack = click.cursor
            icon = if (st.type == Material.AIR) ItemIcon.of(player.uniqueId) else ItemIcon.of(st)
            updateIcon()
        }
        return if (icon != null) {
            guiItem(icon.stack()) {
                onClick(onIconClick)
                display("<green>Иконка")
                lore(listOf("<gray>Перетащите чтобы установить"))
                fromConfig(BoardConfig.config(), "add-menu.full.icon")
            }
        } else {
            guiSkull(player) {
                onClick(onIconClick)
                display("<green>Иконка")
                lore(listOf("<gray>Перетащите чтобы установить"))
                fromConfig(BoardConfig.config(), "add-menu.empty.icon")
            }
        }
    }

    private fun updateIcon() {
        iconItem.setItem(iconItem().item)
        this.update()
    }

    private fun updateBossBarItem() {
        val item = bossBarColor().item
        bossBarColorItem.setItem(item)
        this.update()
    }

    private fun typeItem(): GuiItem = guiItem(type.icon) {
        onClick { click ->
            click.isCancelled = true
            type = when (type) {
                BoardEntryType.BUY -> BoardEntryType.INFO
                BoardEntryType.INFO -> BoardEntryType.LOOKING_FOR
                BoardEntryType.LOOKING_FOR -> BoardEntryType.SELL
                BoardEntryType.SELL -> BoardEntryType.BUY
            }
            updateType()
        }
        tagResolver(TagResolver.resolver("type", Tag.inserting(type.displayName)))
        display("<type>")
        lore(listOf("<green>Тип сообщения", "<gray>Нажмите чтобы поменять"))
        fromConfig(BoardConfig.config(), "add-menu.full.type")
    }

    private fun updateType() {
        typeItem.setItem(typeItem().item)
        this.update()
    }

    private fun publishItem(): GuiItem = guiItem(Material.GREEN_STAINED_GLASS_PANE) {
        onClick { click ->
            click.isCancelled = true
            try {
                val econ = EconomyModule.getEconomy()
                if (econ == null || !econ.has(player, BoardConfig.editCost)) {
                    notEnoughMoneyDisplay(publishItem)
                    return@onClick
                }
                if (title == null) {
                    val lore = mutableListOf<Component>()
                    lore.add(Component.text("Короткое название не установлено", NamedTextColor.GRAY))
                    GuiUtils.temporaryChange(publishItem.item, Component.text("Остались незаполненные поля", NamedTextColor.RED), lore, 60L, ::update)
                    update()
                    return@onClick
                }
                val currentTitle = title ?: return@onClick
                GPTManager.moderationResponse("$currentTitle\n${description ?: ""}").thenAccept { moder ->
                    if (moder.isPresent) {
                        val response = moder.get()
                        if (response.message == ModerationResponse.BAD) {
                            GuiUtils.temporaryChange(publishItem.item,
                                Component.text("Ваш текст не прошёл модерацию", NamedTextColor.RED),
                                TextUtil.splitLoreString(response.comment, 40, 0).map { mm(it, true) },
                                60L, ::update)
                            return@thenAccept
                        }
                    }
                    if (!takeMoney(BoardConfig.publishCost)) {
                        notEnoughMoneyDisplay(publishItem)
                        return@thenAccept
                    }
                    val boardEntry = BoardEntryData(
                        UUID.randomUUID(), player.uniqueId, player.name,
                        type, description ?: "", currentTitle, icon, color,
                        System.currentTimeMillis(), System.currentTimeMillis(),
                        ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet()
                    )
                    BoardManager.addEntry(boardEntry)
                    player.sendMessage(TextUtil.mm(BoardConfig.getString("add-menu.published-successfully")))
                }
            } catch (e: Exception) {
                error("Error while publishing board entry", e)
                player.sendMessage(TextUtil.error())
                click.whoClicked.closeInventory()
            }
            GuiUtils.constructAndShowAsync({ BoardGuiFactory.createForPlayer(player) }, click.whoClicked)
        }
        modelData(11007)
        display("<gray>Опубликовать")
        lore(emptyList())
        fromConfig(BoardConfig.config(), "add-menu.publish")
    }

    private fun editItem(): GuiItem = guiItem(Material.GREEN_STAINED_GLASS_PANE) {
        onClick { click ->
            click.isCancelled = true
            try {
                val econ = EconomyModule.getEconomy()
                if (econ == null || !econ.has(player, BoardConfig.editCost)) {
                    notEnoughMoneyDisplay(publishItem)
                    return@onClick
                }
                if (title == null) {
                    val lore = mutableListOf<Component>()
                    lore.add(Component.text("Короткое название не установлено", NamedTextColor.GRAY))
                    GuiUtils.temporaryChange(publishItem.item, Component.text("Остались незаполненные поля", NamedTextColor.RED), lore, 60L, ::update)
                    update()
                    return@onClick
                }
                if (!takeMoney(BoardConfig.editCost)) return@onClick
                val currentTitle = title ?: return@onClick
                GPTManager.moderationResponse("$currentTitle\n${description ?: ""}").thenAccept { moder ->
                    if (moder.isPresent) {
                        val response = moder.get()
                        if (response.message == ModerationResponse.BAD) {
                            GuiUtils.temporaryChange(publishItem.item,
                                Component.text("Ваш текст не прошёл модерацию", NamedTextColor.RED),
                                TextUtil.splitLoreString(response.comment, 40, 0).map { mm(it, true) },
                                60L, ::update)
                            return@thenAccept
                        }
                    }
                    val e = entry ?: return@thenAccept
                    e.changeText(description ?: "")
                    e.changeTitle(currentTitle)
                    e.changeIcon(icon)
                    e.changeType(type)
                    e.changeColor(color)
                    BoardManager.saveEntry(e)
                    player.sendMessage(TextUtil.mm(BoardConfig.getString("add-menu.edited-successfully")))
                    GuiUtils.constructAndShowAsync({ BoardGuiFactory.createForPlayer(player) }, click.whoClicked)
                }
            } catch (e: Exception) {
                ru.arc.util.Logging.error("Error in editItem", e)
                player.sendMessage(TextUtil.error())
                click.whoClicked.closeInventory()
            }
        }
        modelData(11007)
        display("<gray>Изменить")
        lore(listOf("<gray>Цена: "))
        tagResolver(TagResolver.resolver("cost", Tag.inserting(strip(Component.text(TextUtil.formatAmount(BoardConfig.editCost))) ?: Component.text(TextUtil.formatAmount(BoardConfig.editCost)))))
        fromConfig(BoardConfig.config(), "add-menu.edit")
    }

    private fun takeMoney(cost: Double): Boolean {
        val econ = EconomyModule.getEconomy() ?: return false
        return econ.withdrawPlayer(player, cost).transactionSuccess()
    }

    private fun notEnoughMoneyDisplay(guiItem: GuiItem) {
        GuiUtils.temporaryChange(guiItem.item,
            MiniMessage.miniMessage().deserialize(BoardConfig.getString("not-enough-money")),
            null, 60L, ::update)
        update()
    }

    private fun deleteItem(): GuiItem = guiItem(Material.RED_STAINED_GLASS_PANE) {
        onClick { click ->
            click.isCancelled = true
            if (confirmDelete) {
                entry?.let { BoardManager.deleteEntry(it) }
                GuiUtils.constructAndShowAsync({ BoardGuiFactory.createForPlayer(player) }, click.whoClicked)
            } else {
                confirmDelete = true
                GuiUtils.temporaryChange(deleteItem!!.item,
                    MiniMessage.miniMessage().deserialize(BoardConfig.getString("add-menu.confirm-delete")),
                    null, 100L) {
                    this@AddBoardGui.update()
                    this@AddBoardGui.confirmDelete = false
                }
                update()
            }
        }
        modelData(11002)
        display("<red>Удалить")
        lore(emptyList())
        fromConfig(BoardConfig.config(), "add-menu.delete")
    }

    private fun setupBackground() {
        val pane = OutlinePane(9, 2, Pane.Priority.LOWEST)
        pane.addItem(GuiUtils.background())
        pane.setRepeat(true)
        this.addPane(Slot.fromXY(0, 0), pane)
    }
}
