package ru.arc.mounts

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.roundToInt

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
    val filter: MountFilter = MountFilter.ALL,
    val ownedOnly: Boolean = false,
    val mountsBySlot: Map<Int, String> = emptyMap(),
    val skinsBySlot: Map<Int, String> = emptyMap(),
    val abilitiesBySlot: Map<Int, String> = emptyMap(),
    val speedPercentagesBySlot: Map<Int, Int> = emptyMap(),
    val stepHeightsBySlot: Map<Int, Int> = emptyMap(),
    val confirmAction: ConfirmAction? = null,
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
    private val sessions: MountSessionController,
) : Listener {
    @Volatile private var active = false

    fun start() {
        active = true
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun shutdown() {
        active = false
        plugin.server.onlinePlayers
            .filter { it.openInventory.topInventory.holder is MountMenuHolder }
            .forEach(Player::closeInventory)
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    fun openList(player: Player) = openListPage(player, 0, MountFilter.ALL, false)

    private fun openListPage(
        player: Player,
        requestedPage: Int,
        filter: MountFilter,
        ownedOnly: Boolean,
    ) {
        val catalog = catalogProvider()
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
        val holder = MountMenuHolder(MountScreen.LIST, page = page, filter = filter, ownedOnly = ownedOnly, mountsBySlot = slots)
        val inventory = Bukkit.createInventory(holder, LIST_SIZE, component(configProvider().listTitle))
        holder.backingInventory = inventory
        fill(inventory)
        slots.forEach { (slot, mountId) ->
            val mount = catalog[mountId] ?: return@forEach
            inventory.setItem(slot, mountIcon(mount, checkNotNull(profiles[mount])))
        }
        if (page > 0) inventory.setItem(LIST_PREVIOUS_SLOT, styledItem(MountGuiItemRole.PREVIOUS, Material.ARROW, "<#92bed8>Предыдущая страница", listOf("<#969696>${page}/${pageCount}")))
        if (page + 1 < pageCount) inventory.setItem(LIST_NEXT_SLOT, styledItem(MountGuiItemRole.NEXT, Material.ARROW, "<#92bed8>Следующая страница", listOf("<#969696>${page + 2}/${pageCount}")))
        inventory.setItem(
            LIST_FILTER_SLOT,
            styledItem(
                filter.styleRole,
                filter.icon,
                "<#92bed8>Каталог — <white>${filter.title}",
                listOf(
                    "<#e6fff3>ЛКМ <#8c8c8c>сменить категорию",
                    "<#e6fff3>ПКМ <#8c8c8c>${if (ownedOnly) "показать всю коллекцию" else "оставить только полученных"}",
                    "",
                    "<#969696>Показано <white>${visible.size}<#969696> из <white>${catalog.all.size}",
                ),
                glint = ownedOnly,
            ),
        )
        inventory.setItem(
            LIST_INFO_SLOT,
            styledItem(
                MountGuiItemRole.INFO,
                Material.BOOK,
                "<#92bed8>Путеводитель по коллекции",
                listOf(
                    "<#e6fff3>ЛКМ <#8c8c8c>призвать полученного маунта",
                    "<#e6fff3>ПКМ <#8c8c8c>открыть развитие и облики",
                    "",
                    "<#92bed8>Полёт",
                    "<#8c8c8c>Space — вверх",
                    "<#8c8c8c>Shift — вниз",
                    "<#8c8c8c>Взгляд вниз скрывает маунта из кадра",
                    "",
                    "<#ffacd5>Двойной Shift <#8c8c8c>спешиться",
                ),
            ),
        )
        inventory.setItem(LIST_BALANCE_SLOT, balanceItem(player))
        inventory.setItem(LIST_BACK_SLOT, styledItem(MountGuiItemRole.BACK, Material.BLUE_STAINED_GLASS_PANE, "<#92bed8>Назад", listOf("<#8c8c8c>Вернуться в главное меню")))
        player.openInventory(inventory)
        click(player)
    }

    fun openDetail(player: Player, mountId: String) {
        val config = configProvider()
        val mount = catalogProvider()[mountId] ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        val abilitySlots = DETAIL_ABILITY_SLOTS.zip(mount.abilities.upgrades.map(MountAbilityUpgradeDefinition::id)).toMap()
        val holder = MountMenuHolder(MountScreen.DETAIL, mount.id, abilitiesBySlot = abilitySlots)
        val inventory = Bukkit.createInventory(holder, DETAIL_SIZE, component(config.detailTitle.replace("<mount>", escape(mount.displayName))))
        holder.backingInventory = inventory
        fill(inventory)
        inventory.setItem(DETAIL_ICON_SLOT, mountIcon(mount, profile, detailed = true))
        inventory.setItem(DETAIL_UPGRADE_SLOT, upgradeItem(mount, profile))
        inventory.setItem(DETAIL_SUMMON_SLOT, summonItem(profile, config.sessionDuration))
        inventory.setItem(DETAIL_GLOW_SLOT, glowItem(mount, profile))
        inventory.setItem(DETAIL_SKINS_SLOT, skinsItem(mount, profile))
        abilitySlots.forEach { (slot, abilityId) ->
            mount.ability(abilityId)?.let { inventory.setItem(slot, abilityItem(profile, it)) }
        }
        inventory.setItem(DETAIL_BACK_SLOT, styledItem(MountGuiItemRole.BACK, Material.BLUE_STAINED_GLASS_PANE, "<aqua>Назад", listOf("<gray>К списку маунтов")))
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
        val holder =
            MountMenuHolder(
                MountScreen.PROGRESSION,
                mount.id,
                speedPercentagesBySlot = speedSlots,
                stepHeightsBySlot = stepSlots,
            )
        val inventory =
            Bukkit.createInventory(
                holder,
                TUNING_SIZE,
                component(config.progressionTitle.replace("<mount>", escape(mount.displayName))),
            )
        holder.backingInventory = inventory
        fill(inventory)
        inventory.setItem(TUNING_INFO_SLOT, progressionInfoItem(mount, profile, tuning))
        inventory.setItem(TUNING_LEVEL_SLOT, levelUpgradeItem(mount, profile))
        speedSlots.forEach { (slot, percentage) ->
            inventory.setItem(slot, speedTuningItem(mount, profile, tuning, percentage))
        }
        if (mount.movement == MountMovement.WALKING) {
            stepSlots.forEach { (slot, hundredths) ->
                inventory.setItem(slot, stepHeightTuningItem(profile, tuning, hundredths))
            }
        } else {
            inventory.setItem(
                TUNING_NOT_APPLICABLE_SLOT,
                item(
                    if (mount.movement == MountMovement.FLYING) Material.FEATHER else Material.HEART_OF_THE_SEA,
                    "<gray>Высота шага не используется",
                    listOf("<gray>Эта настройка доступна только пешим маунтам."),
                ),
            )
        }
        inventory.setItem(
            TUNING_BACK_SLOT,
            styledItem(MountGuiItemRole.BACK, Material.BLUE_STAINED_GLASS_PANE, "<aqua>Назад", listOf("<gray>К маунту")),
        )
        player.openInventory(inventory)
        click(player)
    }

    private fun openSkins(player: Player, mount: MountDefinition) {
        val profile = ownership.profile(subject(player), mount)
        val allSkinIds = listOf(MountDefinition.DEFAULT_SKIN_ID) + mount.skins.map(MountSkinDefinition::id)
        val slots = SKIN_CONTENT_SLOTS.zip(allSkinIds).toMap()
        val holder = MountMenuHolder(MountScreen.SKINS, mount.id, skinsBySlot = slots)
        val inventory =
            Bukkit.createInventory(
                holder,
                SKINS_SIZE,
                component(configProvider().skinsTitle.replace("<mount>", escape(mount.displayName))),
            )
        holder.backingInventory = inventory
        fill(inventory)
        slots.forEach { (slot, skinId) -> inventory.setItem(slot, skinItem(mount, profile, skinId)) }
        inventory.setItem(SKINS_BACK_SLOT, styledItem(MountGuiItemRole.BACK, Material.BLUE_STAINED_GLASS_PANE, "<aqua>Назад", listOf("<gray>К развитию маунта")))
        player.openInventory(inventory)
        click(player)
    }

    private fun openConfirm(player: Player, mount: MountDefinition, action: ConfirmAction) {
        val holder = MountMenuHolder(MountScreen.CONFIRM, mount.id, confirmAction = action)
        val inventory = Bukkit.createInventory(holder, CONFIRM_SIZE, component("<dark_gray><bold>Подтверждение покупки"))
        holder.backingInventory = inventory
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE)
        val (name, price, description) = confirmationDetails(mount, action)
        inventory.setItem(CONFIRM_INFO_SLOT, item(Material.SUNFLOWER, name, description + listOf("", "<gray>Цена: <yellow>${TextUtil.formatAmount(price)}<white>💰")))
        inventory.setItem(CONFIRM_CANCEL_SLOT, styledItem(MountGuiItemRole.CANCEL, Material.RED_CONCRETE, "<red>Отмена", listOf("<gray>Вернуться без покупки")))
        inventory.setItem(CONFIRM_ACCEPT_SLOT, styledItem(MountGuiItemRole.CONFIRM, Material.LIME_CONCRETE, "<green>Подтвердить", listOf("<gray>С баланса будет списано", "<yellow>${TextUtil.formatAmount(price)}<white>💰"), glint = true))
        player.openInventory(inventory)
        click(player)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MountMenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== event.view.topInventory) return
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
            LIST_BACK_SLOT -> {
                player.closeInventory()
                configProvider().backCommand.takeIf(String::isNotBlank)?.let(player::performCommand)
                click(player)
            }
            LIST_PREVIOUS_SLOT -> openListPage(player, holder.page - 1, holder.filter, holder.ownedOnly)
            LIST_NEXT_SLOT -> openListPage(player, holder.page + 1, holder.filter, holder.ownedOnly)
            LIST_FILTER_SLOT -> {
                if (event.isRightClick) openListPage(player, 0, holder.filter, !holder.ownedOnly)
                else openListPage(player, 0, holder.filter.next(), holder.ownedOnly)
            }
            else -> {
                val mount = holder.mountsBySlot[event.rawSlot]?.let(catalogProvider()::get) ?: return
                val profile = ownership.profile(subject(player), mount)
                if (profile.unlocked && event.isLeftClick) summon(player, mount, profile) else openDetail(player, mount.id)
            }
        }
    }

    private fun handleDetailClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        holder.abilitiesBySlot[slot]?.let { abilityId ->
            val ability = mount.ability(abilityId) ?: return
            when {
                !profile.unlocked || profile.ownsAbility(abilityId) -> bass(player)
                !configProvider().purchasesEnabled -> purchasesDisabled(player)
                else -> openConfirm(player, mount, ConfirmAction.Ability(abilityId))
            }
            return
        }
        when (slot) {
            DETAIL_BACK_SLOT -> openList(player)
            DETAIL_SUMMON_SLOT -> if (profile.unlocked) summon(player, mount, profile) else bass(player)
            DETAIL_SKINS_SLOT -> if (profile.unlocked) openSkins(player, mount) else bass(player)
            DETAIL_UPGRADE_SLOT -> {
                openProgression(player, mount)
            }
            DETAIL_GLOW_SLOT -> {
                when {
                    !profile.unlocked -> bass(player)
                    profile.glowOwned -> purchases.setGlowEnabled(subject(player), mount, !profile.glowEnabled) {
                        handlePurchaseResult(player, mount, it, purchase = false)
                    }
                    mount.glowPrice == null -> bass(player)
                    !configProvider().purchasesEnabled -> purchasesDisabled(player)
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
                    target > mount.maxLevel || mount.price(target) == null -> bass(player)
                    !configProvider().purchasesEnabled -> purchasesDisabled(player)
                    else -> openConfirm(player, mount, ConfirmAction.Level(target))
                }
            }
            else -> {
                holder.speedPercentagesBySlot[slot]?.let { percentage ->
                    purchases.setSpeedTuning(subject(player), mount, tuning, percentage) {
                        handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.PROGRESSION)
                    }
                    return
                }
                holder.stepHeightsBySlot[slot]?.let { hundredths ->
                    if (!profile.unlocked || hundredths !in tuning.availableStepHeightsHundredths(profile.level)) {
                        bass(player)
                        return
                    }
                    purchases.setStepHeightTuning(subject(player), mount, tuning, hundredths) {
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
            purchases.setActiveSkin(subject(player), mount, skinId) {
                handlePurchaseResult(player, mount, it, purchase = false, reopen = MountScreen.SKINS)
            }
            return
        }
        val skin = mount.skin(skinId) ?: return
        if (skin.price == null) return bass(player)
        if (!configProvider().purchasesEnabled) return purchasesDisabled(player)
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

    private fun summon(player: Player, mount: MountDefinition, profile: MountProfile) {
        val level = mount.level(profile.level)
        val tuning = configProvider().tuning
        val skin = mount.skin(profile.activeSkinId)
        val result =
            sessions.spawn(
                player = player,
                definition = mount,
                speed = tuning.speed(level.speed, profile.selectedSpeedPercentage),
                walkingStepHeight = tuning.stepHeight(profile.level, profile.selectedStepHeightHundredths),
                handlingMultiplier = level.handlingMultiplier,
                sprintMultiplier = level.sprintMultiplier,
                durationMillis = configProvider().sessionDuration.toMillis(),
                glow = profile.glowEnabled,
                scaleMultiplier = level.scaleMultiplier,
                skin = skin,
                abilityUpgrades = mount.abilities.upgrades.filter { profile.ownsAbility(it.id) },
            )
        if (result == MountSpawnResult.SUCCESS) {
            player.closeInventory()
            return
        }
        val (path, fallback) =
            when (result) {
                MountSpawnResult.ALREADY_RIDING -> "already-riding" to "<red>Вы уже используете маунта."
                MountSpawnResult.ALREADY_IN_VEHICLE -> "already-in-vehicle" to "<red>Сначала покиньте текущее транспортное средство."
                MountSpawnResult.WORLD_NOT_ALLOWED -> "world-not-allowed" to "<red>В этом мире маунты недоступны."
                MountSpawnResult.WATER_REQUIRED -> "water-required" to "<aqua>Водного маунта можно призвать только в воде."
                MountSpawnResult.COOLDOWN -> "summon-cooldown" to "<yellow>Подождите немного перед повторным призывом."
                MountSpawnResult.INVALID_ENTITY,
                MountSpawnResult.SPAWN_FAILED,
                -> "spawn-failed" to "<red>Не удалось призвать маунта."
                MountSpawnResult.SUCCESS -> return
            }
        send(player, path, fallback)
        bass(player)
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
                send(player, if (purchase) "purchase-success" else "setting-saved", if (purchase) "<green>Покупка сохранена!" else "<green>Настройка сохранена.")
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

    private fun mountIcon(mount: MountDefinition, profile: MountProfile, detailed: Boolean = false): ItemStack {
        val lore = buildList {
            add(if (profile.unlocked) "<green>✔ Получен" else "<red>✘ Пока не получен")
            add("${mount.rarity.color}${mount.rarity.displayName}")
            if (detailed && mount.description.isNotEmpty()) {
                add("")
                mount.description.forEach { add("<#e6fff3>${escape(it)}") }
            }
            add("")
            add("<#92bed8>Характер")
            add("<#8c8c8c>Тип  ${movementColor(mount.movement)}${mount.movement.displayName}")
            mount.abilities.displayNames.forEach { ability ->
                add("<#8c8c8c>Особенность  <#ffacd5>${escape(ability)}")
            }
            if (profile.unlocked) {
                val tuning = configProvider().tuning
                val selectedSpeed = tuning.speedPercentage(profile.selectedSpeedPercentage)
                add("")
                add("<#92bed8>Ваш профиль")
                add("<#8c8c8c>Уровень  <white>${profile.level}<#969696>/${mount.maxLevel}")
                add("<#8c8c8c>Скорость  <white>${formatSpeed(tuning.speed(mount.speed(profile.level), profile.selectedSpeedPercentage))} <#969696>($selectedSpeed%)")
                if (mount.movement == MountMovement.WALKING) {
                    add("<#8c8c8c>Подъём  <white>${formatHeight(tuning.stepHeight(profile.level, profile.selectedStepHeightHundredths))} блока")
                }
                add("<#8c8c8c>Облик  <white>${escape(skinName(mount, profile.activeSkinId))}")
            } else {
                add("")
                add("<#92bed8>Как получить")
                add("<#e6fff3>${escape(mount.acquisition)}")
            }
            if (!detailed) {
                add("")
                if (profile.unlocked) {
                    add("<#e6fff3>ЛКМ <#8c8c8c>призвать")
                    add("<#92bed8>ПКМ <#8c8c8c>развитие и облики")
                } else add("<#e6fff3>Нажмите <#8c8c8c>изучить маунта")
            }
        }
        return item(
            Material.matchMaterial(mount.iconMaterial) ?: Material.PAPER,
            if (profile.unlocked) "<#ffacd5>${escape(mount.displayName)}" else "<#969696>${escape(mount.displayName)}",
            lore,
            glint = profile.unlocked,
        )
    }

    private fun upgradeItem(mount: MountDefinition, profile: MountProfile): ItemStack {
        val tuning = configProvider().tuning
        return item(
            Material.COMPARATOR,
            "<gold>Развитие и тюнинг",
            buildList {
                add("<gray>Уровень: <yellow>${profile.level}<gray>/${mount.maxLevel}")
                if (profile.unlocked) {
                    add("<gray>Скорость: <white>${tuning.speedPercentage(profile.selectedSpeedPercentage)}% <dark_gray>от доступной")
                    if (mount.movement == MountMovement.WALKING) {
                        add("<gray>Высота шага: <white>${formatHeight(tuning.stepHeight(profile.level, profile.selectedStepHeightHundredths))} блока")
                    }
                }
                add("")
                add("<gray>Повышайте уровень и настраивайте")
                add("<gray>характеристики под себя.")
                add("")
                add("<green>Нажмите, чтобы открыть")
            },
            glint = profile.level >= mount.maxLevel,
        )
    }

    private fun levelUpgradeItem(mount: MountDefinition, profile: MountProfile): ItemStack {
        val next = profile.level + 1
        return when {
            profile.level >= mount.maxLevel -> item(Material.NETHER_STAR, "<gold><bold>Максимальный уровень", listOf("<gray>Все пределы характеристик открыты.", "<gray>Текущие значения можно менять ниже."), glint = true)
            mount.price(next) == null -> item(Material.BARRIER, if (profile.unlocked) "<red>Особое улучшение" else "<red>Особый маунт", listOf("<gray>Этот уровень получается другим способом.", "<white>${escape(mount.acquisition)}"))
            else -> {
                val level = mount.level(next)
                val tuning = configProvider().tuning
                val previousSpeed = if (profile.level > 0) mount.speed(profile.level) else null
                val delta = previousSpeed?.let { (((level.speed / it) - 1.0) * 100.0).roundToInt() }
                val final = next == mount.maxLevel
                item(
                    if (final) Material.NETHER_STAR else Material.EMERALD,
                    if (final) "<gold><bold>ФИНАЛЬНЫЙ РЫВОК" else if (profile.unlocked) "<green>Улучшить до уровня $next" else "<green>Получить маунта",
                    buildList {
                        if (previousSpeed != null) add("<gray>Скорость: <white>${formatSpeed(previousSpeed)} <dark_gray>→ <${if (final) "gold" else "green"}>${formatSpeed(level.speed)}")
                        else add("<gray>Скорость: <white>${formatSpeed(level.speed)}")
                        if (delta != null) add("<gray>Прирост: <${if (final) "gold" else "green"}>+$delta%")
                        add("<gray>Управляемость: <white>×${formatMultiplier(level.handlingMultiplier)}")
                        if (level.sprintMultiplier > 1.0) add("<gray>Форсаж: <white>×${formatMultiplier(level.sprintMultiplier)}")
                        if (mount.movement == MountMovement.WALKING) {
                            val previousHeight = if (profile.level > 0) tuning.maximumStepHeightHundredths(profile.level) else null
                            val nextHeight = tuning.maximumStepHeightHundredths(next)
                            if (previousHeight == null) {
                                add("<gray>Макс. подъём: <white>${formatHeight(nextHeight / 100.0)} блока")
                            } else if (previousHeight != nextHeight) {
                                add("<gray>Макс. подъём: <white>${formatHeight(previousHeight / 100.0)} <dark_gray>→ <green>${formatHeight(nextHeight / 100.0)} блока")
                            }
                        }
                        add("")
                        add("<gray>Цена: <yellow>${TextUtil.formatAmount(checkNotNull(level.price))}<white>💰")
                        if (final) add("<gold>Дорогая престижная цель — и реально быстрый маунт.")
                        add("")
                        add(if (configProvider().purchasesEnabled) "<green>Нажмите для подтверждения" else "<yellow>Покупки доступны на спавне")
                    },
                    glint = final,
                )
            }
        }
    }

    private fun progressionInfoItem(
        mount: MountDefinition,
        profile: MountProfile,
        tuning: MountTuningDefinition,
    ): ItemStack =
        item(
            Material.RECOVERY_COMPASS,
            "<gold>Профиль движения",
            buildList {
                add("<gray>Уровень открывает максимум характеристик.")
                add("<gray>Вы сами выбираете значение внутри предела.")
                add("")
                if (!profile.unlocked) {
                    add("<red>Сначала получите маунта ниже.")
                } else {
                    val levelSpeed = mount.speed(profile.level)
                    add("<gray>Скорость: <white>${formatSpeed(tuning.speed(levelSpeed, profile.selectedSpeedPercentage))} <dark_gray>/ ${formatSpeed(levelSpeed)}")
                    if (mount.movement == MountMovement.WALKING) {
                        add("<gray>Высота шага: <white>${formatHeight(tuning.stepHeight(profile.level, profile.selectedStepHeightHundredths))} <dark_gray>/ ${formatHeight(tuning.maximumStepHeightHundredths(profile.level) / 100.0)} блока")
                    }
                    add("")
                    add("<dark_gray>Тюнинг бесплатный и сохраняется между серверами.")
                }
            },
        )

    private fun speedTuningItem(
        mount: MountDefinition,
        profile: MountProfile,
        tuning: MountTuningDefinition,
        percentage: Int,
    ): ItemStack {
        val selected = profile.unlocked && tuning.speedPercentage(profile.selectedSpeedPercentage) == percentage
        val material =
            if (!profile.unlocked) Material.GRAY_DYE
            else SPEED_TUNING_MATERIALS[tuning.speedPercentages.indexOf(percentage).coerceAtLeast(0)]
        return item(
            material,
            if (selected) "<green>Скорость: $percentage%" else "<aqua>Скорость: $percentage%",
            buildList {
                if (profile.unlocked) {
                    add("<gray>Фактически: <white>${formatSpeed(mount.speed(profile.level) * percentage / 100.0)}")
                    add("<gray>От максимума уровня: <white>$percentage%")
                    add("")
                    add(if (selected) "<green>Выбрано" else "<green>Нажмите, чтобы выбрать")
                } else {
                    add("<red>Сначала получите маунта")
                }
            },
            glint = selected,
        )
    }

    private fun stepHeightTuningItem(
        profile: MountProfile,
        tuning: MountTuningDefinition,
        hundredths: Int,
    ): ItemStack {
        val available = profile.unlocked && hundredths <= tuning.maximumStepHeightHundredths(profile.level)
        val selected = available && tuning.stepHeightHundredths(profile.level, profile.selectedStepHeightHundredths) == hundredths
        val requiredLevel =
            tuning.walkingMaxStepHeightByLevelHundredths.indexOfFirst { it >= hundredths }
                .takeIf { it >= 0 }
                ?.plus(1)
        val material =
            if (!available) Material.BARRIER
            else STEP_TUNING_MATERIALS[tuning.walkingStepHeightsHundredths.indexOf(hundredths).coerceAtLeast(0)]
        return item(
            material,
            if (selected) "<green>Подъём: ${formatHeight(hundredths / 100.0)} блока" else "<yellow>Подъём: ${formatHeight(hundredths / 100.0)} блока",
            buildList {
                add("<gray>Маунт автоматически заходит")
                add("<gray>на препятствия этой высоты.")
                if (hundredths >= 300) {
                    add("<yellow>Высокий подъём работает как карабканье")
                    add("<yellow>и может быть неудобен под низким потолком.")
                }
                add("")
                when {
                    !profile.unlocked -> add("<red>Сначала получите маунта")
                    !available -> add("<red>Откроется на уровне ${requiredLevel ?: "выше"}")
                    selected -> add("<green>Выбрано")
                    else -> add("<green>Нажмите, чтобы выбрать")
                }
            },
            glint = selected,
        )
    }

    private fun summonItem(profile: MountProfile, duration: Duration): ItemStack =
        if (profile.unlocked) item(Material.SADDLE, "<gold>Призвать маунта", listOf("<gray>Максимальная сессия: <white>${formatDuration(duration)}", "<gray>Без движения маунт исчезнет через <white>${formatDuration(configProvider().idleTimeout)}", "", "<green>Нажмите, чтобы призвать"))
        else item(Material.BARRIER, "<red>Маунт недоступен", listOf("<gray>Сначала получите первый уровень."))

    private fun glowItem(mount: MountDefinition, profile: MountProfile): ItemStack =
        when {
            !profile.unlocked -> item(Material.GRAY_DYE, "<gray>Свечение недоступно", listOf("<gray>Сначала получите маунта."))
            profile.glowOwned -> item(if (profile.glowEnabled) Material.GLOW_INK_SAC else Material.INK_SAC, if (profile.glowEnabled) "<red>Выключить свечение" else "<green>Включить свечение", listOf("<gray>Свечение куплено навсегда.", "", "<green>Нажмите для переключения"), glint = profile.glowEnabled)
            mount.glowPrice != null -> item(Material.GLOW_INK_SAC, "<green>Купить свечение", listOf("<gray>Цена: <yellow>${TextUtil.formatAmount(mount.glowPrice)}<white>💰", "", if (configProvider().purchasesEnabled) "<green>Нажмите для подтверждения" else "<yellow>Покупки доступны на спавне"))
            else -> item(Material.BARRIER, "<red>Свечение недоступно", listOf("<gray>Это украшение не продаётся."))
        }

    private fun skinsItem(mount: MountDefinition, profile: MountProfile): ItemStack =
        if (!profile.unlocked) item(Material.GRAY_DYE, "<gray>Облики недоступны", listOf("<gray>Сначала получите маунта."))
        else item(Material.LEATHER_HORSE_ARMOR, "<light_purple>Облики и украшения", listOf("<gray>Выбран: <white>${escape(skinName(mount, profile.activeSkinId))}", "<gray>Получено: <white>${profile.ownedSkinIds.size + 1}/${mount.skins.size + 1}", "", "<green>Нажмите, чтобы открыть"), glint = profile.activeSkinId != MountDefinition.DEFAULT_SKIN_ID)

    private fun abilityItem(
        profile: MountProfile,
        ability: MountAbilityUpgradeDefinition,
    ): ItemStack {
        val owned = profile.ownsAbility(ability.id)
        return item(
            Material.matchMaterial(ability.iconMaterial) ?: Material.PAPER,
            if (owned) "<aqua>${escape(ability.displayName)}" else "<green>${escape(ability.displayName)}",
            buildList {
                ability.description.forEach { add("<gray>${escape(it)}") }
                if (ability.speedMultiplier > 1.0) {
                    add("<gray>Скорость маунта: <aqua>+${((ability.speedMultiplier - 1.0) * 100.0).roundToInt()}%")
                }
                add("")
                when {
                    !profile.unlocked -> add("<red>Сначала получите маунта")
                    owned -> add("<green>Куплено навсегда")
                    else -> {
                        add("<gray>Цена: <yellow>${TextUtil.formatAmount(ability.price)}<white>💰")
                        add(if (configProvider().purchasesEnabled) "<green>Нажмите для подтверждения" else "<yellow>Покупки доступны на спавне")
                    }
                }
            },
            glint = owned,
        )
    }

    private fun skinItem(mount: MountDefinition, profile: MountProfile, skinId: String): ItemStack {
        if (skinId == MountDefinition.DEFAULT_SKIN_ID) {
            return item(mount.appearance.equipment.values.firstOrNull()?.let(Material::matchMaterial) ?: Material.SADDLE, "<white>Классический", appearanceLore(mount.appearance) + listOf("", if (profile.activeSkinId == skinId) "<green>Выбран" else "<green>Нажмите, чтобы выбрать"), glint = profile.activeSkinId == skinId)
        }
        val skin = checkNotNull(mount.skin(skinId))
        val owned = profile.ownsSkin(skinId)
        return item(
            Material.matchMaterial(skin.iconMaterial) ?: Material.LEATHER_HORSE_ARMOR,
            if (owned) "<light_purple>${escape(skin.displayName)}" else "<gray>${escape(skin.displayName)}",
            appearanceLore(skin.appearance) + buildList {
                skin.trail?.let { add("<gray>След: <white>${escape(it.particle.lowercase().replace('_', ' '))}") }
                add("")
                when {
                    profile.activeSkinId == skinId -> add("<green>Выбран")
                    owned -> add("<green>Нажмите, чтобы выбрать")
                    skin.price != null -> {
                        add("<gray>Цена: <yellow>${TextUtil.formatAmount(skin.price)}<white>💰")
                        add(if (configProvider().purchasesEnabled) "<green>Нажмите для подтверждения" else "<yellow>Покупки доступны на спавне")
                    }
                    else -> add("<gold>Особая награда")
                }
            },
            glint = profile.activeSkinId == skinId,
        )
    }

    private fun appearanceLore(appearance: MountAppearance): List<String> = buildList {
        add("<gray>Возраст: <white>${if (appearance.baby) "малыш" else "взрослый"}")
        if (appearance.scale != 1.0) add("<gray>Размер: <white>×${formatMultiplier(appearance.scale)}")
        appearance.variant?.let { add("<gray>Вариант: <white>${escape(it.lowercase().replace('_', ' '))}") }
        if (appearance.equipment.isNotEmpty()) add("<gray>Экипировка: <white>${appearance.equipment.size} предмет(а)")
    }

    private fun confirmationDetails(mount: MountDefinition, action: ConfirmAction): Triple<String, Double, List<String>> =
        when (action) {
            is ConfirmAction.Level -> {
                val level = mount.level(action.level)
                Triple(
                    if (action.level == mount.maxLevel) "<gold><bold>ФИНАЛЬНЫЙ РЫВОК" else "<green>Уровень ${action.level}",
                    checkNotNull(level.price),
                    buildList {
                        add("<gray>${escape(mount.displayName)}")
                        add("<gray>Максимальная скорость: <white>${formatSpeed(level.speed)}")
                        if (mount.movement == MountMovement.WALKING) {
                            add("<gray>Максимальный подъём: <white>${formatHeight(configProvider().tuning.maximumStepHeightHundredths(action.level) / 100.0)} блока")
                        }
                    },
                )
            }
            ConfirmAction.Glow -> Triple("<green>Свечение", checkNotNull(mount.glowPrice), listOf("<gray>${escape(mount.displayName)}", "<gray>Косметика покупается навсегда"))
            is ConfirmAction.Skin -> {
                val skin = checkNotNull(mount.skin(action.skinId))
                Triple("<light_purple>${escape(skin.displayName)}", checkNotNull(skin.price), listOf("<gray>${escape(mount.displayName)}", "<gray>Облик покупается навсегда"))
            }
            is ConfirmAction.Ability -> {
                val ability = checkNotNull(mount.ability(action.abilityId))
                Triple(
                    "<aqua>${escape(ability.displayName)}",
                    ability.price,
                    listOf("<gray>${escape(mount.displayName)}") + ability.description.map { "<gray>${escape(it)}" },
                )
            }
        }

    private fun balanceItem(player: Player): ItemStack {
        val balance = wallet.balanceMinor(player.uniqueId)
        return styledItem(MountGuiItemRole.BALANCE, Material.SUNFLOWER, "<#ffacd5>Баланс", listOf(if (balance != null) "<white>${TextUtil.formatAmount(balance.minorToDouble())}<#ffacd5>💰" else "<red>Экономика недоступна"))
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
    private fun send(player: Player, path: String, fallback: String) = player.sendMessage(component(configProvider().message(path, fallback)))
    private fun click(player: Player) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)
    private fun bass(player: Player) = player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f)
    private fun component(text: String): Component = TextUtil.mm(text, true)
    private fun escape(value: String): String = value.replace("<", "\\<").replace(">", "\\>")
    private fun movementColor(movement: MountMovement): String = when (movement) {
        MountMovement.WALKING -> "<gray>"
        MountMovement.FLYING -> "<green>"
        MountMovement.SWIMMING -> "<aqua>"
    }
    private fun skinName(mount: MountDefinition, skinId: String): String = if (skinId == MountDefinition.DEFAULT_SKIN_ID) "Классический" else mount.skin(skinId)?.displayName ?: "Классический"
    private fun formatSpeed(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun formatHeight(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)
    private fun formatMultiplier(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun formatDuration(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(1L)
        return when {
            seconds % 3_600L == 0L -> "${seconds / 3_600L} ч"
            seconds % 60L == 0L -> "${seconds / 60L} мин"
            else -> "$seconds сек"
        }
    }

    companion object {
        private const val LIST_SIZE = 54
        private val LIST_CONTENT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
        private const val LIST_PREVIOUS_SLOT = 48
        private const val LIST_BACK_SLOT = 45
        private const val LIST_FILTER_SLOT = 49
        private const val LIST_INFO_SLOT = 4
        private const val LIST_NEXT_SLOT = 50
        private const val LIST_BALANCE_SLOT = 53

        private const val DETAIL_SIZE = 45
        private const val DETAIL_ICON_SLOT = 4
        private const val DETAIL_UPGRADE_SLOT = 20
        private const val DETAIL_SUMMON_SLOT = 22
        private const val DETAIL_GLOW_SLOT = 24
        private const val DETAIL_SKINS_SLOT = 31
        private val DETAIL_ABILITY_SLOTS = listOf(29, 30, 32, 33)
        private const val DETAIL_BACK_SLOT = 36

        private const val TUNING_SIZE = 54
        private const val TUNING_INFO_SLOT = 4
        private const val TUNING_LEVEL_SLOT = 13
        private val TUNING_SPEED_SLOTS = listOf(20, 21, 22, 23, 24)
        private val TUNING_STEP_SLOTS = listOf(29, 30, 31, 32, 33)
        private const val TUNING_NOT_APPLICABLE_SLOT = 31
        private const val TUNING_BACK_SLOT = 45
        private val SPEED_TUNING_MATERIALS =
            listOf(
                Material.LEATHER_BOOTS,
                Material.CHAINMAIL_BOOTS,
                Material.IRON_BOOTS,
                Material.GOLDEN_BOOTS,
                Material.DIAMOND_BOOTS,
            )
        private val STEP_TUNING_MATERIALS =
            listOf(
                Material.OAK_SLAB,
                Material.OAK_STAIRS,
                Material.GRASS_BLOCK,
                Material.PISTON,
                Material.GOAT_HORN,
            )

        private const val SKINS_SIZE = 54
        private val SKIN_CONTENT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34)
        private const val SKINS_BACK_SLOT = 45

        private const val CONFIRM_SIZE = 27
        private const val CONFIRM_CANCEL_SLOT = 11
        private const val CONFIRM_INFO_SLOT = 13
        private const val CONFIRM_ACCEPT_SLOT = 15
    }
}

internal fun prioritizeUnlockedMounts(
    mounts: List<MountDefinition>,
    profile: (MountDefinition) -> MountProfile,
): List<MountDefinition> {
    val (unlocked, locked) = mounts.partition { mount -> profile(mount).unlocked }
    return unlocked + locked
}
