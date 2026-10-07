package ru.arc.iteminfo

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.ARC
import ru.arc.chestpreview.ChestPreviewIcons
import ru.arc.chestpreview.ChestPreviewProvider
import ru.arc.chestpreview.ChestPreviewTarget
import ru.arc.core.LifecycleTaskScope
import ru.arc.furniturehitbox.FurnitureHitboxHint
import ru.arc.hooks.luckperms.LuckPermsHook
import ru.arc.hooks.economyshop.FURNITURE_GALLERY_WORLD
import ru.arc.hooks.economyshop.FurnitureGalleryInteractionRuntime
import ru.arc.onboarding.OnboardingModule
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.ArcInspectionProvider
import ru.arc.paper.inspection.PaperArcInspectionService
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.util.Logging.error

internal class ItemInfoRuntime(
    settings: ItemInfoSettings,
    private val inspection: PaperArcInspectionService,
    galleryPurchasePrice: (Player, String) -> String?,
    galleryRuntime: FurnitureGalleryInteractionRuntime?,
    chestPreviewResolver: (Player, Double) -> ChestPreviewTarget?,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val furnitureHitboxHint = FurnitureHitboxHint.create(ARC.instance, galleryRuntime)
    private val failedViewers = mutableSetOf<java.util.UUID>()
    private val preferencesReader = if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) LuckPermsHook() else null
    private val resolver = BukkitItemInfoTargetResolver(settings.targetDistance, galleryRuntime = galleryRuntime)
    private val readPreferences: (Player) -> ItemInfoPreferences = { player ->
        ItemInfoPreferences.fromStored { key -> preferencesReader?.getCachedMeta(player.uniqueId, key) }
    }
    private val suppressedViewer: (Player) -> Boolean = { player ->
        player.isDead || player.gameMode == GameMode.SPECTATOR || ru.arc.landsui.LandsUiModule.hasClaimMenu(player) ||
            OnboardingModule.claimGuide?.hasHologram(player) == true
    }
    private val providerRegistration = inspection.register(
        ARC.instance,
        "item-info",
        0,
        ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = resolver::resolve,
            preferences = readPreferences,
            galleryPurchasePrice = galleryPurchasePrice,
        ),
    )
    private val chestPreview = ChestPreviewProvider(settings.chestPreview, chestPreviewResolver)
    private val chestIcons = ChestPreviewIcons(PaperPacketDisplays(ARC.instance, "chest-preview"), settings.chestPreview)
    private val chestPreviewRegistration = inspection.register(
        ARC.instance,
        "chest-preview",
        50,
        chestPreview,
    )
    private val controller = ItemInfoController(
        preferences = readPreferences,
        inspection = inspection,
        suppressed = suppressedViewer,
        chestPreview = chestPreview,
        chestIcons = chestIcons,
    )
    private var tick = 0L

    fun start() {
        tasks.runTimer(1L, 1L) {
            tick++
            if (tick == 1L || tick % 20L == 0L) furnitureHitboxHint?.refreshAvailability()
            Bukkit.getOnlinePlayers().forEach { player ->
                furnitureHitboxHint?.update(player, refreshTarget = tick == 1L || tick % 2L == 0L)
                if (player.uniqueId in failedViewers) return@forEach
                try {
                    if (tick == 1L || tick % 5L == 0L) controller.update(player)
                    controller.follow(player)
                } catch (failure: Exception) {
                    controller.reset(player)
                    failedViewers += player.uniqueId
                    error("Item info failed for {} in {}; disabled until reconnect", player.name, player.world.name, failure)
                }
            }
        }
    }

    @EventHandler fun joined(event: PlayerJoinEvent) {
        furnitureHitboxHint?.reset(event.player)
        failedViewers.remove(event.player.uniqueId)
        refreshSoon(event.player)
    }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        furnitureHitboxHint?.reset(event.player)
        controller.reset(event.player)
        failedViewers.remove(event.player.uniqueId)
    }

    @EventHandler fun changedWorld(event: PlayerChangedWorldEvent) = resetAndRefresh(event.player)

    @EventHandler fun teleported(event: PlayerTeleportEvent) = resetAndRefresh(event.player)

    @EventHandler fun died(event: PlayerDeathEvent) {
        furnitureHitboxHint?.reset(event.entity)
        controller.reset(event.entity)
    }

    private fun resetAndRefresh(player: Player) {
        furnitureHitboxHint?.reset(player)
        controller.reset(player)
        refreshSoon(player)
    }

    private fun refreshSoon(player: Player) {
        tasks.runLater(1L) {
            if (player.isOnline && player.uniqueId !in failedViewers) controller.update(player)
        }
    }

    override fun close() {
        tasks.close()
        furnitureHitboxHint?.close()
        chestPreviewRegistration.close()
        chestIcons.close()
        providerRegistration.close()
        failedViewers.clear()
    }
}

internal class ItemInfoInspectionProvider(
    private val settings: ItemInfoSettings,
    private val resolveTarget: (Player) -> ItemInfoTarget?,
    private val preferences: (Player) -> ItemInfoPreferences,
    private val galleryPurchasePrice: (Player, String) -> String? = { _, _ -> null },
) : ArcInspectionProvider {
    override fun resolve(player: Player): ArcInspectionFrame? {
        if (!settings.enabled) return null
        val target = resolveTarget(player) ?: return null
        val isGallery = player.world.name == FURNITURE_GALLERY_WORLD
        val price = if (isGallery) {
            runCatching { galleryPurchasePrice(player, target.namespacedId) }
                .getOrNull()
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(256)
        } else {
            null
        }
        // Gallery commerce may surface a current native offer for IDs suppressed
        // from ordinary game item-info, but the generic exclusion remains in force
        // anywhere else and whenever no current quote is available.
        if (target.namespacedId in settings.excludedItemIds && price == null) return null
        val showNamespacedId = preferences(player).showNamespacedId
        val displayTarget = target.copy(purchasePrice = price)
        return ArcInspectionFrame(
            settings.hologramText(displayTarget, showNamespacedId),
            settings.bossbarText(displayTarget, showNamespacedId),
        )
    }
}
