package ru.arc.landsui

import io.papermc.paper.event.player.PlayerArmSwingEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.land.enums.LandType
import me.angeschossen.lands.api.player.Selection
import me.angeschossen.lands.api.player.claiming.ClaimResult
import org.bukkit.Location
import org.bukkit.GameMode
import org.bukkit.Material
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.onboarding.ClaimBlockIdentity
import ru.arc.onboarding.OnboardingModule
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Owns the reusable claim-block request without touching the held item. */
internal class ClaimBlockTool(
    private val settings: LandsUiSettings,
    private val lands: LandsIntegration = LandsIntegration.of(ARC.instance),
) : AutoCloseable, Listener {
    private val tasks = LifecycleTaskScope()
    private val pending = mutableMapOf<UUID, Pending>()
    private var menu: ClaimLandMenu? = null
    private val menuClickAfter = mutableMapOf<UUID, Long>()
    private val menuRetries = mutableMapOf<UUID, Long>()
    private var tick = 0L

    private data class Pending(
        val player: UUID,
        val target: Location,
        val radius: Int,
        var selection: Selection? = null,
    )

    fun start() {
        menu = ClaimLandMenu(
            ARC.instance,
            title = { name -> MiniMessage.miniMessage().deserialize(
                settings.text("panel-title"), Placeholder.component("land", Component.text(name.take(24))),
            ) },
            labels = LandsUiPanelAction.entries.associateWith {
                TextUtil.mm(settings.text("panel-${it.name.lowercase(java.util.Locale.ROOT).replace('_', '-')}"))
            },
        )
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        tasks.runTimer(1L, 1L) {
            tick++
            Bukkit.getOnlinePlayers().forEach { player ->
                if (tick < (menuRetries[player.uniqueId] ?: 0L)) return@forEach
                try {
                    val land = menuLand(player)
                    if (land == null) clearMenu(player)
                    else menu?.show(player, land.ulid.toString(), land.name)
                    menuRetries.remove(player.uniqueId)
                } catch (failure: Exception) {
                    clearMenu(player)
                    // Blocked placement is a normal result; only unexpected failures back off.
                    menuRetries[player.uniqueId] = tick + 100L
                    error("Claim land menu failed for {}; retrying in 5 seconds", player.name, failure)
                }
            }
        }
    }

    private fun menuLand(player: Player): Land? {
        if (!player.isOnline || player.isDead || player.gameMode == GameMode.SPECTATOR ||
            player.gameMode == GameMode.ADVENTURE || RegionToolItem.matches(player.inventory.itemInMainHand) ||
            ClaimBlockIdentity.heldRadius(player) == null || lands.getWorld(player.world) == null
        ) return null
        val at = player.location
        return lands.getLandByUnloadedChunk(player.world, at.blockX shr 4, at.blockZ shr 4)
            ?.takeIf { it.exists() && it.isTrusted(player.uniqueId) }
    }

    fun hasMenu(player: Player): Boolean = menu?.contains(player.uniqueId) == true

    fun isLookingAtMenu(player: Player): Boolean = menu?.isLookingAt(player) == true

    private fun menuTarget(player: Player): Pair<String, LandsUiPanelAction>? {
        val active = menu ?: return null
        val action = active.target(player) ?: return null
        val id = active.landId(player.uniqueId) ?: return null
        if (menuLand(player)?.ulid?.toString() != id) return null
        return id to action
    }

    fun isMenuTarget(player: Player): Boolean = menuTarget(player) != null

    private fun clickMenu(player: Player): Boolean {
        val (id, action) = menuTarget(player) ?: return false
        // Consume duplicate swing/block events too; a menu click must never place or break blocks.
        if (tick >= (menuClickAfter[player.uniqueId] ?: 0L)) {
            menuClickAfter[player.uniqueId] = tick + 5
            LandsUiModule.openPanelAction(player, id, action)
        }
        return true
    }

    private fun interceptMenu(player: Player, hand: EquipmentSlot?): Boolean = when (hand) {
        EquipmentSlot.HAND -> clickMenu(player)
        EquipmentSlot.OFF_HAND -> isMenuTarget(player)
        else -> false
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun interact(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL && interceptMenu(event.player, event.hand)) {
            event.isCancelled = true
            return
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        if (RegionToolItem.matches(player.inventory.itemInMainHand)) return
        if (event.hand == EquipmentSlot.OFF_HAND && ClaimBlockIdentity.matches(player.inventory.itemInMainHand)) {
            event.isCancelled = true
            return
        }
        val item = event.item ?: return
        val radius = ClaimBlockIdentity.radius(item) ?: return
        if (OnboardingModule.claimGuide?.isMenuTarget(player) == true) {
            event.isCancelled = true
            return
        }
        if (event.useItemInHand() == Event.Result.DENY) return
        // Stop vanilla placement (and Lands' consuming BlockPlace handler) before it occurs.
        event.isCancelled = true
        if (player.gameMode == GameMode.SPECTATOR || player.gameMode == GameMode.ADVENTURE || player.isDead) return
        if (lands.configuration.mainConfig.getBoolean("land.claimblock.only-owner") && !ClaimBlockIdentity.usableBy(item, player)) {
            player.sendMessage(TextUtil.mm(settings.text("claim-place-owner")))
            return
        }
        val block = event.clickedBlock ?: return
        val target = if (block.isReplaceable) block else block.getRelative(event.blockFace)
        if (target.y !in player.world.minHeight until player.world.maxHeight) return
        place(player, target.location, radius)
    }

    /** Starts one native, aggregate Lands claim. The item is deliberately unchanged. */
    fun place(player: Player, target: Location, radius: Int) {
        require(radius in 0..4) { "Claim radius must be in 0..4" }
        val snapshot = target.clone()
        tasks.runSync {
            if (!player.isOnline || player.world != snapshot.world || lands.getWorld(player.world) == null) return@runSync
            if (pending.containsKey(player.uniqueId)) {
                player.sendMessage(TextUtil.mm(settings.text("claim-place-working")))
                return@runSync
            }
            val lp = lands.getLandPlayer(player.uniqueId)
            if (lp == null) {
                player.sendMessage(TextUtil.mm(settings.text("claim-place-failed")))
                return@runSync
            }
            if (lp.selection != null) {
                player.sendMessage(TextUtil.mm(settings.text("claim-place-selection-active")))
                return@runSync
            }

            val request = Pending(player.uniqueId, snapshot, radius)
            pending[player.uniqueId] = request
            try {
                val occupied = lands.getLandByUnloadedChunk(
                    snapshot.world,
                    snapshot.blockX shr 4,
                    snapshot.blockZ shr 4,
                )
                val selected = lp.getEditLand(false)?.takeIf { it.exists() }
                if (occupied != null && (selected == null || occupied.ulid != selected.ulid)) {
                    finish(request, player, "claim-place-occupied")
                    return@runSync
                }

                if (selected != null && !selected.defaultArea.hasRoleFlag(lp, Flags.LAND_CLAIM, Material.GRASS_BLOCK, false)) {
                    finish(request, player, "claim-place-no-permission")
                    return@runSync
                }
                val landFuture = if (selected != null) CompletableFuture.completedFuture<Land?>(selected)
                    else createLand(player, snapshot, lp)
                val token = tasks.token()
                landFuture.whenCompleteSync(tasks, token) { land, failure ->
                    try {
                        if (failure != null || land == null || !land.exists()) {
                            finish(request, player, "claim-place-failed", failure)
                            return@whenCompleteSync
                        }
                        if (!player.isOnline || player.world != snapshot.world || lp.selection != null) {
                            finish(request, player, "claim-place-failed")
                            return@whenCompleteSync
                        }
                        if (selected == null && lp.getEditLand(false) == null) lp.setEditLand(land)
                        val selection = Selection.of(lp, false, false, true)
                        request.selection = selection
                        val edge = selectionEdge(snapshot, radius)
                        selection.setPos1(edge.first)
                        selection.setPos2(edge.second)
                        selection.claim(land, false, true).whenCompleteSync(tasks, token) { result, claimFailure ->
                            if (claimFailure != null || result == null || result != ClaimResult.SUCCESS && result != ClaimResult.IGNOREABLE) {
                                finish(request, player, "claim-place-failed", claimFailure)
                            } else {
                                finish(request, player, "claim-place-done")
                            }
                        }
                    } catch (failure: Exception) {
                        finish(request, player, "claim-place-failed", failure)
                    }
                }
            } catch (failure: Exception) {
                finish(request, player, "claim-place-failed", failure)
            }
        }
    }

    override fun close() {
        HandlerList.unregisterAll(this)
        menu?.close()
        menu = null
        menuClickAfter.clear()
        menuRetries.clear()
        pending.values.toList().forEach { it.selection?.disable() }
        pending.clear()
        tasks.close()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun swingMenu(event: PlayerArmSwingEvent) {
        if (interceptMenu(event.player, event.hand)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun entityMenu(event: PlayerInteractEntityEvent) {
        if (interceptMenu(event.player, event.hand)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun entityAtMenu(event: PlayerInteractAtEntityEvent) = entityMenu(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun attackMenu(event: PrePlayerAttackEntityEvent) {
        if (clickMenu(event.player)) event.isCancelled = true
    }

    private fun clearMenu(player: Player) {
        menu?.hide(player.uniqueId)
        menuClickAfter.remove(player.uniqueId)
    }

    @EventHandler fun quitMenu(event: PlayerQuitEvent) {
        clearMenu(event.player)
        menuRetries.remove(event.player.uniqueId)
    }
    private fun resetMenu(player: Player) {
        clearMenu(player)
        menuRetries.remove(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun teleportMenu(event: PlayerTeleportEvent) = resetMenu(event.player)
    @EventHandler fun worldMenu(event: PlayerChangedWorldEvent) = resetMenu(event.player)
    @EventHandler fun deathMenu(event: PlayerDeathEvent) = resetMenu(event.entity)

    private fun finish(request: Pending, player: Player, key: String?, failure: Throwable? = null) {
        if (pending[request.player] !== request) return
        pending.remove(request.player)
        request.selection?.disable()
        if (failure != null) {
            error("Reusable claim-block request failed for {}", player.name, failure)
        }
        if (key != null && player.isOnline) player.sendMessage(TextUtil.mm(settings.text(key)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun createLand(player: Player, target: Location, lp: me.angeschossen.lands.api.player.LandPlayer): CompletableFuture<Land?> =
        Land.of(player.name, LandType.LAND, target, lp, false, false) as CompletableFuture<Land?>

    private fun selectionEdge(target: Location, radius: Int): Pair<Location, Location> {
        val world = requireNotNull(target.world)
        val centerX = target.blockX shr 4
        val centerZ = target.blockZ shr 4
        val min = Location(world, ((centerX - radius) shl 4).toDouble(), world.minHeight.toDouble(), ((centerZ - radius) shl 4).toDouble())
        val max = Location(world, (((centerX + radius + 1) shl 4) - 1).toDouble(), (world.maxHeight - 1).toDouble(), (((centerZ + radius + 1) shl 4) - 1).toDouble())
        return min to max
    }
}
