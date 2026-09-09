package ru.arc.landsui

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
import org.bukkit.inventory.EquipmentSlot
import ru.arc.onboarding.ClaimBlockIdentity
import ru.arc.onboarding.OnboardingModule
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Owns the reusable claim-block request without touching the held item. */
internal class ClaimBlockTool(
    private val settings: LandsUiSettings,
    private val lands: LandsIntegration = LandsIntegration.of(ARC.instance),
) : AutoCloseable, Listener {
    private val tasks = LifecycleTaskScope()
    private val pending = mutableMapOf<UUID, Pending>()

    private data class Pending(
        val player: UUID,
        val target: Location,
        val radius: Int,
        var selection: Selection? = null,
    )

    fun start() { Bukkit.getPluginManager().registerEvents(this, ARC.instance) }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun interact(event: PlayerInteractEvent) {
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
        pending.values.toList().forEach { it.selection?.disable() }
        pending.clear()
        tasks.close()
    }

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
