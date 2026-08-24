package ru.arc.itemcatalog

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.gui.GuiDefaults
import ru.arc.gui.GuiItems
import ru.arc.gui.gui
import ru.arc.gui.onBottomClick
import ru.arc.gui.onTopClick
import ru.arc.gui.onTopDrag
import ru.arc.util.GuiUtils
import ru.arc.util.ItemStackFactory
import ru.arc.util.TextUtil
import ru.arc.util.TextUtils
import ru.arc.util.withCustomModelData

class ItemsCatalogGuiController(
    private val settings: ItemsCatalogSettings,
    private val service: ItemsCatalogService,
) {
    fun openRoot(player: Player, page: Int = 0) {
        val snapshot = service.currentSnapshot()
        if (snapshot == null) {
            player.sendMessage(TextUtil.mm(settings.loadingMessage, true))
            return
        }
        val entries = rootEntries(player, snapshot)
        if (entries.isEmpty()) {
            player.sendMessage(TextUtil.mm(settings.unavailableMessage, true))
            return
        }
        show(
            player,
            pagedMenu(
                player = player,
                title = settings.config.string("gui.root-title", "<dark_gray><bold>Каталог предметов"),
                entries = entries,
                requestedPage = page,
                back = player::closeInventory,
                reopen = { nextPage -> openRoot(player, nextPage) },
                render = { entry, rootPage -> rootEntryItem(player, snapshot, entry, rootPage) },
            ),
        )
    }

    private fun openGroup(
        player: Player,
        groupId: String,
        page: Int,
        rootPage: Int,
    ) {
        val snapshot = service.currentSnapshot() ?: return openRoot(player, rootPage)
        val group = snapshot.groups.firstOrNull { it.definition.id == groupId } ?: return openRoot(player, rootPage)
        val categories = group.categories.filter { player.canSee(it.permissions) }
        if (categories.isEmpty()) return openRoot(player, rootPage)
        val title =
            settings.config.string("gui.group-title", "<dark_gray><bold><group>")
                .replace("<group>", TextUtils.escapeMM(group.definition.displayName))
        show(
            player,
            pagedMenu(
                player = player,
                title = title,
                entries = categories,
                requestedPage = page,
                back = { openRoot(player, rootPage) },
                reopen = { nextPage -> openGroup(player, groupId, nextPage, rootPage) },
                render = { category, groupPage ->
                    categoryItem(category) {
                        openCategory(
                            player,
                            category.id,
                            0,
                            BackTarget.Group(groupId, groupPage, rootPage),
                        )
                    }
                },
            ),
        )
    }

    private fun openCategory(
        player: Player,
        categoryId: String,
        page: Int,
        backTarget: BackTarget,
    ) {
        val snapshot = service.currentSnapshot() ?: return openBack(player, backTarget)
        val category = snapshot.allCategories().firstOrNull { it.id == categoryId }
            ?.takeIf { player.canSee(it.permissions) }
            ?: return openBack(player, backTarget)
        val title =
            settings.config.string("gui.category-title", "<dark_gray><bold><category>")
                .replace("<category>", TextUtils.escapeMM(category.displayName))
        show(
            player,
            pagedMenu(
                player = player,
                title = title,
                entries = category.itemIds,
                requestedPage = page,
                back = { openBack(player, backTarget) },
                reopen = { nextPage -> openCategory(player, categoryId, nextPage, backTarget) },
                render = { itemId, _ -> previewItem(itemId) },
            ),
        )
    }

    private fun openAll(
        player: Player,
        page: Int,
        rootPage: Int,
    ) {
        val snapshot = service.currentSnapshot() ?: return openRoot(player, rootPage)
        if (!settings.showAll || !player.canSee(settings.allPermission)) return openRoot(player, rootPage)
        show(
            player,
            pagedMenu(
                player = player,
                title = settings.config.string("gui.all-title", "<dark_gray><bold>Все предметы"),
                entries = snapshot.registryItemIds,
                requestedPage = page,
                back = { openRoot(player, rootPage) },
                reopen = { nextPage -> openAll(player, nextPage, rootPage) },
                render = { itemId, _ -> previewItem(itemId) },
            ),
        )
    }

    private fun rootEntries(
        player: Player,
        snapshot: ItemsCatalogSnapshot,
    ): List<RootEntry> =
        buildList {
            if (settings.showAll && player.canSee(settings.allPermission) && snapshot.registryItemIds.isNotEmpty()) {
                add(RootEntry.All)
            }
            snapshot.groups
                .filter { group -> group.categories.any { player.canSee(it.permissions) } }
                .forEach { add(RootEntry.Group(it)) }
            snapshot.ungroupedCategories
                .filter { player.canSee(it.permissions) }
                .forEach { add(RootEntry.Category(it)) }
        }

    private fun rootEntryItem(
        player: Player,
        snapshot: ItemsCatalogSnapshot,
        entry: RootEntry,
        rootPage: Int,
    ): GuiItem =
        when (entry) {
            RootEntry.All -> {
                val stack = styledStack(settings.allIcon)
                decorate(
                    stack,
                    settings.allDisplayName,
                    settings.allDescription.map(::body) +
                        countLore(snapshot.registryItemIds.size, "предметов") +
                        actionLore("открыть все предметы"),
                )
                clickable(stack) { openAll(player, 0, rootPage) }
            }

            is RootEntry.Group -> {
                val visible = entry.value.categories.filter { player.canSee(it.permissions) }
                val itemCount = visible.flatMapTo(linkedSetOf(), CatalogCategory::itemIds).size
                val stack = styledStack(entry.value.definition.icon)
                decorate(
                    stack,
                    entry.value.definition.displayName,
                    entry.value.definition.description.map(::body) +
                        countLore(visible.size, "подкатегорий") +
                        countLore(itemCount, "предметов") +
                        actionLore("выбрать подкатегорию"),
                )
                clickable(stack) { openGroup(player, entry.value.definition.id, 0, rootPage) }
            }

            is RootEntry.Category -> categoryItem(entry.value) {
                openCategory(player, entry.value.id, 0, BackTarget.Root(rootPage))
            }
        }

    private fun categoryItem(
        category: CatalogCategory,
        open: () -> Unit,
    ): GuiItem {
        val stack = category.iconId?.let(::safeItemStack) ?: styledStack(settings.categoryFallbackIcon)
        decorate(
            stack,
            category.displayName,
            countLore(category.itemIds.size, "предметов") + actionLore("открыть категорию"),
        )
        return clickable(stack, open)
    }

    private fun previewItem(namespacedId: String): GuiItem {
        val stack = safeItemStack(namespacedId) ?: unavailableItem(namespacedId)
        stack.amount = 1
        val meta = stack.itemMeta
        if (meta != null) {
            meta.displayName()?.let { meta.displayName(nonItalic(it)) }
            val original = meta.lore().orEmpty().take(MAX_ORIGINAL_LORE_LINES).map(::nonItalic)
            val suffix =
                buildList {
                    if (original.isNotEmpty()) add(Component.empty())
                    add(labelValue("ID", namespacedId))
                    add(muted("Только просмотр"))
                }
            meta.lore(original + suffix)
            stack.itemMeta = meta
        }
        return clickable(stack) {}
    }

    private fun <T> pagedMenu(
        player: Player,
        title: String,
        entries: List<T>,
        requestedPage: Int,
        back: () -> Unit,
        reopen: (Int) -> Unit,
        render: (T, Int) -> GuiItem,
    ): ChestGui {
        val model = catalogPage(entries, requestedPage, PAGE_SIZE)
        val page = model.page
        val pages = model.pages
        val visible = model.entries
        return gui(title, 6, player, settings.config) {
            contentBackground()
            navBackground()
            onTopClick { it.isCancelled = true }
            onBottomClick { it.isCancelled = true }
            onTopDrag { it.isCancelled = true }
            staticPane(width = 9, height = 5) {
                visible.forEachIndexed { index, entry ->
                    item(index % 9, index / 9, render(entry, page))
                }
            }
            navBar {
                back(configKey = "gui.items.back", action = back)
                if (page > 0) {
                    button(GuiDefaults.Slots.prev) {
                        material(GuiDefaults.PrevButton.material)
                        if (GuiDefaults.PrevButton.modelData != 0) modelData(GuiDefaults.PrevButton.modelData)
                        display(GuiDefaults.PrevButton.defaultDisplay)
                        lore(emptyList())
                        fromConfig(settings.config, "gui.items.previous")
                        onClick { reopen(page - 1) }
                    }
                }
                button(4) {
                    val indicator = settings.pageIndicatorIcon
                    material(Material.valueOf(indicator.material))
                    if (indicator.customModelData != 0) modelData(indicator.customModelData)
                    display(accent("Страница ${page + 1} из $pages", bold = false))
                    loreComponents(listOf(muted("Показано ${visible.size} из ${model.totalEntries}")))
                }
                if (page + 1 < pages) {
                    button(GuiDefaults.Slots.next) {
                        material(GuiDefaults.NextButton.material)
                        if (GuiDefaults.NextButton.modelData != 0) modelData(GuiDefaults.NextButton.modelData)
                        display(GuiDefaults.NextButton.defaultDisplay)
                        lore(emptyList())
                        fromConfig(settings.config, "gui.items.next")
                        onClick { reopen(page + 1) }
                    }
                }
            }
        }
    }

    private fun decorate(
        stack: ItemStack,
        name: String,
        lore: List<Component>,
    ) {
        val meta = stack.itemMeta ?: return
        meta.displayName(accent(name))
        meta.lore(lore.map(::nonItalic))
        stack.itemMeta = meta
    }

    private fun styledStack(style: CatalogIconStyle): ItemStack =
        ItemStackFactory.create(Material.valueOf(style.material), 1).also { stack ->
            if (style.customModelData != 0) {
                stack.withCustomModelData(style.customModelData)
            }
        }

    private fun safeItemStack(namespacedId: String): ItemStack? =
        runCatching { service.gateway.itemStack(namespacedId) }.getOrNull()

    private fun unavailableItem(namespacedId: String): ItemStack =
        styledStack(CatalogIconStyle(Material.BARRIER.name)).also {
            decorate(it, "Предмет недоступен", listOf(labelValue("ID", namespacedId)))
        }

    private fun clickable(
        stack: ItemStack,
        action: () -> Unit,
    ): GuiItem =
        GuiItems.create(stack) { event ->
            event.isCancelled = true
            action()
        }

    private fun openBack(player: Player, target: BackTarget) {
        when (target) {
            is BackTarget.Root -> openRoot(player, target.page)
            is BackTarget.Group -> openGroup(player, target.groupId, target.page, target.rootPage)
        }
    }

    private fun show(player: Player, gui: ChestGui) {
        GuiUtils.constructAndShowAsync({ gui }, player)
    }

    private fun countLore(count: Int, noun: String): List<Component> = listOf(labelValue("В каталоге", "$count $noun"))

    private fun actionLore(action: String): List<Component> =
        listOf(Component.empty(), muted("• ").append(accent("Нажмите", bold = false)).append(body(", чтобы $action.")).append(muted(" •")))

    private fun labelValue(label: String, value: String): Component =
        muted("$label: ").append(accent(value, bold = false))

    private fun accent(text: String, bold: Boolean = true): Component =
        Component.text(text, ACCENT)
            .decoration(TextDecoration.BOLD, bold)
            .decoration(TextDecoration.ITALIC, false)

    private fun body(text: String): Component =
        Component.text(text, BODY).decoration(TextDecoration.ITALIC, false)

    private fun muted(text: String): Component =
        Component.text(text, MUTED).decoration(TextDecoration.ITALIC, false)

    private fun nonItalic(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)

    private fun Player.canSee(permission: String?): Boolean = permission.isNullOrBlank() || hasPermission(permission)

    private fun Player.canSee(permissions: Set<String>): Boolean = permissions.all { hasPermission(it) }

    private fun ItemsCatalogSnapshot.allCategories(): List<CatalogCategory> =
        groups.flatMap(CatalogGroup::categories) + ungroupedCategories

    private sealed interface RootEntry {
        data object All : RootEntry

        data class Group(val value: CatalogGroup) : RootEntry

        data class Category(val value: CatalogCategory) : RootEntry
    }

    private sealed interface BackTarget {
        data class Root(val page: Int) : BackTarget

        data class Group(val groupId: String, val page: Int, val rootPage: Int) : BackTarget
    }

    companion object {
        internal const val PAGE_SIZE = 45
        private const val MAX_ORIGINAL_LORE_LINES = 24
        private val ACCENT = TextColor.color(0x92BED8)
        private val BODY = TextColor.color(0xE6FFF3)
        private val MUTED = TextColor.color(0x8C8C8C)
    }
}
