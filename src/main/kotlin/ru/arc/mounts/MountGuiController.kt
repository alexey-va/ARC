package ru.arc.mounts

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.Tasks
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import kotlin.math.ceil

private enum class MountScreen { LIST, DETAIL, PROGRESSION, SKINS, CONFIRM }

private enum class MountFilter(
    val title: String,
    val icon: Material,
    val styleRole: MountGuiItemRole,
) {
    ALL("Все", Material.COMPASS, MountGuiItemRole.CATEGORY_ALL),
    FLYING("Воздушные", Material.FEATHER, MountGuiItemRole.CATEGORY_FLYING),
    WALKING("Наземные", Material.SADDLE, MountGuiItemRole.CATEGORY_WALKING),
    SWIMMING("Водные", Material.HEART_OF_THE_SEA, MountGuiItemRole.CATEGORY_SWIMMING),
    ;

    fun matches(mount: MountDefinition): Boolean =
        this == ALL ||
            this == FLYING && mount.movement == MountMovement.FLYING ||
            this == WALKING && mount.movement == MountMovement.WALKING ||
            this == SWIMMING && mount.movement == MountMovement.SWIMMING

    fun next(): MountFilter = entries[(ordinal + 1) % entries.size]
}

private sealed interface ConfirmAction {
    data class Level(val level: Int) : ConfirmAction
    data object Glow : ConfirmAction
    data class Skin(val skinId: String) : ConfirmAction
    data class Ability(val abilityId: String) : ConfirmAction
}

private class MountMenuHolder(
    val screen: MountScreen,
    val mountId: String? = null,
    val page: Int = 0,
    val pageCount: Int = 1,
    val filter: MountFilter = MountFilter.ALL,
    val ownedOnly: Boolean = false,
    val mountsBySlot: Map<Int, String> = emptyMap(),
    val skinsBySlot: Map<Int, String> = emptyMap(),
    val abilitiesBySlot: Map<Int, String> = emptyMap(),
    val speedPercentagesBySlot: Map<Int, Int> = emptyMap(),
    val stepHeightsBySlot: Map<Int, Int> = emptyMap(),
    val sizeOptionsBySlot: Map<Int, String> = emptyMap(),
    val confirmAction: ConfirmAction? = null,
    var confirmEnabled: Boolean = false,
) : InventoryHolder {
    lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
}

