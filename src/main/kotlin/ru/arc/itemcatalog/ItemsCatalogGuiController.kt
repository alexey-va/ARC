package ru.arc.itemcatalog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.ItemStackFactory
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil
import ru.arc.util.TextUtils
import ru.arc.util.withCustomModelData
import java.util.concurrent.atomic.AtomicBoolean

class ItemsCatalogGuiController(
    private val settings: ItemsCatalogSettings,
    private val service: ItemsCatalogService,
) {
    private val active = AtomicBoolean(true)

    fun shutdown() {
        active.set(false)
    }

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
        pagedMenu(
                player = player,
                title = settings.config.string("gui.root-title", "<dark_gray><bold>Каталог предметов"),
                entries = entries,
                requestedPage = page,
                back = player::closeInventory,
                reopen = { nextPage -> openRoot(player, nextPage) },
                render = { entry, rootPage -> rootEntryItem(player, snapshot, entry, rootPage) },
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
        val fallbackAction = itemAction(snapshot, categoryId, backTarget)
        pagedMenu(
                player = player,
                title = title,
                entries = category.itemIds,
                requestedPage = page,
                back = { openBack(player, backTarget) },
                reopen = { nextPage -> openCategory(player, categoryId, nextPage, backTarget) },
                render = { itemId, _ -> previewItem(player, itemId, snapshot, fallbackAction) },
        )
    }

    private fun openAll(
        player: Player,
        page: Int,
        rootPage: Int,
    ) {
        val snapshot = service.currentSnapshot() ?: return openRoot(player, rootPage)
        if (!settings.showAll || !player.canSee(settings.allPermission)) return openRoot(player, rootPage)
        pagedMenu(
                player = player,
                title = settings.config.string("gui.all-title", "<dark_gray><bold>Все предметы"),
                entries = snapshot.registryItemIds,
                requestedPage = page,
                back = { openRoot(player, rootPage) },
                reopen = { nextPage -> openAll(player, nextPage, rootPage) },
                render = { itemId, _ -> previewItem(player, itemId, snapshot, null) },
        )
    }

    private fun rootEntries(
        player: Player,
        snapshot: ItemsCatalogSnapshot,
    ): List<RootEntry> {
        val groups =
            snapshot.groups
                .filter { group -> group.categories.any { player.canSee(it.permissions) } }
                .map(RootEntry::Group)
        val categories =
            snapshot.ungroupedCategories
                .filter { player.canSee(it.permissions) }
                .map(RootEntry::Category)
        val all =
            RootEntry.All.takeIf {
                settings.showAll && player.canSee(settings.allPermission) && snapshot.registryItemIds.isNotEmpty()
            }
        return catalogRootOrder(groups, categories, all)
    }

    private fun rootEntryItem(
        player: Player,
        snapshot: ItemsCatalogSnapshot,
        entry: RootEntry,
        rootPage: Int,
    ): PaperMenuEntry =
        when (entry) {
            RootEntry.All -> {
                val stack = ArcMenus.item(
                    "catalog-root-entry",
                    catalogContext(
                        name = settings.allDisplayName,
                        categories = 0,
                        items = snapshot.registryItemIds.size,
                        action = "Нажмите — открыть все предметы",
                        description = settings.allDescription,
                    ),
                ).withType(Material.valueOf(settings.allIcon.material))
                clickable(stack) { openAll(player, 0, rootPage) }
            }

            is RootEntry.Group -> {
                val visible = entry.value.categories.filter { player.canSee(it.permissions) }
                val itemCount = visible.flatMapTo(linkedSetOf(), CatalogCategory::itemIds).size
                val stack = ArcMenus.item(
                    "catalog-root-entry",
                    catalogContext(
                        name = entry.value.definition.displayName,
                        categories = visible.size,
                        items = itemCount,
                        action = "Нажмите — выбрать подкатегорию",
                        description = entry.value.definition.description,
                    ),
                ).withType(Material.valueOf(entry.value.definition.icon.material))
                clickable(stack) { openGroup(player, entry.value.definition.id, 0, rootPage) }
            }

            is RootEntry.Category -> categoryItem(entry.value) {
                openCategory(player, entry.value.id, 0, BackTarget.Root(rootPage))
            }
        }

    private fun categoryItem(
        category: CatalogCategory,
        open: () -> Unit,
    ): PaperMenuEntry {
        val base = category.iconId?.let(::safeItemStack) ?: styledStack(settings.categoryFallbackIcon)
        val rendered = ArcMenus.item(
            "catalog-category",
            values("name" to category.displayName, "items" to category.itemIds.size.toString(), "action" to "Нажмите — открыть категорию"),
        )
        return clickable(applyPresentation(base, rendered), open)
    }

    private fun previewItem(
        player: Player,
        namespacedId: String,
        snapshot: ItemsCatalogSnapshot,
        fallbackAction: CatalogClickAction?,
    ): PaperMenuEntry {
        val liveStack = safeItemStack(namespacedId)
        val stack = liveStack ?: unavailableItem(namespacedId)
        stack.amount = 1
        val click =
            if (liveStack == null) {
                CatalogItemClick.Unavailable
            } else {
                catalogItemClick(
                    canGive = player.hasPermission(settings.givePermission),
                    hasRecipe = settings.recipeClicksEnabled && namespacedId in snapshot.recipeResultItemIds,
                    fallback = fallbackAction,
                )
            }
        val meta = stack.itemMeta
        val name = meta?.displayName() ?: Component.text(namespacedId)
        val original = meta?.lore().orEmpty().take(MAX_ORIGINAL_LORE_LINES).map(::nonItalic)
        val action = when (click) {
            CatalogItemClick.Give -> "Нажмите — получить 1 предмет"
            CatalogItemClick.Recipe -> "Нажмите — открыть рецепт"
            is CatalogItemClick.Action -> "Нажмите — ${click.value.hint}"
            CatalogItemClick.Unavailable -> "Нет рецепта или действия"
        }
        val rendered = ArcMenus.item(
            "catalog-preview",
            PaperMenuItemRenderContext(
                values = mapOf("name" to name, "id" to Component.text(namespacedId), "action" to Component.text(action)),
                repeats = mapOf("original" to original.map { mapOf("line" to it) }),
            ),
        )
        return clickable(applyPresentation(stack, rendered)) { handleItemClick(player, namespacedId, click) }
    }

    private fun handleItemClick(
        player: Player,
        namespacedId: String,
        click: CatalogItemClick,
    ) {
        if (!active.get()) {
            player.closeInventory()
            sendConfigured(player, settings.actionFailedMessage)
            return
        }
        when (click) {
            CatalogItemClick.Give -> {
                if (player.hasPermission(settings.givePermission)) {
                    giveItem(player, namespacedId)
                } else {
                    sendConfigured(player, settings.actionFailedMessage)
                }
            }
            CatalogItemClick.Recipe -> executePlayerCommand(player, "iarecipe $namespacedId")
            is CatalogItemClick.Action ->
                when (val action = click.value) {
                    is CatalogClickAction.PlayerCommand -> executePlayerCommand(player, action.command)
                }
            CatalogItemClick.Unavailable -> sendConfigured(player, settings.noActionMessage, namespacedId)
        }
    }

    private fun giveItem(player: Player, namespacedId: String) {
        val stack = safeItemStack(namespacedId)
        if (stack == null) {
            sendConfigured(player, settings.unavailableMessage, namespacedId)
            return
        }
        stack.amount = 1
        val leftovers = runCatching { player.inventory.addItem(stack) }.getOrNull()
        when {
            leftovers == null -> sendConfigured(player, settings.unavailableMessage, namespacedId)
            leftovers.isNotEmpty() -> sendConfigured(player, settings.inventoryFullMessage, namespacedId)
            else -> {
                info("Items catalog granted one item: player={} item={}", player.uniqueId, namespacedId)
                sendConfigured(player, settings.givenMessage, namespacedId)
            }
        }
    }

    private fun executePlayerCommand(player: Player, command: String) {
        if (!validCatalogPlayerCommand(command)) {
            sendConfigured(player, settings.actionFailedMessage)
            return
        }
        player.closeInventory()
        val executed = runCatching { player.performCommand(command) }.getOrDefault(false)
        if (!executed) sendConfigured(player, settings.actionFailedMessage)
    }

    private fun sendConfigured(
        player: Player,
        template: String,
        namespacedId: String = "",
    ) {
        val rendered = template.replace("%item%", TextUtils.escapeMM(namespacedId))
        player.sendMessage(TextUtil.mm(rendered, true))
    }

    private fun itemAction(
        snapshot: ItemsCatalogSnapshot,
        categoryId: String,
        backTarget: BackTarget,
    ): CatalogClickAction? =
        settings.categoryOverrides[categoryId]?.itemAction
            ?: (backTarget as? BackTarget.Group)?.let { target ->
                snapshot.groups.firstOrNull { it.definition.id == target.groupId }?.definition?.itemAction
            }

    private fun <T> pagedMenu(
        player: Player,
        title: String,
        entries: List<T>,
        requestedPage: Int,
        back: () -> Unit,
        reopen: (Int) -> Unit,
        render: (T, Int) -> PaperMenuEntry,
    ) {
        val model = catalogPage(entries, requestedPage, PAGE_SIZE)
        val page = model.page
        val pages = model.pages
        val visible = model.entries
        val controls = buildMap {
            put("back", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ITEM_CATALOG, "back")) { back() })
            put(
                "page",
                ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.ITEM_CATALOG,
                        "page",
                        values(
                            "page" to (page + 1).toString(),
                            "pages" to pages.toString(),
                            "shown" to visible.size.toString(),
                            "total" to model.totalEntries.toString(),
                        ),
                    ),
                    enabled = false,
                ),
            )
            if (page > 0) {
                put("previous", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ITEM_CATALOG, "previous")) { reopen(page - 1) })
            }
            if (page + 1 < pages) {
                put("next", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ITEM_CATALOG, "next")) { reopen(page + 1) })
            }
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.ITEM_CATALOG,
            TextUtil.mm(title, true),
            elements = controls,
            regions = mapOf(ArcMenuSchema.CATALOG_ITEMS to visible.map { render(it, page) }),
        )
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
    ): PaperMenuEntry = ArcMenus.entry(stack) { action() }

    private fun openBack(player: Player, target: BackTarget) {
        when (target) {
            is BackTarget.Root -> openRoot(player, target.page)
            is BackTarget.Group -> openGroup(player, target.groupId, target.page, target.rootPage)
        }
    }

    private fun applyPresentation(base: ItemStack, presentation: ItemStack): ItemStack =
        base.clone().also { target ->
            val source = presentation.itemMeta
            target.editMeta { meta ->
                meta.displayName(source.displayName())
                meta.lore(source.lore())
                meta.isHideTooltip = source.isHideTooltip
                meta.setEnchantmentGlintOverride(source.enchantmentGlintOverride)
            }
        }

    private fun catalogContext(
        name: String,
        categories: Int,
        items: Int,
        action: String,
        description: List<String>,
    ) = PaperMenuItemRenderContext(
        values = mapOf(
            "name" to TextUtil.mm(name, true),
            "categories" to Component.text(categories),
            "items" to Component.text(items),
            "action" to Component.text(action),
        ),
        repeats = mapOf("description" to description.map { mapOf("line" to TextUtil.mm(it, true)) }),
    )

    private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
        values = pairs.associate { (key, value) -> key to TextUtil.mm(value, true) },
    )

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
