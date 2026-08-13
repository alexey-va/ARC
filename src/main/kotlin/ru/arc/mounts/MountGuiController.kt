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

private enum class MountScreen { LIST, DETAIL, SKINS, CONFIRM }

private enum class MountFilter(val title: String, val icon: Material) {
    ALL("Все", Material.COMPASS),
    FLYING("Воздушные", Material.FEATHER),
    WALKING("Наземные", Material.SADDLE),
    SWIMMING("Водные", Material.HEART_OF_THE_SEA),
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
}

private class MountMenuHolder(
    val screen: MountScreen,
    val mountId: String? = null,
    val page: Int = 0,
    val filter: MountFilter = MountFilter.ALL,
    val ownedOnly: Boolean = false,
    val mountsBySlot: Map<Int, String> = emptyMap(),
    val skinsBySlot: Map<Int, String> = emptyMap(),
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
        val visible =
            catalog.all.filter { mount ->
                filter.matches(mount) && (!ownedOnly || ownership.profile(subject(player), mount).unlocked)
            }
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
            inventory.setItem(slot, mountIcon(mount, ownership.profile(subject(player), mount)))
        }
        if (page > 0) inventory.setItem(LIST_PREVIOUS_SLOT, item(Material.ARROW, "<aqua>Предыдущая страница", listOf("<gray>${page}/${pageCount}")))
        if (page + 1 < pageCount) inventory.setItem(LIST_NEXT_SLOT, item(Material.ARROW, "<aqua>Следующая страница", listOf("<gray>${page + 2}/${pageCount}")))
        inventory.setItem(
            LIST_FILTER_SLOT,
            item(
                filter.icon,
                "<gold>Категория: <white>${filter.title}",
                listOf(
                    "<gray>ЛКМ — сменить категорию",
                    "<gray>ПКМ — ${if (ownedOnly) "показать всю коллекцию" else "показать только полученных"}",
                    "",
                    "<dark_gray>Показано: ${visible.size}/${catalog.all.size}",
                ),
                glint = ownedOnly,
            ),
        )
        inventory.setItem(
            LIST_INFO_SLOT,
            item(
                Material.BOOK,
                "<gold>Коллекция маунтов",
                listOf(
                    "<gray>ЛКМ — призвать полученного",
                    "<gray>ПКМ — развитие и облики",
                    "",
                    "<gray>Полёт: Space вверх, Shift вниз",
                    "<gray>Двойной Shift — спешиться",
                ),
            ),
        )
        inventory.setItem(LIST_BALANCE_SLOT, balanceItem(player))
        inventory.setItem(LIST_BACK_SLOT, item(Material.BLUE_STAINED_GLASS_PANE, "<aqua>Назад", listOf("<gray>Вернуться в главное меню")))
        player.openInventory(inventory)
        click(player)
    }

    fun openDetail(player: Player, mountId: String) {
        val config = configProvider()
        val mount = catalogProvider()[mountId] ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        val holder = MountMenuHolder(MountScreen.DETAIL, mount.id)
        val inventory = Bukkit.createInventory(holder, DETAIL_SIZE, component(config.detailTitle.replace("<mount>", escape(mount.displayName))))
        holder.backingInventory = inventory
        fill(inventory)
        inventory.setItem(DETAIL_ICON_SLOT, mountIcon(mount, profile, detailed = true))
        inventory.setItem(DETAIL_UPGRADE_SLOT, upgradeItem(mount, profile))
        inventory.setItem(DETAIL_SUMMON_SLOT, summonItem(profile, config.sessionDuration))
        inventory.setItem(DETAIL_GLOW_SLOT, glowItem(mount, profile))
        inventory.setItem(DETAIL_SKINS_SLOT, skinsItem(mount, profile))
        inventory.setItem(DETAIL_BACK_SLOT, item(Material.BLUE_STAINED_GLASS_PANE, "<aqua>Назад", listOf("<gray>К списку маунтов")))
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
        inventory.setItem(SKINS_BACK_SLOT, item(Material.BLUE_STAINED_GLASS_PANE, "<aqua>Назад", listOf("<gray>К развитию маунта")))
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
        inventory.setItem(CONFIRM_CANCEL_SLOT, item(Material.RED_CONCRETE, "<red>Отмена", listOf("<gray>Вернуться без покупки")))
        inventory.setItem(CONFIRM_ACCEPT_SLOT, item(Material.LIME_CONCRETE, "<green>Подтвердить", listOf("<gray>С баланса будет списано", "<yellow>${TextUtil.formatAmount(price)}<white>💰"), glint = true))
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
        when (slot) {
            DETAIL_BACK_SLOT -> openList(player)
            DETAIL_SUMMON_SLOT -> if (profile.unlocked) summon(player, mount, profile) else bass(player)
            DETAIL_SKINS_SLOT -> if (profile.unlocked) openSkins(player, mount) else bass(player)
            DETAIL_UPGRADE_SLOT -> {
                val target = profile.level + 1
                if (target > mount.maxLevel || mount.price(target) == null) bass(player)
                else if (!configProvider().purchasesEnabled) purchasesDisabled(player)
                else openConfirm(player, mount, ConfirmAction.Level(target))
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

    private fun handleSkinClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val skinId = holder.skinsBySlot[slot] ?: if (slot == SKINS_BACK_SLOT) return openDetail(player, mount.id) else return
        val profile = ownership.profile(subject(player), mount)
        if (profile.ownsSkin(skinId)) {
            purchases.setActiveSkin(subject(player), mount, skinId) { handlePurchaseResult(player, mount, it, purchase = false, reopenSkins = true) }
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
                else -> openDetail(player, mount.id)
            }
            CONFIRM_ACCEPT_SLOT -> {
                val callback: (MountPurchaseResult) -> Unit = { result ->
                    handlePurchaseResult(player, mount, result, reopenSkins = action is ConfirmAction.Skin)
                }
                when (action) {
                    is ConfirmAction.Level -> purchases.purchaseLevel(subject(player), mount, action.level, callback)
                    ConfirmAction.Glow -> purchases.purchaseGlow(subject(player), mount, callback)
                    is ConfirmAction.Skin -> mount.skin(action.skinId)?.let { purchases.purchaseSkin(subject(player), mount, it, callback) }
                }
            }
        }
    }

    private fun summon(player: Player, mount: MountDefinition, profile: MountProfile) {
        val level = mount.level(profile.level)
        val skin = mount.skin(profile.activeSkinId)
        val result =
            sessions.spawn(
                player = player,
                definition = mount,
                speed = level.speed,
                handlingMultiplier = level.handlingMultiplier,
                sprintMultiplier = level.sprintMultiplier,
                durationMillis = configProvider().sessionDuration.toMillis(),
                glow = profile.glowEnabled,
                skin = skin,
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
        reopenSkins: Boolean = false,
    ) {
        if (!active || !player.isOnline) return
        when (result) {
            MountPurchaseResult.Success -> {
                send(player, if (purchase) "purchase-success" else "setting-saved", if (purchase) "<green>Покупка сохранена!" else "<green>Настройка сохранена.")
                click(player)
                if (reopenSkins) openSkins(player, mount) else openDetail(player, mount.id)
            }
            MountPurchaseResult.Busy -> send(player, "purchase-busy", "<yellow>Предыдущая операция ещё выполняется.")
            MountPurchaseResult.AlreadyOwned -> {
                send(player, "already-owned", "<yellow>Это уже выбрано или куплено.")
                if (reopenSkins) openSkins(player, mount)
                else openDetail(player, mount.id)
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

    private fun mountIcon(mount: MountDefinition, profile: MountProfile, detailed: Boolean = false): ItemStack {
        val lore = buildList {
            add("${mount.rarity.color}${mount.rarity.displayName}")
            add("<dark_gray>● <gray>Тип: ${movementColor(mount.movement)}${mount.movement.displayName}")
            add("<dark_gray>● <gray>Получение: <white>${escape(mount.acquisition)}")
            add(if (profile.unlocked) "<dark_gray>● <gray>Уровень: <yellow>${profile.level}<gray>/${mount.maxLevel}" else "<dark_gray>● <gray>Статус: <red>не получен")
            if (profile.unlocked) {
                add("<dark_gray>● <gray>Скорость: <white>${formatSpeed(mount.speed(profile.level))}")
                add("<dark_gray>● <gray>Облик: <white>${escape(skinName(mount, profile.activeSkinId))}")
            }
            if (detailed && mount.description.isNotEmpty()) {
                add("")
                mount.description.forEach { add("<gray>${escape(it)}") }
            }
            if (!detailed) {
                add("")
                if (profile.unlocked) {
                    add("<green>ЛКМ <gray>— призвать")
                    add("<aqua>ПКМ <gray>— развитие и облики")
                } else add("<green>Нажмите, чтобы узнать способ получения")
            }
        }
        return item(
            Material.matchMaterial(mount.iconMaterial) ?: Material.PAPER,
            if (profile.unlocked) "<gold>${escape(mount.displayName)}" else "<gray>${escape(mount.displayName)}",
            lore,
            glint = profile.unlocked,
        )
    }

    private fun upgradeItem(mount: MountDefinition, profile: MountProfile): ItemStack {
        val next = profile.level + 1
        return when {
            profile.level >= mount.maxLevel -> item(Material.NETHER_STAR, "<gold><bold>Финальный уровень достигнут", listOf("<gray>Маунт раскрыт на полную скорость.", "<white>${formatSpeed(mount.speed(mount.maxLevel))} <gray>ед. скорости"), glint = true)
            mount.price(next) == null -> item(Material.BARRIER, if (profile.unlocked) "<red>Особое улучшение" else "<red>Особый маунт", listOf("<gray>Этот уровень получается другим способом.", "<white>${escape(mount.acquisition)}"))
            else -> {
                val level = mount.level(next)
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
                Triple(if (action.level == mount.maxLevel) "<gold><bold>ФИНАЛЬНЫЙ РЫВОК" else "<green>Уровень ${action.level}", checkNotNull(level.price), listOf("<gray>${escape(mount.displayName)}", "<gray>Новая скорость: <white>${formatSpeed(level.speed)}"))
            }
            ConfirmAction.Glow -> Triple("<green>Свечение", checkNotNull(mount.glowPrice), listOf("<gray>${escape(mount.displayName)}", "<gray>Косметика покупается навсегда"))
            is ConfirmAction.Skin -> {
                val skin = checkNotNull(mount.skin(action.skinId))
                Triple("<light_purple>${escape(skin.displayName)}", checkNotNull(skin.price), listOf("<gray>${escape(mount.displayName)}", "<gray>Облик покупается навсегда"))
            }
        }

    private fun balanceItem(player: Player): ItemStack {
        val balance = wallet.balanceMinor(player.uniqueId)
        return item(Material.SUNFLOWER, "<yellow>Баланс", listOf(if (balance != null) "<green>${TextUtil.formatAmount(balance.minorToDouble())}<white>💰" else "<red>Экономика недоступна"))
    }

    private fun purchasesDisabled(player: Player) {
        send(player, "purchases-disabled", "<yellow>Покупки маунтов доступны на спавне.")
        bass(player)
    }

    private fun fill(inventory: Inventory, material: Material = Material.GRAY_STAINED_GLASS_PANE) {
        val background = item(material, " ", emptyList())
        for (slot in 0 until inventory.size) inventory.setItem(slot, background)
    }

    private fun item(material: Material, display: String, lore: List<String>, glint: Boolean = false): ItemStack =
        ItemStack(material).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(component(display))
                meta.lore(lore.map(::component))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
                meta.setEnchantmentGlintOverride(glint)
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
        private const val LIST_PREVIOUS_SLOT = 45
        private const val LIST_BACK_SLOT = 48
        private const val LIST_FILTER_SLOT = 49
        private const val LIST_INFO_SLOT = 50
        private const val LIST_NEXT_SLOT = 52
        private const val LIST_BALANCE_SLOT = 53

        private const val DETAIL_SIZE = 45
        private const val DETAIL_ICON_SLOT = 4
        private const val DETAIL_UPGRADE_SLOT = 20
        private const val DETAIL_SUMMON_SLOT = 22
        private const val DETAIL_GLOW_SLOT = 24
        private const val DETAIL_SKINS_SLOT = 31
        private const val DETAIL_BACK_SLOT = 40

        private const val SKINS_SIZE = 54
        private val SKIN_CONTENT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34)
        private const val SKINS_BACK_SLOT = 49

        private const val CONFIRM_SIZE = 27
        private const val CONFIRM_CANCEL_SLOT = 11
        private const val CONFIRM_INFO_SLOT = 13
        private const val CONFIRM_ACCEPT_SLOT = 15
    }
}
