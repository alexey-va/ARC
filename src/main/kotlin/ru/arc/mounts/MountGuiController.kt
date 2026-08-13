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

private enum class MountScreen {
    LIST,
    DETAIL,
}

private class MountMenuHolder(
    val screen: MountScreen,
    val mountId: String? = null,
    val mountsBySlot: Map<Int, String> = emptyMap(),
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

    fun openList(player: Player) {
        val config = configProvider()
        val catalog = catalogProvider()
        val slots = CONTENT_SLOTS.zip(catalog.all.map(MountDefinition::id)).toMap()
        val holder = MountMenuHolder(MountScreen.LIST, mountsBySlot = slots)
        val inventory = Bukkit.createInventory(holder, LIST_SIZE, component(config.listTitle))
        holder.backingInventory = inventory
        fill(inventory)

        for ((slot, mountId) in slots) {
            val mount = catalog[mountId] ?: continue
            val profile = ownership.profile(subject(player), mount)
            inventory.setItem(slot, mountIcon(mount, profile))
        }
        inventory.setItem(
            LIST_BACK_SLOT,
            item(
                Material.BLUE_STAINED_GLASS_PANE,
                "<aqua>Назад",
                listOf("<gray>Вернуться в главное меню"),
            ),
        )
        inventory.setItem(
            LIST_INFO_SLOT,
            item(
                Material.BOOK,
                "<gold>Управление",
                listOf(
                    "<gray>ЛКМ — призвать доступного маунта",
                    "<gray>ПКМ — открыть улучшения",
                    "",
                    "<gray>Полёт: Space вверх, Shift вниз",
                    "<gray>Двойной Shift — спешиться",
                ),
            ),
        )
        inventory.setItem(
            LIST_BALANCE_SLOT,
            item(
                Material.SUNFLOWER,
                "<yellow>Баланс",
                listOf(
                    if (wallet.available) "<green>${TextUtil.formatAmount(wallet.balance(player.uniqueId))}<white>💰"
                    else "<red>Экономика недоступна",
                ),
            ),
        )
        player.openInventory(inventory)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)
    }

    fun openDetail(player: Player, mountId: String) {
        val config = configProvider()
        val mount = catalogProvider()[mountId] ?: return openList(player)
        val profile = ownership.profile(subject(player), mount)
        val holder = MountMenuHolder(MountScreen.DETAIL, mount.id)
        val title = config.detailTitle.replace("<mount>", escapeMiniMessage(mount.displayName))
        val inventory = Bukkit.createInventory(holder, DETAIL_SIZE, component(title))
        holder.backingInventory = inventory
        fill(inventory)

        inventory.setItem(DETAIL_ICON_SLOT, mountIcon(mount, profile, detailed = true))
        inventory.setItem(DETAIL_UPGRADE_SLOT, upgradeItem(mount, profile))
        inventory.setItem(DETAIL_SUMMON_SLOT, summonItem(profile, config.sessionDuration))
        inventory.setItem(DETAIL_GLOW_SLOT, glowItem(mount, profile))
        inventory.setItem(
            DETAIL_BACK_SLOT,
            item(Material.BLUE_STAINED_GLASS_PANE, "<aqua>Назад", listOf("<gray>К списку маунтов")),
        )
        player.openInventory(inventory)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)
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
                val command = configProvider().backCommand
                if (command.isNotBlank()) player.performCommand(command)
                click(player)
            }
            else -> {
                val mount = holder.mountsBySlot[event.rawSlot]?.let(catalogProvider()::get) ?: return
                val profile = ownership.profile(subject(player), mount)
                if (profile.unlocked && event.isLeftClick) {
                    summon(player, mount, profile)
                } else {
                    openDetail(player, mount.id)
                }
            }
        }
    }

    private fun handleDetailClick(player: Player, holder: MountMenuHolder, slot: Int) {
        val mount = holder.mountId?.let(catalogProvider()::get) ?: return openList(player)
        val subject = subject(player)
        val profile = ownership.profile(subject, mount)
        when (slot) {
            DETAIL_BACK_SLOT -> openList(player)
            DETAIL_SUMMON_SLOT -> if (profile.unlocked) summon(player, mount, profile) else bass(player)
            DETAIL_UPGRADE_SLOT -> {
                val targetLevel = profile.level + 1
                if (targetLevel > mount.maxLevel || mount.price(targetLevel) == null) {
                    bass(player)
                    return
                }
                purchases.purchaseLevel(subject, mount, targetLevel) { result ->
                    handlePurchaseResult(player, mount, result)
                }
            }
            DETAIL_GLOW_SLOT -> {
                when {
                    !profile.unlocked -> bass(player)
                    !profile.glowOwned && mount.glowPrice != null -> {
                        purchases.purchaseGlow(subject, mount) { result ->
                            handlePurchaseResult(player, mount, result)
                        }
                    }
                    profile.glowOwned -> {
                        purchases.setGlowEnabled(subject, mount, !profile.glowEnabled) { result ->
                            handlePurchaseResult(player, mount, result, purchase = false)
                        }
                    }
                    else -> bass(player)
                }
            }
        }
    }

    private fun summon(player: Player, mount: MountDefinition, profile: MountProfile) {
        val result =
            sessions.spawn(
                player = player,
                definition = mount,
                speed = mount.speed(profile.level),
                durationMillis = configProvider().sessionDuration.toMillis(),
                glow = profile.glowEnabled,
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
    ) {
        if (!active || !player.isOnline) return
        when (result) {
            MountPurchaseResult.Success -> {
                send(
                    player,
                    if (purchase) "purchase-success" else "setting-saved",
                    if (purchase) "<green>Покупка успешна!" else "<green>Настройка сохранена.",
                )
                click(player)
                openDetail(player, mount.id)
            }
            MountPurchaseResult.Busy -> send(player, "purchase-busy", "<yellow>Предыдущая операция ещё выполняется.")
            MountPurchaseResult.AlreadyOwned -> send(player, "already-owned", "<yellow>У вас уже есть это улучшение.")
            MountPurchaseResult.InvalidLevel -> send(player, "invalid-level", "<red>Сначала купите предыдущий уровень.")
            MountPurchaseResult.NotUnlocked -> send(player, "not-unlocked", "<red>Сначала разблокируйте маунта.")
            MountPurchaseResult.NotForSale -> send(player, "not-for-sale", "<red>Это улучшение нельзя купить.")
            MountPurchaseResult.EconomyUnavailable -> send(player, "economy-unavailable", "<red>Экономика временно недоступна.")
            MountPurchaseResult.InsufficientFunds -> send(player, "not-enough-money", "<red>Недостаточно денег.")
            MountPurchaseResult.PaymentFailed -> send(player, "payment-failed", "<red>Платёж не прошёл. Деньги не списаны.")
            MountPurchaseResult.PersistenceFailed -> send(player, "setting-failed", "<red>Не удалось сохранить настройку.")
            MountPurchaseResult.PersistenceFailedRefunded -> {
                send(player, "save-failed-refunded", "<red>Не удалось сохранить покупку. Деньги возвращены.")
            }
            MountPurchaseResult.PersistenceFailedRefundFailed -> {
                error("Mount purchase persistence and refund failed for {} / {}", player.name, mount.id)
                send(player, "save-failed-refund-failed", "<dark_red>Ошибка покупки и возврата. Обратитесь к администрации.")
            }
        }
        if (result != MountPurchaseResult.Success) bass(player)
    }

    private fun mountIcon(mount: MountDefinition, profile: MountProfile, detailed: Boolean = false): ItemStack {
        val status = if (profile.unlocked) "<green>Доступен" else "<red>Не куплен"
        val lore =
            buildList {
                add("<dark_gray>● <gray>Тип: ${movementColor(mount.movement)}${mount.movement.displayName}")
                add("<dark_gray>● <gray>Статус: $status")
                add(
                    if (profile.unlocked) "<dark_gray>● <gray>Уровень: <yellow>${profile.level}<gray>/${mount.maxLevel}"
                    else "<dark_gray>● <gray>Уровень: <red>—",
                )
                if (profile.glowOwned) {
                    add("<dark_gray>● <gray>Свечение: ${if (profile.glowEnabled) "<green>включено" else "<red>выключено"}")
                }
                if (!detailed) {
                    add("")
                    if (profile.unlocked) {
                        add("<green>ЛКМ <gray>— призвать")
                        add("<aqua>ПКМ <gray>— улучшения")
                    } else {
                        add("<green>Нажмите, чтобы открыть магазин")
                    }
                }
            }
        return item(
            Material.matchMaterial(mount.iconMaterial) ?: Material.PAPER,
            if (profile.unlocked) "<gold>${escapeMiniMessage(mount.displayName)}" else "<gray>${escapeMiniMessage(mount.displayName)}",
            lore,
            glint = profile.unlocked,
        )
    }

    private fun upgradeItem(mount: MountDefinition, profile: MountProfile): ItemStack {
        val nextLevel = profile.level + 1
        return when {
            profile.level >= mount.maxLevel -> item(
                Material.NETHER_STAR,
                "<green>Максимальный уровень",
                listOf("<gray>Маунт полностью улучшен."),
                glint = true,
            )
            mount.price(nextLevel) == null -> item(
                Material.BARRIER,
                if (profile.unlocked) "<red>Нельзя улучшить за деньги" else "<red>Нельзя купить",
                listOf("<gray>Этот уровень получается другим способом."),
            )
            else -> {
                val price = checkNotNull(mount.price(nextLevel))
                item(
                    Material.EMERALD,
                    if (profile.unlocked) "<green>Улучшить до уровня $nextLevel" else "<green>Купить маунта",
                    listOf(
                        "<gray>Скорость: <white>${mount.speed(nextLevel)}",
                        "<gray>Цена: <yellow>${TextUtil.formatAmount(price)}<white>💰",
                        "",
                        "<green>Нажмите для покупки",
                    ),
                )
            }
        }
    }

    private fun summonItem(profile: MountProfile, duration: Duration): ItemStack =
        if (profile.unlocked) {
            item(
                Material.SADDLE,
                "<gold>Призвать маунта",
                listOf(
                    "<gray>Время поездки: <white>${formatDuration(duration)}",
                    "",
                    "<green>Нажмите, чтобы призвать",
                ),
            )
        } else {
            item(Material.BARRIER, "<red>Маунт недоступен", listOf("<gray>Сначала разблокируйте первый уровень."))
        }

    private fun glowItem(mount: MountDefinition, profile: MountProfile): ItemStack =
        when {
            !profile.unlocked -> item(Material.GRAY_DYE, "<gray>Свечение недоступно", listOf("<gray>Сначала разблокируйте маунта."))
            profile.glowOwned -> item(
                if (profile.glowEnabled) Material.GLOW_INK_SAC else Material.INK_SAC,
                if (profile.glowEnabled) "<red>Выключить свечение" else "<green>Включить свечение",
                listOf("<gray>Свечение уже куплено.", "", "<green>Нажмите для переключения"),
                glint = profile.glowEnabled,
            )
            mount.glowPrice != null -> item(
                Material.GLOW_INK_SAC,
                "<green>Купить свечение",
                listOf(
                    "<gray>Цена: <yellow>${TextUtil.formatAmount(mount.glowPrice)}<white>💰",
                    "",
                    "<green>Нажмите для покупки",
                ),
            )
            else -> item(Material.BARRIER, "<red>Свечение недоступно", listOf("<gray>Это улучшение нельзя купить."))
        }

    private fun fill(inventory: Inventory) {
        val background = item(Material.GRAY_STAINED_GLASS_PANE, " ", emptyList())
        for (slot in 0 until inventory.size) inventory.setItem(slot, background)
    }

    private fun item(
        material: Material,
        display: String,
        lore: List<String>,
        glint: Boolean = false,
    ): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(component(display))
            meta.lore(lore.map(::component))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
            meta.setEnchantmentGlintOverride(glint)
        }
        return stack
    }

    private fun subject(player: Player): MountPermissionSubject =
        MountPermissionSubject(player.uniqueId, player.name, player::hasPermission)

    private fun send(player: Player, path: String, fallback: String) {
        player.sendMessage(component(configProvider().message(path, fallback)))
    }

    private fun click(player: Player) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)
    private fun bass(player: Player) = player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f)
    private fun component(text: String): Component = TextUtil.mm(text, true)

    private fun movementColor(movement: MountMovement): String =
        when (movement) {
            MountMovement.WALKING -> "<gray>"
            MountMovement.FLYING -> "<green>"
            MountMovement.SWIMMING -> "<aqua>"
        }

    private fun escapeMiniMessage(value: String): String = value.replace("<", "\\<").replace(">", "\\>")

    private fun formatDuration(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(1L)
        return when {
            seconds % 3_600L == 0L -> "${seconds / 3_600L} ч"
            seconds % 60L == 0L -> "${seconds / 60L} мин"
            else -> "$seconds сек"
        }
    }

    companion object {
        private const val LIST_SIZE = 36
        private val CONTENT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
        private const val LIST_BALANCE_SLOT = 28
        private const val LIST_BACK_SLOT = 31
        private const val LIST_INFO_SLOT = 34

        private const val DETAIL_SIZE = 27
        private const val DETAIL_ICON_SLOT = 4
        private const val DETAIL_UPGRADE_SLOT = 11
        private const val DETAIL_SUMMON_SLOT = 13
        private const val DETAIL_GLOW_SLOT = 15
        private const val DETAIL_BACK_SLOT = 22
    }
}
