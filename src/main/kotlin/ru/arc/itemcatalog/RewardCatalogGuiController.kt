package ru.arc.itemcatalog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
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
            title = settings.title,
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
            title = settings.title,
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
            ArcMenus.item(
                "catalog-root-entry",
                PaperMenuItemRenderContext(
                    values = mapOf(
                        "name" to TextUtil.mm("Награды лутбоксов", true),
                        "categories" to Component.text(settings.categories.size),
                        "items" to Component.text(settings.entryCount),
                        "action" to Component.text("Нажмите — открыть награды"),
                    ),
                    repeats =
                        mapOf(
                            "description" to
                                listOf(
                                    mapOf("line" to TextUtil.mm("Награды и предметы из лутбоксов.", true)),
                                ),
                        ),
                ),
            ).withType(Material.CHEST)
        return ArcMenus.entry(stack) { open() }
    }

    private fun categoryItem(
        player: Player,
        category: RewardCatalogCategory,
        back: () -> Unit,
    ): PaperMenuEntry {
        val base = styledStack(category.icon)
        val rendered =
            ArcMenus.item(
                "catalog-root-entry",
                PaperMenuItemRenderContext(
                    values = mapOf(
                        "name" to TextUtil.mm(category.name, true),
                        "categories" to Component.text(0),
                        "items" to Component.text(category.entries.size),
                        "action" to Component.text("Нажмите — открыть награды"),
                    ),
                    repeats = mapOf(
                        "description" to category.description.map { line -> mapOf("line" to descriptionComponent(line)) },
                    ),
                ),
            )
        return ArcMenus.entry(applyPresentation(base, rendered)) {
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
            title = category.name,
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
        entry.name?.let { TextUtil.mm(it, true) }
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
                    else -> Component.text("Награда", MUTED)
                }
            null -> Component.text("Награда", MUTED)
        }.decoration(TextDecoration.ITALIC, false)

    private fun tooltipLines(
        entry: RewardCatalogEntry,
        resolved: ResolvedReward?,
        preview: ItemStack,
    ): List<Component> =
        buildList {
            add(Component.empty())
            addAll(preview.itemMeta?.lore().orEmpty().take(MAX_INTRINSIC_LORE).map(::nonItalic))
            if (entry.description.isNotEmpty()) {
                add(Component.empty())
                addAll(entry.description.map(::descriptionComponent))
            }
            entry.rarity?.let {
                add(Component.empty())
                add(labelValue("Редкость", it))
            }
            rewardAmount(resolved)?.let { add(labelValue("Количество", it)) }
            if (entry.requires.isNotEmpty()) add(labelValue("Требуется", entry.requires.joinToString(", ")))
        }

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

    private fun styledStack(style: CatalogIconStyle): ItemStack = ItemStackFactory.create(Material.valueOf(style.material), 1)

    private fun rewardPresentation(
        base: ItemStack,
        name: Component,
        details: List<Component>,
        action: String,
    ): ItemStack =
        base.clone().also { target ->
            target.editMeta { meta ->
                meta.displayName(name)
                meta.lore(details + Component.empty() + actionComponent(action))
                meta.isHideTooltip = false
            }
        }

    private fun actionComponent(action: String): Component =
        if (action.startsWith("[▶]")) accent(action, false) else muted(action)

    private fun applyPresentation(base: ItemStack, presentation: ItemStack): ItemStack =
        base.clone().also { target ->
            val source = presentation.itemMeta
            target.editMeta { meta ->
                meta.displayName(source.displayName())
                meta.lore(source.lore())
                meta.isHideTooltip = source.isHideTooltip
                meta.setEnchantmentGlintOverride(
                    if (source.hasEnchantmentGlintOverride()) source.enchantmentGlintOverride else null,
                )
            }
        }

    private fun sendConfigured(player: Player, template: String) {
        player.sendMessage(TextUtil.mm(template, true))
    }

    private fun descriptionComponent(line: String): Component = TextUtil.mm("<gray>$line", true)

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
            TextUtil.mm(title, true),
            elements = controls,
            regions = mapOf(ArcMenuSchema.CATALOG_ITEMS to model.entries.map { render(it, page) }),
        )
    }

    private fun labelValue(label: String, value: String): Component = muted("$label: ").append(accent(value, false))

    private fun accent(text: String, bold: Boolean): Component =
        Component.text(text, ACCENT).decoration(TextDecoration.BOLD, bold).decoration(TextDecoration.ITALIC, false)

    private fun muted(text: String): Component =
        Component.text(text, MUTED).decoration(TextDecoration.ITALIC, false)

    private fun nonItalic(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)

    private sealed interface ResolvedReward {
        data class TreasureValue(val value: Treasure) : ResolvedReward

        data class Stacks(val values: List<ItemStack>) : ResolvedReward
    }

    companion object {
        private const val PAGE_SIZE = 45
        private const val MAX_INTRINSIC_LORE = 24
        private const val MAX_STACKS = 64
        private val ACCENT = TextColor.color(0x92BED8)
        private val MUTED = TextColor.color(0x8C8C8C)
    }
}