class MountGuiController(
    private val plugin: JavaPlugin,
    private val configProvider: () -> MountModuleConfig,
    private val catalogProvider: () -> MountCatalog,
    private val ownership: MountOwnership,
    private val wallet: MountWallet,
    private val purchases: MountPurchaseCoordinator,
    private val summons: MountSummonService,
    private val quickSummons: MountQuickSummonController,
    private val transfers: () -> MountTransferController? = { null },
) : Listener {
    @Volatile private var active = false
    private val items = MountGuiItems(configProvider, quickSummons)

    fun start() {
        active = true
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun shutdown() {
        active = false
        plugin.server.onlinePlayers
            .filter { player -> runCatching { player.openInventory.topInventory.holder is MountMenuHolder }.getOrDefault(false) }
            .forEach(Player::closeInventory)
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    fun openList(player: Player) = openListPage(player, 0, MountFilter.ALL, false)

    fun openOwned(player: Player) {
        transfers()?.recoverDelivery(player)
        openListPage(player, 0, MountFilter.ALL, true)
    }

    private fun openListPage(
        player: Player,
        requestedPage: Int,
        filter: MountFilter,
        ownedOnly: Boolean,
    ) {
        val config = configProvider()
        val catalog = catalogProvider()
        val favoriteMountId = summons.favoriteMountId(player.uniqueId)
        val profiles = catalog.all.associateWith { mount -> ownership.profile(subject(player), mount) }
        val matching =
            catalog.all.filter { mount ->
                filter.matches(mount) && (!ownedOnly || checkNotNull(profiles[mount]).unlocked)
            }
        val visible = prioritizeUnlockedMounts(matching) { mount -> checkNotNull(profiles[mount]) }
        val pageCount = maxOf(1, ceil(visible.size.toDouble() / LIST_CONTENT_SLOTS.size).toInt())
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val pageMounts = visible.drop(page * LIST_CONTENT_SLOTS.size).take(LIST_CONTENT_SLOTS.size)
        val slots = LIST_CONTENT_SLOTS.zip(pageMounts.map(MountDefinition::id)).toMap()
        val holder =
            MountMenuHolder(
                MountScreen.LIST,
                page = page,
                pageCount = pageCount,
                filter = filter,
                ownedOnly = ownedOnly,
                mountsBySlot = slots,
            )
        val inventory = Bukkit.createInventory(holder, LIST_SIZE, component(config.listTitle))
        holder.backingInventory = inventory
        fill(inventory)
        slots.forEach { (slot, mountId) ->
            val mount = catalog[mountId] ?: return@forEach
            inventory.setItem(slot, items.mountIcon(mount, checkNotNull(profiles[mount]), favorite = mount.id == favoriteMountId))
        }
        if (page > 0) {
            inventory.setItem(
                LIST_PREVIOUS_SLOT,
                styledItem(
                    MountGuiItemRole.PREVIOUS,
                    Material.ARROW,
                    config.guiText("list.previous-name", "<#92bed8>Предыдущая страница"),
                    listOf(
                        copy(
                            "list.page",
                            "<#8c8c8c>Страница: <#e6fff3><page>/<pages>",
                            "page" to (page + 1).toString(),
                            "pages" to pageCount.toString(),
                        ),
                        "",
                        config.guiText("list.previous-footer", actionFooter(config.guiText("list.previous-action", "перейти назад"))),
                    ),
                ),
            )
        }
        if (page + 1 < pageCount) {
            inventory.setItem(
                LIST_NEXT_SLOT,
                styledItem(
                    MountGuiItemRole.NEXT,
                    Material.ARROW,
                    config.guiText("list.next-name", "<#92bed8>Следующая страница"),
                    listOf(
                        copy(
                            "list.page",
                            "<#8c8c8c>Страница: <#e6fff3><page>/<pages>",
                            "page" to (page + 1).toString(),
                            "pages" to pageCount.toString(),
                        ),
                        "",
                        config.guiText("list.next-footer", actionFooter(config.guiText("list.next-action", "перейти вперёд"))),
                    ),
                ),
            )
        }
        inventory.setItem(
            LIST_FILTER_SLOT,
            styledItem(
                filter.styleRole,
                filter.icon,
                config.guiText("list.filter-name", "<#92bed8>Фильтр коллекции"),
                copyLines(
                    "list.filter-stats",
                    listOf(
                        "<#8c8c8c>Категория: <#e6fff3><category>",
                        "<#8c8c8c>Показано: <#e6fff3><shown>/<total>",
                    ),
                    "category" to config.guiText("list.filter-category-${filter.name.lowercase()}", filter.title),
                    "shown" to visible.size.toString(),
                    "total" to catalog.all.size.toString(),
                ) +
                    listOf(
                        "",
                        copy(
                            "list.filter-footer",
                            "<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ЛКМ<#e6fff3> — сменить <#8c8c8c>· <#92bed8>ПКМ<#e6fff3> — <owned-action>",
                            "owned-action" to
                                if (ownedOnly) {
                                    config.guiText("list.filter-show-all", "показать все")
                                } else {
                                    config.guiText("list.filter-show-owned", "только полученные")
                                },
                        ),
                    ),
                glint = ownedOnly,
            ),
        )
        inventory.setItem(
            LIST_INFO_SLOT,
            styledItem(
                MountGuiItemRole.INFO,
                Material.BOOK,
                config.guiText("list.guide-name", "<#92bed8>Путеводитель по коллекции"),
                config.guiLines(
                    "list.guide-lore",
                    listOf(
                        "<#8c8c8c>ЛКМ — призвать полученного маунта",
                        "<#8c8c8c>ПКМ — открыть развитие и облики",
                        "",
                        "<#8c8c8c>Shift + F — призвать любимого маунта",
                        "<#8c8c8c>Свисток — получить в карточке маунта",
                        "",
                        "<#92bed8>Полёт",
                        "<#8c8c8c>Space — вверх",
                        "<#8c8c8c>Shift — вниз",
                        "<#8c8c8c>Взгляд вниз скрывает маунта из кадра",
                        "",
                        "<#8c8c8c>Двойной Shift — спешиться",
                    ),
                ),
            ),
        )
        inventory.setItem(
            LIST_BACK_SLOT,
            styledItem(
                MountGuiItemRole.BACK,
                Material.BLUE_STAINED_GLASS_PANE,
                config.guiText("common.back-name", "<#92bed8>Назад"),
                actionLore(listOf(config.guiText("list.back-description", "<#8c8c8c>Вернуться в главное меню.")), "вернуться"),
            ),
        )
        player.openInventory(inventory)
        click(player)
    }

    fun openDetail(player: Player, mountId: String) {
        val config = configProvider()
        val mount = catalogProvider()[mountId] ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        val abilitySlots = centeredSlots(DETAIL_ABILITY_SLOTS, mount.abilities.upgrades.size)
            .zip(mount.abilities.upgrades.map(MountAbilityUpgradeDefinition::id))
            .toMap()
        val holder = MountMenuHolder(MountScreen.DETAIL, mount.id, abilitiesBySlot = abilitySlots)
        val inventory = Bukkit.createInventory(holder, DETAIL_SIZE, component(config.detailTitle.replace("<mount>", escape(mount.displayName))))
        holder.backingInventory = inventory
        fill(inventory)
        transfers()?.let { inventory.setItem(it.detailSlot, it.button(profile.unlocked)) }
        val favorite = summons.favoriteMountId(player.uniqueId) == mount.id
        inventory.setItem(DETAIL_ICON_SLOT, items.mountIcon(mount, profile, detailed = true, favorite = favorite))
        inventory.setItem(DETAIL_FAVORITE_SLOT, items.favoriteItem(profile, favorite))
        inventory.setItem(DETAIL_UPGRADE_SLOT, items.upgradeItem(mount, profile))
        inventory.setItem(DETAIL_SUMMON_SLOT, items.summonItem(profile, config.sessionDuration))
        inventory.setItem(DETAIL_GLOW_SLOT, items.glowItem(mount, profile))
        inventory.setItem(DETAIL_SKINS_SLOT, items.skinsItem(mount, profile))
        inventory.setItem(DETAIL_WHISTLE_SLOT, items.whistleMenuItem(player, summons.favoriteMountId(player.uniqueId)))
        abilitySlots.forEach { (slot, abilityId) ->
            mount.ability(abilityId)?.let { inventory.setItem(slot, items.abilityItem(profile, it)) }
        }
        inventory.setItem(
            DETAIL_BACK_SLOT,
            styledItem(
                MountGuiItemRole.BACK,
                Material.BLUE_STAINED_GLASS_PANE,
                config.guiText("common.back-name", "<#92bed8>Назад"),
                actionLore(listOf(config.guiText("detail.back-description", "<#8c8c8c>Вернуться к коллекции.")), "вернуться"),
            ),
        )
        player.openInventory(inventory)
        click(player)
    }

    private fun openProgression(player: Player, mount: MountDefinition) {
        val config = configProvider()
        val tuning = config.tuning
        val profile = ownership.profile(subject(player), mount)
        val speedSlots = TUNING_SPEED_SLOTS.zip(tuning.speedPercentages).toMap()
        val stepSlots =
            if (mount.movement == MountMovement.WALKING) {
                TUNING_STEP_SLOTS.zip(tuning.walkingStepHeightsHundredths).toMap()
            } else {
                emptyMap()
            }
        val sizeSlots =
            mount.sizeOptions.takeIf { it.size > 1 }
                ?.let { tuningSizeSlots(it.size).zip(it.map(MountSizeOptionDefinition::id)).toMap() }
                .orEmpty()
        val holder =
            MountMenuHolder(
                MountScreen.PROGRESSION,
                mount.id,
                speedPercentagesBySlot = speedSlots,
                stepHeightsBySlot = stepSlots,
                sizeOptionsBySlot = sizeSlots,
            )
        val inventory =
            Bukkit.createInventory(
                holder,
                TUNING_SIZE,
                component(config.progressionTitle.replace("<mount>", escape(mount.displayName))),
            )
        holder.backingInventory = inventory
        fill(inventory)
        inventory.setItem(TUNING_INFO_SLOT, items.progressionInfoItem(mount, profile, tuning))
        inventory.setItem(TUNING_LEVEL_SLOT, items.levelUpgradeItem(mount, profile))
        speedSlots.forEach { (slot, percentage) ->
            inventory.setItem(slot, items.speedTuningItem(mount, profile, tuning, percentage))
        }
        if (mount.movement == MountMovement.WALKING) {
            stepSlots.forEach { (slot, hundredths) ->
                inventory.setItem(slot, items.stepHeightTuningItem(profile, tuning, hundredths))
            }
        } else {
            inventory.setItem(
                TUNING_NOT_APPLICABLE_SLOT,
                item(
                    if (mount.movement == MountMovement.FLYING) Material.FEATHER else Material.HEART_OF_THE_SEA,
                    config.guiText("progression.step-not-applicable-name", "<#969696>Подъём не используется"),
                    config.guiLines(
                        "progression.step-not-applicable-lore",
                        listOf("<#8c8c8c>Эта настройка доступна только пешим маунтам."),
                    ),
                ),
            )
        }
        sizeSlots.forEach { (slot, sizeId) ->
            mount.sizeOptions.firstOrNull { it.id == sizeId }?.let { option ->
                inventory.setItem(slot, items.sizeTuningItem(mount, profile, option))
            }
        }
        inventory.setItem(TUNING_RIDER_VIEW_SLOT, items.riderViewTuningItem(profile))
        inventory.setItem(
            TUNING_BACK_SLOT,
            styledItem(
                MountGuiItemRole.BACK,
                Material.BLUE_STAINED_GLASS_PANE,
                config.guiText("common.back-name", "<#92bed8>Назад"),
                actionLore(listOf(config.guiText("progression.back-description", "<#8c8c8c>Вернуться к маунту.")), "вернуться"),
            ),
        )
        player.openInventory(inventory)
        click(player)
    }

    private fun openSkins(player: Player, mount: MountDefinition) {
        val config = configProvider()
        val profile = ownership.profile(subject(player), mount)
        val allSkinIds = listOf(MountDefinition.DEFAULT_SKIN_ID) + mount.skins.map(MountSkinDefinition::id)
        val slots = SKIN_CONTENT_SLOTS.zip(allSkinIds).toMap()
        val holder = MountMenuHolder(MountScreen.SKINS, mount.id, skinsBySlot = slots)
        val inventory =
            Bukkit.createInventory(
                holder,
                SKINS_SIZE,
                component(config.skinsTitle.replace("<mount>", escape(mount.displayName))),
            )
        holder.backingInventory = inventory
        fill(inventory)
        slots.forEach { (slot, skinId) -> inventory.setItem(slot, items.skinItem(mount, profile, skinId)) }
        inventory.setItem(
            SKINS_BACK_SLOT,
            styledItem(
                MountGuiItemRole.BACK,
                Material.BLUE_STAINED_GLASS_PANE,
                config.guiText("common.back-name", "<#92bed8>Назад"),
                actionLore(listOf(config.guiText("skins.back-description", "<#8c8c8c>Вернуться к маунту.")), "вернуться"),
            ),
        )
        player.openInventory(inventory)
        click(player)
    }

    private fun openConfirm(player: Player, mount: MountDefinition, action: ConfirmAction) {
        val config = configProvider()
        val holder = MountMenuHolder(MountScreen.CONFIRM, mount.id, confirmAction = action)
        val inventory = Bukkit.createInventory(holder, CONFIRM_SIZE, component(config.confirmTitle))
        holder.backingInventory = inventory
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE)
        val (name, price, description) = confirmationDetails(mount, action)
        val currency = if (action is ConfirmAction.Ability) mount.ability(action.abilityId)?.currency ?: mount.currency else mount.currency
        val balance = wallet.walletForCurrency(currency)?.balanceMinor(player.uniqueId)
        val priceMinor = price.toExactMinor()
        holder.confirmEnabled = balance != null && balance >= priceMinor
        val economyLore =
            buildList {
                addAll(description)
                add("")
                add(moneyLine("common.price", "price", priceMinor, "Цена", currency = currency))
                if (balance == null) {
                    add(config.guiText("confirm.economy-unavailable-line", "<#c42323>Баланс сейчас недоступен"))
                } else {
                    add(moneyLine("common.balance", "balance", balance, "Баланс", currency = currency))
                    if (balance >= priceMinor) {
                        add(moneyLine("common.remaining", "remaining", balance - priceMinor, "Останется", currency = currency))
                    } else {
                        add(moneyLine("common.missing", "missing", priceMinor - balance, "Не хватает", "<#c42323>", currency = currency))
                    }
                }
            }
        inventory.setItem(CONFIRM_INFO_SLOT, item(Material.SUNFLOWER, name, economyLore))
        inventory.setItem(
            CONFIRM_CANCEL_SLOT,
            styledItem(
                MountGuiItemRole.CANCEL,
                Material.RED_CONCRETE,
                config.guiText("confirm.cancel-name", "<#c42323>Отменить"),
                actionLore(listOf(config.guiText("confirm.cancel-description", "<#8c8c8c>Вернуться без покупки.")), "отменить"),
            ),
        )
        inventory.setItem(
            CONFIRM_ACCEPT_SLOT,
            when {
                balance == null -> styledItem(MountGuiItemRole.CONFIRM, Material.GRAY_CONCRETE, config.guiText("confirm.unavailable-name", "<#c42323>Экономика недоступна"), emptyList())
                balance < priceMinor -> styledItem(MountGuiItemRole.CONFIRM, Material.GRAY_CONCRETE, config.guiText("confirm.insufficient-name", "<#c42323>Недостаточно средств"), emptyList())
                else ->
                    styledItem(
                        MountGuiItemRole.CONFIRM,
                        Material.ORANGE_CONCRETE,
                        config.guiText("confirm.accept-name", "<#ff9f0f>Подтвердить покупку"),
                        actionLore(listOf(moneyLine("common.debit", "price", priceMinor, "Будет списано", currency = currency)), "купить", "<#ff9f0f>"),
                    )
            },
        )
        player.openInventory(inventory)
        click(player)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MountMenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== event.view.topInventory) return
        when (event.click) {
            ClickType.LEFT -> Unit
            ClickType.RIGHT -> if (holder.screen != MountScreen.LIST) return
            else -> return
        }
        when (holder.screen) {
            MountScreen.LIST -> handleListClick(player, holder, event)
            MountScreen.DETAIL -> handleDetailClick(player, holder, event.rawSlot)
            MountScreen.PROGRESSION -> handleProgressionClick(player, holder, event.rawSlot)
            MountScreen.SKINS -> handleSkinClick(player, holder, event.rawSlot)
            MountScreen.CONFIRM -> handleConfirmClick(player, holder, event.rawSlot)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is MountMenuHolder) event.isCancelled = true
    }

    private fun handleListClick(player: Player, holder: MountMenuHolder, event: InventoryClickEvent) {
        when (event.rawSlot) {
            LIST_BACK_SLOT -> if (event.click == ClickType.LEFT) {
                player.closeInventory()
                configProvider().backCommand.takeIf(String::isNotBlank)?.let(player::performCommand)
                click(player)
            }
            LIST_PREVIOUS_SLOT -> if (event.click == ClickType.LEFT && holder.page > 0) {
                openListPage(player, holder.page - 1, holder.filter, holder.ownedOnly)
            }
            LIST_NEXT_SLOT -> if (event.click == ClickType.LEFT && holder.page + 1 < holder.pageCount) {
                openListPage(player, holder.page + 1, holder.filter, holder.ownedOnly)
            }
            LIST_FILTER_SLOT -> when (event.click) {
                ClickType.LEFT -> openListPage(player, 0, holder.filter.next(), holder.ownedOnly)
                ClickType.RIGHT -> openListPage(player, 0, holder.filter, !holder.ownedOnly)
                else -> Unit
            }
            else -> {
                val mount = holder.mountsBySlot[event.rawSlot]?.let(catalogProvider()::get) ?: return
                val profile = ownership.profile(subject(player), mount)
                when {
                    profile.unlocked && event.click == ClickType.LEFT -> summon(player, mount)
                    profile.unlocked && event.click == ClickType.RIGHT -> openDetail(player, mount.id)
                    !profile.unlocked && mount.price(1) != null && event.click == ClickType.LEFT -> openProgression(player, mount)
                }
            }
        }
    }

    private fun handleDetailClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val transfer = transfers()
        if (transfer != null && slot == transfer.detailSlot) {
            holder.mountId?.let { transfer.confirm(player, it) }; return
        }
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        holder.abilitiesBySlot[slot]?.let { abilityId ->
            val ability = mount.ability(abilityId) ?: return
            when {
                !profile.unlocked || profile.ownsAbility(abilityId) -> Unit
                !configProvider().purchasesEnabled -> Unit
                else -> openConfirm(player, mount, ConfirmAction.Ability(abilityId))
            }
            return
        }
        when (slot) {
            DETAIL_BACK_SLOT -> openList(player)
            DETAIL_FAVORITE_SLOT -> if (profile.unlocked && summons.favoriteMountId(player.uniqueId) != mount.id) selectFavorite(player, mount)
            DETAIL_SUMMON_SLOT -> if (profile.unlocked) summon(player, mount)
            DETAIL_SKINS_SLOT -> if (profile.unlocked) openSkins(player, mount)
            DETAIL_WHISTLE_SLOT -> {
                val hasFavorite = summons.favoriteMountId(player.uniqueId) != null
                val hasWhistle = player.inventory.contents.any(quickSummons::isWhistle)
                if (hasFavorite && !hasWhistle && configProvider().quickSummonWhistle && player.inventory.firstEmpty() >= 0) {
                    quickSummons.giveWhistle(player)
                    openDetail(player, mount.id)
                }
            }
            DETAIL_UPGRADE_SLOT -> {
                if (profile.unlocked || mount.price(1) != null) openProgression(player, mount)
            }
            DETAIL_GLOW_SLOT -> {
                when {
                    !profile.unlocked -> Unit
                    profile.glowOwned -> purchases.setGlowEnabled(subject(player), mount, !profile.glowEnabled) {
                        handlePurchaseResult(player, mount, it, purchase = false)
                    }
                    mount.glowPrice == null -> Unit
                    !configProvider().purchasesEnabled -> Unit
                    else -> openConfirm(player, mount, ConfirmAction.Glow)
                }
            }
        }
    }

    private fun handleProgressionClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        val tuning = configProvider().tuning
        when (slot) {
            TUNING_BACK_SLOT -> openDetail(player, mount.id)
            TUNING_LEVEL_SLOT -> {
                val target = profile.level + 1
                when {
                    target > mount.maxLevel || mount.price(target) == null -> Unit
                    !configProvider().purchasesEnabled -> Unit
                    else -> openConfirm(player, mount, ConfirmAction.Level(target))
                }
            }
            TUNING_RIDER_VIEW_SLOT -> {
                if (!profile.unlocked) return
                purchases.setRiderViewAutoHide(subject(player), mount, !(profile.riderViewAutoHide ?: true)) {
                    handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.PROGRESSION)
                }
            }
            else -> {
                holder.speedPercentagesBySlot[slot]?.let { percentage ->
                    if (!profile.unlocked || tuning.speedPercentage(profile.selectedSpeedPercentage) == percentage) return
                    purchases.setSpeedTuning(subject(player), mount, tuning, percentage) {
                        handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.PROGRESSION)
                    }
                    return
                }
                holder.stepHeightsBySlot[slot]?.let { hundredths ->
                    if (!profile.unlocked || hundredths !in tuning.availableStepHeightsHundredths(profile.level)) {
                        return
                    }
                    if (tuning.stepHeightHundredths(profile.level, profile.selectedStepHeightHundredths) == hundredths) return
                    purchases.setStepHeightTuning(subject(player), mount, tuning, hundredths) {
                        handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.PROGRESSION)
                    }
                    return
                }
                holder.sizeOptionsBySlot[slot]?.let { sizeId ->
                    val option = mount.sizeOptions.firstOrNull { it.id == sizeId } ?: return
                    if (
                        !profile.unlocked ||
                        option.minimumLevel > profile.level ||
                        !profile.ownsSize(option) ||
                        mount.effectiveSizeOption(profile.selectedSizeId, profile.level, profile.ownedSizeIds)?.id == sizeId
                    ) return
                    purchases.setSizeTuning(subject(player), mount, sizeId) {
                        handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.PROGRESSION)
                    }
                }
            }
        }
    }

    private fun handleSkinClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val skinId = holder.skinsBySlot[slot] ?: if (slot == SKINS_BACK_SLOT) return openDetail(player, mount.id) else return
        val profile = ownership.profile(subject(player), mount)
        if (profile.ownsSkin(skinId)) {
            if (profile.activeSkinId == skinId) return
            purchases.setActiveSkin(subject(player), mount, skinId) {
                handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.SKINS)
            }
            return
        }
        val skin = mount.skin(skinId) ?: return
        if (skin.price == null || !configProvider().purchasesEnabled) return
        openConfirm(player, mount, ConfirmAction.Skin(skinId))
    }

    private fun handleConfirmClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val action = holder.confirmAction ?: return openDetail(player, mount.id)
        when (slot) {
            CONFIRM_CANCEL_SLOT -> when (action) {
                is ConfirmAction.Skin -> openSkins(player, mount)
                is ConfirmAction.Level -> openProgression(player, mount)
                else -> openDetail(player, mount.id)
            }
            CONFIRM_ACCEPT_SLOT -> {
                if (!holder.confirmEnabled) return
                holder.confirmEnabled = false
                holder.backingInventory.setItem(
                    CONFIRM_ACCEPT_SLOT,
                    styledItem(
                        MountGuiItemRole.CONFIRM,
                        Material.GRAY_CONCRETE,
                        configProvider().guiText("confirm.loading-name", "<#969696>Покупка выполняется…"),
                        emptyList(),
                    ),
                )
                val callback: (MountPurchaseResult) -> Unit = { result ->
                    handlePurchaseResult(
                        player,
                        mount,
                        result,
                        reopen =
                            when (action) {
                                is ConfirmAction.Skin -> MountScreen.SKINS
                                is ConfirmAction.Level -> MountScreen.PROGRESSION
                                else -> MountScreen.DETAIL
                            },
                    )
                }
                when (action) {
                    is ConfirmAction.Level -> purchases.purchaseLevel(subject(player), mount, action.level, callback)
                    ConfirmAction.Glow -> purchases.purchaseGlow(subject(player), mount, callback)
                    is ConfirmAction.Skin -> mount.skin(action.skinId)?.let { purchases.purchaseSkin(subject(player), mount, it, callback) }
                    is ConfirmAction.Ability -> mount.ability(action.abilityId)?.let { purchases.purchaseAbility(subject(player), mount, it, callback) }
                }
            }
        }
    }

    private fun summon(player: Player, mount: MountDefinition) {
        val outcome = summons.summon(player, mount)
        if (outcome == MountSummonOutcome.SUCCESS) {
            player.closeInventory()
            return
        }
        summons.sendFeedback(player, outcome)
    }

    private fun selectFavorite(player: Player, mount: MountDefinition) {
        summons.selectFavorite(player, mount).whenComplete { outcome, failure ->
            Tasks.scheduler.runSync(
                Runnable {
                    if (!active || !player.isOnline) return@Runnable
                    when {
                        failure != null || outcome == MountFavoriteSelectionOutcome.PERSISTENCE_FAILED -> {
                            error("Unable to save favorite mount for {}: {}", player.name, failure?.javaClass?.simpleName ?: "persistence_failed")
                            send(player, "setting-failed", "<red>Не удалось сохранить настройку.")
                            bass(player)
                        }
                        outcome == MountFavoriteSelectionOutcome.NOT_UNLOCKED -> {
                            send(player, "not-unlocked", "<red>Сначала разблокируйте маунта.")
                            bass(player)
                        }
                        else -> {
                            val configured =
                                configProvider().message(
                                    "favorite-saved",
                                    "<green>Любимый маунт выбран: <white><mount><green>.",
                                )
                            player.sendMessage(component(configured.replace("<mount>", escape(mount.displayName))))
                            click(player)
                            openDetail(player, mount.id)
                        }
                    }
                },
            )
        }
    }

    private fun handlePurchaseResult(
        player: Player,
        mount: MountDefinition,
        result: MountPurchaseResult,
        purchase: Boolean = true,
        reopen: MountScreen = MountScreen.DETAIL,
    ) {
        if (!active || !player.isOnline) return
        when (result) {
            MountPurchaseResult.Success -> {
                val update = summons.refreshActive(player, mount)
                val (path, fallback) =
                    if (purchase && update == MountSessionUpdateResult.UNSAFE_APPEARANCE) {
                        "purchase-success-next-summon" to
                            "<yellow>Покупка сохранена; новый облик применится при следующем безопасном призыве."
                    } else if (purchase) {
                        "purchase-success" to "<green>Покупка сохранена!"
                    } else if (update == MountSessionUpdateResult.UNSAFE_APPEARANCE) {
                        "setting-next-summon" to "<yellow>Настройка сохранена и применится при следующем призыве."
                    } else if (update == MountSessionUpdateResult.APPLIED) {
                        "setting-applied" to "<green>Настройка сохранена и применена к активному маунту."
                    } else {
                        "setting-saved" to "<green>Настройка сохранена."
                    }
                send(player, path, fallback)
                click(player)
                reopen(player, mount, reopen)
            }
            MountPurchaseResult.Busy -> send(player, "purchase-busy", "<yellow>Предыдущая операция ещё выполняется.")
            MountPurchaseResult.AlreadyOwned -> {
                send(player, "already-owned", "<yellow>Это уже выбрано или куплено.")
                reopen(player, mount, reopen)
            }
            MountPurchaseResult.InvalidLevel -> send(player, "invalid-level", "<red>Сначала купите предыдущий уровень.")
            MountPurchaseResult.NotUnlocked -> send(player, "not-unlocked", "<red>Сначала разблокируйте маунта или облик.")
            MountPurchaseResult.NotForSale -> send(player, "not-for-sale", "<red>Это улучшение нельзя купить.")
            MountPurchaseResult.PurchasesDisabled -> purchasesDisabled(player)
            MountPurchaseResult.EconomyUnavailable -> send(player, "economy-unavailable", "<red>Экономика временно недоступна.")
            MountPurchaseResult.InsufficientFunds -> send(player, "not-enough-money", "<red>Недостаточно денег.")
            MountPurchaseResult.PaymentFailed -> send(player, "payment-failed", "<red>Платёж не прошёл. Деньги не списаны.")
            MountPurchaseResult.JournalUnavailable -> send(player, "journal-unavailable", "<red>Покупки временно приостановлены. Деньги не списаны.")
            MountPurchaseResult.PersistenceFailed -> send(player, "setting-failed", "<red>Не удалось сохранить настройку.")
            MountPurchaseResult.PersistenceFailedRefunded -> send(player, "save-failed-refunded", "<red>Покупка не сохранилась. Деньги полностью возвращены.")
            MountPurchaseResult.PersistenceFailedRefundFailed,
            MountPurchaseResult.ManualReview,
            -> {
                error("Mount purchase requires manual review for {} / {}", player.name, mount.id)
                send(player, "purchase-manual-review", "<dark_red>Покупка приостановлена для безопасной проверки. Повторно не покупайте; обратитесь к администрации.")
            }
        }
        if (result != MountPurchaseResult.Success && result != MountPurchaseResult.AlreadyOwned) bass(player)
    }

    private fun reopen(player: Player, mount: MountDefinition, screen: MountScreen) {
        when (screen) {
            MountScreen.PROGRESSION -> openProgression(player, mount)
            MountScreen.SKINS -> openSkins(player, mount)
            else -> openDetail(player, mount.id)
        }
    }

    private fun confirmationDetails(mount: MountDefinition, action: ConfirmAction): Triple<String, Double, List<String>> =
        when (action) {
            is ConfirmAction.Level -> {
                val level = mount.level(action.level)
                Triple(
                    copy(
                        "confirm.level-name",
                        "<#92bed8>Уровень <level>",
                        "level" to action.level.toString(),
                    ),
                    checkNotNull(level.price),
                    buildList {
                        add(copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName)))
                        add(
                            copy(
                                "confirm.maximum-speed",
                                "<#8c8c8c>Максимальная скорость: <#e6fff3><speed>",
                                "speed" to formatSpeed(level.speed),
                            ),
                        )
                        if (mount.movement == MountMovement.WALKING) {
                            add(
                                copy(
                                    "confirm.maximum-step",
                                    "<#8c8c8c>Максимальный подъём: <#e6fff3><step> блока",
                                    "step" to formatHeight(configProvider().tuning.maximumStepHeightHundredths(action.level) / 100.0),
                                ),
                            )
                        }
                    },
                )
            }
            ConfirmAction.Glow ->
                Triple(
                    copy("confirm.glow-name", "<#92bed8>Свечение"),
                    checkNotNull(mount.glowPrice),
                    listOf(
                        copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName)),
                        copy("confirm.glow-permanent-line", "<#8c8c8c>Косметика покупается навсегда."),
                    ),
                )
            is ConfirmAction.Skin -> {
                val skin = checkNotNull(mount.skin(action.skinId))
                Triple(
                    copy("confirm.info-name", "<#ffacd5><skin>", "skin" to escape(skin.displayName)),
                    checkNotNull(skin.price),
                    listOf(
                        copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName)),
                        copy("confirm.permanent-line", "<#8c8c8c>Облик покупается навсегда."),
                    ),
                )
            }
            is ConfirmAction.Ability -> {
                val ability = checkNotNull(mount.ability(action.abilityId))
                Triple(
                    copy(
                        "confirm.ability-name",
                        "<#92bed8><ability>",
                        "ability" to escape(ability.displayName),
                    ),
                    ability.price,
                    listOf(copy("confirm.mount-line", "<#8c8c8c>Маунт: <#e6fff3><mount>", "mount" to escape(mount.displayName))) +
                        ability.description.map {
                            copy(
                                "confirm.ability-description",
                                "<#8c8c8c><ability-description>",
                                "ability-description" to escape(it),
                            )
                        },
                )
            }
        }

    private fun purchasesDisabled(player: Player) {
        send(player, "purchases-disabled", "<yellow>Покупки маунтов доступны на спавне.")
        bass(player)
    }

    private fun fill(inventory: Inventory, fallbackMaterial: Material = Material.GRAY_STAINED_GLASS_PANE) {
        val background = styledItem(MountGuiItemRole.BACKGROUND, fallbackMaterial, " ", emptyList(), hideTooltip = true)
        for (slot in 0 until inventory.size) inventory.setItem(slot, background)
    }

    private fun styledItem(
        role: MountGuiItemRole,
        fallbackMaterial: Material,
        display: String,
        lore: List<String>,
        glint: Boolean = false,
        hideTooltip: Boolean = false,
    ): ItemStack {
        val style = configProvider().guiStyle(role)
        return item(style.material ?: fallbackMaterial, display, lore, glint, style.customModelData, hideTooltip)
    }

    private fun item(
        material: Material,
        display: String,
        lore: List<String>,
        glint: Boolean = false,
        customModelData: Int? = null,
        hideTooltip: Boolean = false,
    ): ItemStack =
        ItemStack(material).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(component(display))
                meta.lore(lore.map(::component))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
                meta.setEnchantmentGlintOverride(glint)
                meta.setHideTooltip(hideTooltip)
                @Suppress("DEPRECATION")
                customModelData?.let(meta::setCustomModelData)
            }
        }

    private fun subject(player: Player) = MountPermissionSubject(player.uniqueId, player.name, player::hasPermission)

    private fun tuningSizeSlots(count: Int): List<Int> = centeredSlots(TUNING_SIZE_SLOTS, count)
    private fun send(player: Player, path: String, fallback: String) = player.sendMessage(component(configProvider().message(path, fallback)))
    private fun click(player: Player) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)
    private fun bass(player: Player) = player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f)
    private fun component(text: String): Component = TextUtil.mm(text, true)
    private fun escape(value: String): String = value.replace("<", "\\<").replace(">", "\\>")
    private fun copy(path: String, fallback: String, vararg values: Pair<String, String>): String =
        fillTemplate(configProvider().guiText(path, fallback), *values)

    private fun copyLines(path: String, fallback: List<String>, vararg values: Pair<String, String>): List<String> =
        configProvider().guiLines(path, fallback).map { fillTemplate(it, *values) }

    private fun fillTemplate(template: String, vararg values: Pair<String, String>): String =
        values.fold(template) { current, (key, value) -> current.replace("<$key>", value) }

    private fun actionFooter(action: String, accent: String = "<#92bed8>"): String {
        val composedPath =
            when (action) {
                "вернуться" -> "common.footer-back"
                "открыть" -> "common.footer-open"
                "открыть покупку" -> "common.footer-open-purchase"
                "выбрать" -> "common.footer-select"
                "призвать" -> "common.footer-summon"
                "включить" -> "common.footer-enable"
                "выключить" -> "common.footer-disable"
                "получить" -> "common.footer-get"
                "купить" -> "common.footer-buy"
                "отменить" -> "common.footer-cancel"
                else -> null
            }
        composedPath?.let { path ->
            configProvider().guiText(path, "").takeIf(String::isNotEmpty)?.let { return it }
        }
        val path = if (accent == "<#ff9f0f>") "common.action-footer-warning" else "common.action-footer"
        val fallback = "<#8c8c8c>[${accent}▶<#8c8c8c>] ${accent}ЛКМ<#e6fff3> — <action>"
        return configProvider().guiText(path, fallback).replace("<action>", action)
    }

    private fun actionLore(
        content: List<String>,
        action: String,
        accent: String = "<#92bed8>",
    ): List<String> = content.dropLastWhile(String::isEmpty) + "" + actionFooter(action, accent)

    private fun moneyLine(
        path: String,
        placeholder: String,
        amountMinor: Long,
        fallbackLabel: String,
        fallbackColor: String = "<#8c8c8c>",
        currency: String = "vault",
    ): String =
        copy(
            "$path-$currency",
            "$fallbackColor$fallbackLabel: <#ffacd5><$placeholder> ${currencyLabel(configProvider(), currency)}",
            placeholder to TextUtil.formatAmount(amountMinor.minorToDouble()),
            "currency" to currencyLabel(configProvider(), currency),
        )

    private fun formatSpeed(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun formatHeight(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)

    companion object {
        private val LIST_SIZE get() = rows(ArcMenuSchema.MOUNT_LIST)
        private val LIST_CONTENT_SLOTS get() = region(ArcMenuSchema.MOUNT_LIST, ArcMenuSchema.MOUNT_ENTRIES)
        private val LIST_PREVIOUS_SLOT get() = slot(ArcMenuSchema.MOUNT_LIST, "previous")
        private val LIST_BACK_SLOT get() = slot(ArcMenuSchema.MOUNT_LIST, "back")
        private val LIST_FILTER_SLOT get() = slot(ArcMenuSchema.MOUNT_LIST, "filter")
        private val LIST_INFO_SLOT get() = slot(ArcMenuSchema.MOUNT_LIST, "info")
        private val LIST_NEXT_SLOT get() = slot(ArcMenuSchema.MOUNT_LIST, "next")

        private val DETAIL_SIZE get() = rows(ArcMenuSchema.MOUNT_DETAIL)
        private val DETAIL_ICON_SLOT get() = slot(ArcMenuSchema.MOUNT_DETAIL, "icon")
        private val DETAIL_FAVORITE_SLOT get() = slot(ArcMenuSchema.MOUNT_DETAIL, "favorite")
        private val DETAIL_UPGRADE_SLOT get() = slot(ArcMenuSchema.MOUNT_DETAIL, "upgrade")
        private val DETAIL_SUMMON_SLOT get() = slot(ArcMenuSchema.MOUNT_DETAIL, "summon")
        private val DETAIL_GLOW_SLOT get() = slot(ArcMenuSchema.MOUNT_DETAIL, "glow")
        private val DETAIL_SKINS_SLOT get() = slot(ArcMenuSchema.MOUNT_DETAIL, "skins")
        private val DETAIL_BACK_SLOT get() = slot(ArcMenuSchema.MOUNT_DETAIL, "back")
        private val DETAIL_WHISTLE_SLOT get() = slot(ArcMenuSchema.MOUNT_DETAIL, "whistle")
        private val DETAIL_ABILITY_SLOTS get() = region(ArcMenuSchema.MOUNT_DETAIL, ArcMenuSchema.MOUNT_ABILITIES)

        private val TUNING_SIZE get() = rows(ArcMenuSchema.MOUNT_PROGRESSION)
        private val TUNING_INFO_SLOT get() = slot(ArcMenuSchema.MOUNT_PROGRESSION, "info")
        private val TUNING_LEVEL_SLOT get() = slot(ArcMenuSchema.MOUNT_PROGRESSION, "level")
        private val TUNING_SPEED_SLOTS get() = region(ArcMenuSchema.MOUNT_PROGRESSION, ArcMenuSchema.MOUNT_SPEEDS)
        private val TUNING_STEP_SLOTS get() = region(ArcMenuSchema.MOUNT_PROGRESSION, ArcMenuSchema.MOUNT_STEPS)
        private val TUNING_SIZE_SLOTS get() = region(ArcMenuSchema.MOUNT_PROGRESSION, ArcMenuSchema.MOUNT_SIZES)
        private val TUNING_NOT_APPLICABLE_SLOT get() = TUNING_STEP_SLOTS[TUNING_STEP_SLOTS.size / 2]
        private val TUNING_BACK_SLOT get() = slot(ArcMenuSchema.MOUNT_PROGRESSION, "back")
        private val TUNING_RIDER_VIEW_SLOT get() = slot(ArcMenuSchema.MOUNT_PROGRESSION, "rider-view")
        private val SKINS_SIZE get() = rows(ArcMenuSchema.MOUNT_SKINS)
        private val SKIN_CONTENT_SLOTS get() = region(ArcMenuSchema.MOUNT_SKINS, ArcMenuSchema.MOUNT_SKIN_ENTRIES)
        private val SKINS_BACK_SLOT get() = slot(ArcMenuSchema.MOUNT_SKINS, "back")

        private val CONFIRM_SIZE get() = rows(ArcMenuSchema.MOUNT_CONFIRM)
        private val CONFIRM_CANCEL_SLOT get() = slot(ArcMenuSchema.MOUNT_CONFIRM, "cancel")
        private val CONFIRM_INFO_SLOT get() = slot(ArcMenuSchema.MOUNT_CONFIRM, "info")
        private val CONFIRM_ACCEPT_SLOT get() = slot(ArcMenuSchema.MOUNT_CONFIRM, "accept")

        private fun rows(menu: ru.arc.menu.MenuId) = ArcMenus.current().catalog.require(menu).rows * 9
        private fun slot(menu: ru.arc.menu.MenuId, id: String) = ArcMenus.current().catalog.require(menu).slot(id).index
        private fun region(menu: ru.arc.menu.MenuId, id: ru.arc.menu.MenuRegionId) =
            ArcMenus.current().catalog.require(menu).region(id).map { it.index }
    }
}

internal fun centeredDetailAbilitySlots(count: Int): List<Int> =
    centeredSlots(listOf(29, 30, 31, 32, 33), count.coerceAtMost(4))

private fun centeredSlots(available: List<Int>, count: Int): List<Int> {
    val requested = count.coerceIn(0, available.size)
    if (requested == 0) return emptyList()
    if (requested == available.size) return available
    if (available.size == 5) return when (requested) {
        1 -> listOf(available[2])
        2 -> listOf(available[1], available[3])
        3 -> listOf(available[0], available[2], available[4])
        4 -> listOf(available[0], available[1], available[3], available[4])
        else -> available
    }
    val pool = if (requested % 2 == 0 && available.size % 2 == 1) {
        available.filterIndexed { index, _ -> index != available.size / 2 }
    } else {
        available
    }
    return pool.drop((pool.size - requested) / 2).take(requested)
}

internal fun prioritizeUnlockedMounts(
    mounts: List<MountDefinition>,
    profile: (MountDefinition) -> MountProfile,
): List<MountDefinition> {
    val (unlocked, locked) = mounts.partition { mount -> profile(mount).unlocked }
    return unlocked + locked
}
