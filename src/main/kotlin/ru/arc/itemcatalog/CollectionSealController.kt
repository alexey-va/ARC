package ru.arc.itemcatalog

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.Treasures
import ru.arc.util.ItemStackFactory
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.withCustomModelData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Physical collection-seal entitlement and its player-facing selection flow.
 *
 * The seal itself is the entitlement; no permission or persistent economic
 * record is consulted. A session can successfully claim at most once, even
 * when the player owns several matching seals.
 */
class CollectionSealController(
    private val plugin: Plugin,
    private val settings: RewardCatalogSettings,
) {
    private val active = AtomicBoolean(true)
    private val registered = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val exchange = CollectionSealExchange()

    private val listener = object : Listener {
        @EventHandler
        fun onPlayerQuit(event: PlayerQuitEvent) {
            sessions.remove(event.player.uniqueId)?.cancel()
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        fun onPlayerInteract(event: PlayerInteractEvent) {
            if (!active.get() || event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
            if (event.action !in RIGHT_CLICK_ACTIONS) return
            val categoryId = CollectionSealIdentity.categoryId(event.item ?: event.player.inventory.itemInMainHand) ?: return

            event.isCancelled = true
            open(event.player, categoryId)
        }
    }

    /** Registers the right-click listener once for this controller lifetime. */
    fun register() {
        if (active.get() && registered.compareAndSet(false, true)) {
            plugin.server.pluginManager.registerEvents(listener, plugin)
        }
    }

    /** Disables old callbacks and unregisters the right-click listener. */
    fun close() {
        if (!active.compareAndSet(true, false)) return
        sessions.values.forEach(Session::cancel)
        sessions.clear()
        if (registered.compareAndSet(true, false)) HandlerList.unregisterAll(listener)
    }

    /**
     * Creates one physical seal for a current, valid `set_` category.
     * Returns null for disabled/unknown categories or categories with no live
     * native equipment choices.
     */
    fun createStack(categoryId: String, icon: CatalogIconStyle? = null): ItemStack? {
        if (!active.get() || !settings.enabled || !CollectionSealIdentity.isValidCategoryId(categoryId)) return null
        val category = settings.categories.firstOrNull { it.id == categoryId } ?: return null
        val choices = currentChoices(category)
        if (choices.isEmpty()) return null
        val base = runCatching { styledStack(icon ?: category.icon) }.getOrNull() ?: return null
        val name = collectionName(category.name)
        val stack = base.clone()
        CollectionSealIdentity.mark(stack, categoryId)
        stack.editMeta { meta ->
            meta.displayName(
                Component.text("Печать коллекции · ", NAME)
                    .append(name)
                    .decoration(TextDecoration.ITALIC, false),
            )
            meta.lore(
                buildList {
                    add(Component.empty())
                    add(body("Открывает выбор одного предмета из коллекции."))
                    add(body("Коллекция: ").append(name))
                    if (category.description.isNotEmpty()) {
                        add(Component.empty())
                        addAll(category.description.map(::body))
                    }
                    add(Component.empty())
                    add(labelValue("Доступно предметов", choices.size.toString()))
                    add(Component.empty())
                    add(action("[▶] ПКМ — выбрать предмет"))
                }.map(::nonItalic),
            )
            meta.isHideTooltip = false
        }
        return stack
    }

    private fun open(player: Player, categoryId: String) {
        if (!active.get()) return
        sessions.remove(player.uniqueId)?.cancel()
        val session = Session(player.uniqueId, categoryId)
        sessions[player.uniqueId] = session
        openSelectionPage(player, session, 0)
    }

    private fun openSelectionPage(player: Player, session: Session, requestedPage: Int) {
        if (!isSessionActive(player, session)) return
        val category = currentCategory(session.categoryId)
        if (category == null) return unavailable(player, session)
        val choices = currentChoices(category)
        if (choices.isEmpty()) return unavailable(player, session)
        pagedMenu(
            player = player,
            title = titleStrip(category.name),
            entries = choices,
            requestedPage = requestedPage,
            back = {
                session.cancel()
                sessions.remove(player.uniqueId, session)
                player.closeInventory()
            },
            reopen = { page -> openSelectionPage(player, session, page) },
            render = { choice, page -> selectionEntry(player, session, choice, page) },
        )
    }

    private fun openConfirmation(
        player: Player,
        session: Session,
        entryId: String,
        returnPage: Int,
    ) {
        if (!isSessionActive(player, session)) return
        val category = currentCategory(session.categoryId)
        if (category == null) return unavailable(player, session)
        val entry = category.entries.firstOrNull { it.id == entryId } ?: return openSelectionPage(player, session, returnPage)
        pagedMenu(
            player = player,
            title = titleStrip(category.name),
            entries = listOf(entry),
            requestedPage = 0,
            back = { openSelectionPage(player, session, returnPage) },
            reopen = { openConfirmation(player, session, entryId, returnPage) },
            render = { _, _ -> confirmationEntry(player, session, entry) },
        )
    }

    private fun selectionEntry(player: Player, session: Session, choice: CurrentChoice, page: Int): PaperMenuEntry {
        val stack = presentation(choice.entry, choice.stack, "[▶] ЛКМ — выбрать предмет")
        return ArcMenus.entry(
            stack,
            acceptedClicks = LEFT_CLICKS,
        ) {
            openConfirmation(player, session, choice.entry.id, page)
        }
    }

    private fun confirmationEntry(
        player: Player,
        session: Session,
        entry: RewardCatalogEntry,
    ): PaperMenuEntry {
        val current = currentChoice(entry)
        val stack =
            if (current != null) {
                presentation(entry, current.stack, "[▶] ЛКМ — подтвердить получение")
            } else {
                unavailableStack(entry)
            }
        return if (current != null) {
            ArcMenus.entry(stack, acceptedClicks = LEFT_CLICKS) {
                claim(player, session, entry.id)
            }
        } else {
            ArcMenus.entry(stack, enabled = false)
        }
    }

    private fun claim(player: Player, session: Session, entryId: String) {
        if (!isSessionActive(player, session)) return
        if (!session.beginClaim()) return

        var successful = false
        try {
            val category = currentCategory(session.categoryId)
            val entry = category?.entries?.firstOrNull { it.id == entryId }
            val reward = entry?.let(::currentChoice)?.stack
            val result = reward?.let { exchange.redeem(player, session.categoryId, it) }
                ?: CollectionSealExchange.RedemptionResult.SealMissing
            when (result) {
                CollectionSealExchange.RedemptionResult.Success -> {
                    successful = true
                    session.complete()
                    sessions.remove(player.uniqueId, session)
                    player.closeInventory()
                    player.sendMessage(TextUtil.mm(settings.messages.given, true))
                }
                CollectionSealExchange.RedemptionResult.InventoryFull -> {
                    player.sendMessage(TextUtil.mm(settings.messages.inventoryFull, true))
                }
                CollectionSealExchange.RedemptionResult.InvalidCategory,
                CollectionSealExchange.RedemptionResult.InvalidReward,
                CollectionSealExchange.RedemptionResult.SealMissing,
                -> player.sendMessage(TextUtil.mm(settings.messages.unavailable, true))
                is CollectionSealExchange.RedemptionResult.MutationFailed -> {
                    warn(
                        "Collection seal exchange failed: player={} category={} entry={}",
                        player.uniqueId,
                        session.categoryId,
                        entryId,
                        result.cause,
                    )
                    player.sendMessage(TextUtil.mm(settings.messages.unavailable, true))
                }
            }
        } finally {
            session.finish(successful)
        }
    }

    private fun currentCategory(categoryId: String): RewardCatalogCategory? =
        settings.categories.firstOrNull { it.id == categoryId }
            ?.takeIf { settings.enabled && CollectionSealIdentity.isValidCategoryId(it.id) }

    private fun currentChoices(category: RewardCatalogCategory): List<CurrentChoice> =
        category.entries.mapNotNull { entry -> currentChoice(entry) }

    private fun currentChoice(entry: RewardCatalogEntry): CurrentChoice? {
        if (!providersEnabled(entry)) return null
        val base =
            when (val source = entry.source) {
                is RewardCatalogSource.Treasure ->
                    runCatching { Treasures.getPool(source.pool)?.findById(source.id) as? Treasure.Item }
                        .getOrNull()
                        ?.stack
                        ?.clone()
                is RewardCatalogSource.ItemsAdder -> {
                    if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null
                    runCatching { CustomStack.getInstance(source.id)?.itemStack?.clone() }.getOrNull()
                }
                else -> null
            } ?: return null
        if (base.type.isAir) return null
        val enriched = RewardItemEnhancer.enrich(base, entry.enchantments) ?: return null
        return CurrentChoice(entry, enriched.also { it.amount = 1 })
    }

    private fun providersEnabled(entry: RewardCatalogEntry): Boolean =
        entry.requires.all { required ->
            Bukkit.getPluginManager().plugins.any { plugin ->
                plugin.name.equals(required, ignoreCase = true) && plugin.isEnabled
            }
        }

    private fun presentation(entry: RewardCatalogEntry, base: ItemStack, actionText: String): ItemStack =
        base.clone().also { target ->
            val meta = target.itemMeta ?: return@also
            val nativeLore = meta.lore().orEmpty().take(MAX_INTRINSIC_LORE).map(::nonItalic)
            val name =
                entry.name?.let { authored(it, NAME) }
                    ?: meta.displayName()?.let(::nonItalic)
                    ?: Component.translatable(target.type.translationKey()).decoration(TextDecoration.ITALIC, false)
            val lore = buildList {
                add(Component.empty())
                addAll(nativeLore)
                if (entry.description.isNotEmpty()) {
                    if (nativeLore.isNotEmpty()) add(Component.empty())
                    addAll(entry.description.map(::body))
                }
                add(Component.empty())
                add(action(actionText))
            }
            target.editMeta { rendered ->
                rendered.displayName(name)
                rendered.lore(lore.map(::nonItalic))
                rendered.isHideTooltip = false
            }
        }

    private fun unavailableStack(entry: RewardCatalogEntry): ItemStack =
        styledStack(CatalogIconStyle(Material.BARRIER.name)).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(authored(entry.name ?: "Предмет недоступен", NAME))
                meta.lore(
                    listOf(
                        Component.empty(),
                        body("Эта позиция больше не доступна в текущей коллекции."),
                    ).map(::nonItalic),
                )
            }
        }

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
                        values(
                            "page" to (page + 1).toString(),
                            "pages" to model.pages.toString(),
                            "shown" to model.entries.size.toString(),
                            "total" to model.totalEntries.toString(),
                        ),
                    ),
                    enabled = false,
                ),
            )
            if (page > 0) {
                put("previous", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ITEM_CATALOG, "previous")) { reopen(page - 1) })
            }
            if (page + 1 < model.pages) {
                put("next", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ITEM_CATALOG, "next")) { reopen(page + 1) })
            }
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.ITEM_CATALOG,
            title,
            elements = controls,
            regions = mapOf(ArcMenuSchema.CATALOG_ITEMS to model.entries.map { entry -> render(entry, page) }),
        )
    }

    private fun isSessionActive(player: Player, session: Session): Boolean =
        active.get() && player.isOnline && !session.isClosed && sessions[player.uniqueId] === session

    private fun unavailable(player: Player, session: Session) {
        session.cancel()
        sessions.remove(player.uniqueId, session)
        player.closeInventory()
        player.sendMessage(TextUtil.mm(settings.messages.unavailable, true))
    }

    private fun styledStack(style: CatalogIconStyle): ItemStack =
        ItemStackFactory.create(Material.valueOf(style.material), 1).also { stack ->
            if (style.customModelData != 0) stack.withCustomModelData(style.customModelData)
        }

    private fun collectionName(raw: String): Component = authored(raw, NAME)

    private fun authored(raw: String, fallback: TextColor): Component =
        TextUtil.mm(raw, true)
            .let { if (it.color() == null) it.color(fallback) else it }
            .decoration(TextDecoration.ITALIC, false)

    private fun titleStrip(raw: String): Component =
        Component.text(
            TextUtil.mm(raw, true).let {
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)
            },
            TITLE,
        ).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)

    private fun values(vararg pairs: Pair<String, String>): PaperMenuItemRenderContext =
        PaperMenuItemRenderContext(values = pairs.associate { (key, value) -> key to TextUtil.mm(value, true) })

    private fun labelValue(label: String, value: String): Component =
        Component.text("$label: ", LABEL).decoration(TextDecoration.ITALIC, false)
            .append(authored(value, VALUE))

    private fun body(text: String): Component = authored(text, BODY)

    private fun action(text: String): Component = authored(text, ACTION)

    private fun nonItalic(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)

    private class Session(
        val playerId: UUID,
        val categoryId: String,
    ) {
        @Volatile
        var isClosed: Boolean = false
            private set

        private var claiming = false
        private var claimed = false

        @Synchronized
        fun beginClaim(): Boolean {
            if (isClosed || claimed || claiming) return false
            claiming = true
            return true
        }

        @Synchronized
        fun finish(success: Boolean) {
            claiming = false
            if (success) claimed = true
        }

        @Synchronized
        fun complete() {
            isClosed = true
            claimed = true
        }

        @Synchronized
        fun cancel() {
            isClosed = true
        }
    }

    private data class CurrentChoice(
        val entry: RewardCatalogEntry,
        val stack: ItemStack,
    )

    companion object {
        private const val PAGE_SIZE = 45
        private const val MAX_INTRINSIC_LORE = 24
        private val RIGHT_CLICK_ACTIONS = setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)
        private val LEFT_CLICKS = setOf(ClickType.LEFT, ClickType.SHIFT_LEFT)
        private val TITLE = TextColor.color(0x20252B)
        private val NAME = TextColor.color(0xFFB547)
        private val BODY = TextColor.color(0xF3EEE6)
        private val LABEL = TextColor.color(0xEAD5B5)
        private val VALUE = TextColor.color(0xF8F0DC)
        private val ACTION = TextColor.color(0x5FE18B)
    }
}
