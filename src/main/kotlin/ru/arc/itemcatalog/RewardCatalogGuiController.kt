package ru.arc.itemcatalog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.hooks.HookRegistry
import ru.arc.ops.ItemPresets
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.treasure.core.GiveResult
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.Treasures
import ru.arc.treasure.pouch.Pouches
import ru.arc.util.ItemStackFactory
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.withCustomModelData
import java.util.concurrent.atomic.AtomicBoolean

/** GUI and final-click guard for the operator-selected reward catalogue. */
class RewardCatalogGuiController(
    private val settings: RewardCatalogSettings,
    private val givePermission: String,
) {
    private val active = AtomicBoolean(true)

    fun shutdown() {
        active.set(false)
    }

    fun isAvailable(): Boolean = active.get() && settings.enabled && settings.categories.any { it.entries.isNotEmpty() }

    fun openRoot(player: Player, back: () -> Unit = player::closeInventory) {
        if (!active.get()) {
            player.closeInventory()
            return
        }
        if (!isAvailable()) {
            sendConfigured(player, settings.messages.unavailable)
            return
        }
        pagedMenu(
            player = player,
            title = titleStrip(settings.title),
            entries = settings.categories,
            requestedPage = 0,
            back = back,
            reopen = { nextPage -> openRootPage(player, nextPage, back) },
            render = { category, _ -> categoryItem(player, category, back) },
        )
    }

    private fun openRootPage(player: Player, page: Int, back: () -> Unit) {
        if (!active.get()) {
            player.closeInventory()
            return
        }
        if (!isAvailable()) return back()
        pagedMenu(
            player = player,
            title = titleStrip(settings.title),
            entries = settings.categories,
            requestedPage = page,
            back = back,
            reopen = { nextPage -> openRootPage(player, nextPage, back) },
            render = { category, _ -> categoryItem(player, category, back) },
        )
    }

    /** Item shown as the root tab in the existing `/arc items` catalogue. */
    fun rootEntry(player: Player, open: () -> Unit): PaperMenuEntry {
        val stack =
            rewardPresentation(
                base = styledStack(settings.rootIcon),
                name = authoredComponent("<gold><bold>Награды лутбоксов", NAME_DEFAULT),
                details =
                    buildList {
                        add(body("Предметы и сюрпризы из лутбоксов."))
                        add(Component.empty())
                        add(metadata("Категорий", settings.categories.size.toString()))
                        add(metadata("Наград", settings.entryCount.toString()))
                    },
                action = "[▶] ЛКМ — открыть награды",
            )
        return ArcMenus.entry(stack) { open() }
    }

    private fun categoryItem(
        player: Player,
        category: RewardCatalogCategory,
        back: () -> Unit,
    ): PaperMenuEntry {
        val base = styledStack(category.icon)
        val details = buildList {
            addAll(category.description.map(::body))
            add(Component.empty())
            add(metadata("Наград", category.entries.size.toString()))
        }
        return ArcMenus.entry(
            rewardPresentation(
                base = base,
                name = authoredComponent(category.name, NAME_DEFAULT),
                details = details,
                action =
                    if (category.id.startsWith("set_")) {
                        "[▶] ЛКМ — открыть пак"
                    } else {
                        "[▶] ЛКМ — открыть категорию"
                    },
            ),
        ) {
            openCategory(player, category.id, 0, back)
        }
    }

    private fun openCategory(player: Player, categoryId: String, page: Int, rootBack: () -> Unit) {
        if (!active.get()) {
            player.closeInventory()
            return
        }
        val category = settings.categories.firstOrNull { it.id == categoryId } ?: return rootBack()
        pagedMenu(
            player = player,
            title = titleStrip(category.name),
            entries = category.entries,
            requestedPage = page,
            back = { openRoot(player, rootBack) },
            reopen = { nextPage -> openCategory(player, categoryId, nextPage, rootBack) },
            render = { entry, _ -> rewardItem(player, entry) },
        )
    }

    private fun rewardItem(player: Player, entry: RewardCatalogEntry): PaperMenuEntry {
        val providerReady = providersEnabled(entry)
        val resolved = if (providerReady) resolve(entry) else null
        val preview = previewStack(entry, resolved)
        val capacityReady = resolved?.let { hasCapacity(player, it) } == true
        val canGive =
            active.get() && player.hasPermission(givePermission) && providerReady && resolved != null && capacityReady
        val action =
            when {
                canGive -> "[▶] ЛКМ — получить"
                !providerReady -> "Сейчас недоступно"
                !player.hasPermission(givePermission) -> "Получение недоступно"
                resolved == null -> "Сейчас недоступно"
                !capacityReady -> "Нет места в инвентаре"
                else -> "Сейчас недоступно"
            }
        val original = tooltipLines(entry, resolved, preview)
        val stack = rewardPresentation(preview, displayName(entry, resolved, preview), original, action)
        return if (canGive) {
            ArcMenus.entry(stack) { handleClick(player, entry) }
        } else {
            // A player without the final grant permission gets a genuinely inert entry.
            ArcMenus.entry(stack, enabled = false)
        }
    }

    private fun handleClick(player: Player, entry: RewardCatalogEntry) {
        val resolved = RewardCatalogClickGuard.resolveForGrant(
            active = active.get(),
            hasPermission = { player.hasPermission(givePermission) },
            providersEnabled = { providersEnabled(entry) },
            resolve = { resolve(entry) },
        )
        if (resolved == null) {
            if (!active.get()) player.closeInventory()
            sendConfigured(player, settings.messages.unavailable)
            return
        }
        when (resolved) {
            is ResolvedReward.TreasureValue -> giveTreasure(player, entry, resolved.value)
            is ResolvedReward.Stacks ->
                if (addStacks(player, resolved.values)) {
                    sendConfigured(player, settings.messages.given)
                } else {
                    sendConfigured(player, settings.messages.inventoryFull)
                }
        }
    }

    private fun giveTreasure(player: Player, entry: RewardCatalogEntry, treasure: Treasure) {
        if (!hasTreasureCapacity(player, treasure)) {
            sendConfigured(player, settings.messages.inventoryFull)
            return
        }
        val result =
            runCatching { Treasures.service.give(treasure, player) }
                .getOrElse {
                    warn(
                        "Reward catalog treasure grant failed: player={} entry={} source={}",
                        player.uniqueId,
                        entry.id,
                        sourceLabel(entry.source),
                        it,
                    )
                    sendConfigured(player, settings.messages.unavailable)
                    return
                }
        when (result) {
            is GiveResult.Success -> sendConfigured(player, settings.messages.accepted)
            is GiveResult.Failure -> {
                warn("Reward catalog treasure grant rejected: player={} entry={} reason={}", player.uniqueId, entry.id, result.reason)
                sendConfigured(player, settings.messages.unavailable)
            }
        }
    }

    private fun resolve(entry: RewardCatalogEntry): ResolvedReward? =
        runCatching {
            when (val source = entry.source) {
                is RewardCatalogSource.Treasure ->
                    Treasures.getPool(source.pool)
                        ?.findById(source.id)
                        ?.takeIf { treasure ->
                            treasure !is Treasure.Slimefun ||
                                HookRegistry.sfHook?.getSlimefunItemStack(treasure.itemId) != null
                        }
                        ?.let(ResolvedReward::TreasureValue)
                is RewardCatalogSource.Preset ->
                    ItemPresets.resolveStacks(source.id, 1).getOrNull()?.takeIf { it.isNotEmpty() && it.size <= MAX_STACKS }
                        ?.let(ResolvedReward::Stacks)
                is RewardCatalogSource.Pouch ->
                    Pouches.createStack(source.id).getOrNull()?.let { ResolvedReward.Stacks(listOf(it)) }
            }
        }.getOrNull()

    private fun previewStack(entry: RewardCatalogEntry, resolved: ResolvedReward?): ItemStack =
        when (resolved) {
            is ResolvedReward.TreasureValue ->
                when (val treasure = resolved.value) {
                    is Treasure.Item -> treasure.stack.clone().also { it.amount = 1 }
                    is Treasure.Slimefun -> HookRegistry.sfHook?.getSlimefunItemStack(treasure.itemId)?.clone()?.also { it.amount = 1 }
                    else -> null
                }
            is ResolvedReward.Stacks -> resolved.values.firstOrNull()?.clone()
            null -> null
        } ?: styledStack(entry.icon ?: CatalogIconStyle(Material.PAPER.name))

    private fun displayName(entry: RewardCatalogEntry, resolved: ResolvedReward?, preview: ItemStack): Component =
        entry.name?.let { authoredComponent(it, NAME_DEFAULT) }
            ?: nativeDisplayName(resolved, preview)

    private fun nativeDisplayName(resolved: ResolvedReward?, preview: ItemStack): Component =
        when (resolved) {
            is ResolvedReward.Stacks -> preview.itemMeta?.displayName() ?: Component.translatable(preview.type.translationKey())
            is ResolvedReward.TreasureValue ->
                when (val treasure = resolved.value) {
                    is Treasure.Item -> preview.itemMeta?.displayName() ?: Component.translatable(preview.type.translationKey())
                    is Treasure.Slimefun ->
                        HookRegistry.sfHook?.getSlimefunItemStack(treasure.itemId)?.itemMeta?.displayName()
                            ?: Component.translatable(preview.type.translationKey())
                    else -> Component.text("Награда", NAME_DEFAULT)
                }
            null -> Component.text("Награда", NAME_DEFAULT)
        }.let { component ->
            authoredComponent(component, NAME_DEFAULT)
        }

    private fun tooltipLines(
        entry: RewardCatalogEntry,
        resolved: ResolvedReward?,
        preview: ItemStack,
    ): List<Component> =
        buildList {
            addAll(trimBlankEdges(preview.itemMeta?.lore().orEmpty().take(MAX_INTRINSIC_LORE).map(::nonItalic)))
            appendSection(entry.description.map(::descriptionComponent))
            appendSection(
                buildList {
                    entry.rarity?.let { add(metadata("Редкость", authoredComponent(it, RARITY_DEFAULT))) }
                    rewardAmount(resolved)?.let { add(metadata("Количество", authoredComponent(it, VALUE_DEFAULT))) }
                },
            )
        }

    private fun MutableList<Component>.appendSection(lines: List<Component>) {
        val cleaned = trimBlankEdges(lines)
        if (cleaned.isEmpty()) return
        if (isNotEmpty() && !isBlank(last())) add(Component.empty())
        addAll(cleaned)
    }

    private fun trimBlankEdges(lines: List<Component>): List<Component> =
        lines.dropWhile(::isBlank).dropLastWhile(::isBlank)

    private fun isBlank(component: Component): Boolean =
        PlainTextComponentSerializer.plainText().serialize(component).isBlank()

    private fun rewardAmount(resolved: ResolvedReward?): String? =
        (resolved as? ResolvedReward.TreasureValue)?.value?.let { treasure ->
            when (treasure) {
                is Treasure.Item -> if (treasure.min == treasure.max) treasure.min.toString() else "${treasure.min}–${treasure.max}"
                is Treasure.Enchant -> if (treasure.min == treasure.max) treasure.min.toString() else "${treasure.min}–${treasure.max}"
                is Treasure.Potion -> if (treasure.min == treasure.max) treasure.min.toString() else "${treasure.min}–${treasure.max}"
                else -> null
            }
        }

    private fun providersEnabled(entry: RewardCatalogEntry): Boolean =
        entry.requires.all { required ->
            Bukkit.getPluginManager().plugins.any { plugin -> plugin.name.equals(required, ignoreCase = true) && plugin.isEnabled }
        }

    private fun hasTreasureCapacity(player: Player, treasure: Treasure): Boolean =
        when (treasure) {
            is Treasure.Money -> true
            is Treasure.Item -> hasCapacity(player, capacityStacks(treasure.stack, treasure.max))
            is Treasure.Enchant -> emptyStorageSlots(player) >= treasure.max.coerceAtLeast(1)
            is Treasure.Potion -> emptyStorageSlots(player) >= treasure.max.coerceAtLeast(1)
            is Treasure.Ae -> emptyStorageSlots(player) >= treasure.amount.coerceAtLeast(1)
            is Treasure.Slimefun ->
                HookRegistry.sfHook?.getSlimefunItemStack(treasure.itemId)?.let { native ->
                    hasCapacity(player, capacityStacks(native, treasure.max))
                } == true
            else -> emptyStorageSlots(player) >= 1
        }

    private fun hasCapacity(player: Player, resolved: ResolvedReward): Boolean =
        when (resolved) {
            is ResolvedReward.TreasureValue -> hasTreasureCapacity(player, resolved.value)
            is ResolvedReward.Stacks -> hasCapacity(player, resolved.values)
        }

    private fun capacityStacks(template: ItemStack, quantity: Int): List<ItemStack> {
        var remaining = quantity.coerceAtLeast(1)
        val maxPerStack = template.maxStackSize.coerceAtLeast(1)
        return buildList {
            while (remaining > 0) {
                add(template.clone().also { it.amount = minOf(remaining, maxPerStack) })
                remaining -= maxPerStack
            }
        }
    }

    private fun addStacks(player: Player, values: List<ItemStack>): Boolean {
        if (!hasCapacity(player, values)) return false
        val before = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        return try {
            val leftovers = player.inventory.addItem(*values.map { it.clone() }.toTypedArray())
            if (leftovers.isEmpty()) true else {
                player.inventory.storageContents = before
                false
            }
        } catch (_: RuntimeException) {
            player.inventory.storageContents = before
            false
        }
    }

    private fun hasCapacity(player: Player, values: List<ItemStack>): Boolean {
        val simulation = Bukkit.createInventory(null, 36)
        simulation.contents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        return values.all { simulation.addItem(it.clone()).isEmpty() }
    }

    private fun emptyStorageSlots(player: Player): Int =
        player.inventory.storageContents.count { it == null || it.type.isAir }

    private fun sourceLabel(source: RewardCatalogSource): String =
        when (source) {
            is RewardCatalogSource.Treasure -> "${source.pool}:${source.id}"
            is RewardCatalogSource.Preset -> "preset:${source.id}"
            is RewardCatalogSource.Pouch -> "pouch:${source.id}"
        }

    private fun styledStack(style: CatalogIconStyle): ItemStack =
        ItemStackFactory.create(Material.valueOf(style.material), 1).also { stack ->
            if (style.customModelData != 0) stack.withCustomModelData(style.customModelData)
        }

    private fun rewardPresentation(
        base: ItemStack,
        name: Component,
        details: List<Component>,
        action: String,
    ): ItemStack =
        base.clone().also { target ->
            target.editMeta { meta ->
                meta.displayName(name)
                val content = trimBlankEdges(details)
                meta.lore(buildList {
                    add(Component.empty())
                    addAll(content)
                    if (content.isNotEmpty()) add(Component.empty())
                    add(actionComponent(action))
                })
                meta.isHideTooltip = false
            }
        }

    private fun actionComponent(action: String): Component =
        if (action.startsWith("[▶]")) Component.text(action, ACTION).decoration(TextDecoration.ITALIC, false)
        else Component.text(action, UNAVAILABLE).decoration(TextDecoration.ITALIC, false)

    private fun sendConfigured(player: Player, template: String) {
        player.sendMessage(TextUtil.mm(template, true))
    }

    private fun descriptionComponent(line: String): Component = authoredComponent(line, BODY)

    private fun <T> pagedMenu(
        player: Player,
        title: Component,
        entries: List<T>,
        requestedPage: Int,
        back: () -> Unit,
        reopen: (Int) -> Unit,
        render: (T, Int) -> PaperMenuEntry,
    ) {
        val model = catalogPage(entries, requestedPage, PAGE_SIZE)
        val page = model.page
        val controls = buildMap {
            put("back", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ITEM_CATALOG, "back")) { back() })
            put(
                "page",
                ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.ITEM_CATALOG,
                        "page",
                        PaperMenuItemRenderContext(
                            values = mapOf(
                                "page" to TextUtil.mm((page + 1).toString(), true),
                                "pages" to TextUtil.mm(model.pages.toString(), true),
                                "shown" to TextUtil.mm(model.entries.size.toString(), true),
                                "total" to TextUtil.mm(model.totalEntries.toString(), true),
                            ),
                        ),
                    ),
                    enabled = false,
                ),
            )
            if (page > 0) put("previous", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ITEM_CATALOG, "previous")) { reopen(page - 1) })
            if (page + 1 < model.pages) put("next", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ITEM_CATALOG, "next")) { reopen(page + 1) })
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.ITEM_CATALOG,
            title,
            elements = controls,
            regions = mapOf(ArcMenuSchema.CATALOG_ITEMS to model.entries.map { render(it, page) }),
        )
    }

    private fun titleStrip(raw: String): Component {
        val plain = PlainTextComponentSerializer.plainText().serialize(TextUtil.mm(raw, true))
        return Component.text(plain, TITLE).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
    }

    private fun metadata(label: String, value: String): Component = metadata(label, authoredComponent(value, VALUE_DEFAULT))

    private fun metadata(label: String, value: Component): Component =
        Component.text("$label: ", LABEL).decoration(TextDecoration.ITALIC, false).append(nonItalic(value))

    private fun body(text: String): Component = authoredComponent(text, BODY)

    private fun authoredComponent(text: String, fallback: TextColor): Component =
        authoredComponent(TextUtil.mm(text, true), fallback)

    private fun authoredComponent(component: Component, fallback: TextColor): Component =
        (if (component.color() == null) component.color(fallback) else component)
            .decoration(TextDecoration.ITALIC, false)

    private fun nonItalic(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)

    private sealed interface ResolvedReward {
        data class TreasureValue(val value: Treasure) : ResolvedReward

        data class Stacks(val values: List<ItemStack>) : ResolvedReward
    }

    companion object {
        private const val PAGE_SIZE = 45
        private const val MAX_INTRINSIC_LORE = 24
        private const val MAX_STACKS = 64
        private val TITLE = TextColor.color(0x20252B)
        private val NAME_DEFAULT = TextColor.color(0xF4BD6A)
        private val BODY = TextColor.color(0xF3EEE6)
        private val LABEL = TextColor.color(0xEAD5B5)
        private val VALUE_DEFAULT = TextColor.color(0xF8F0DC)
        private val RARITY_DEFAULT = TextColor.color(0xFFD166)
        private val ACTION = TextColor.color(0x5FE18B)
        private val UNAVAILABLE = TextColor.color(0xF2C66D)
    }
}
