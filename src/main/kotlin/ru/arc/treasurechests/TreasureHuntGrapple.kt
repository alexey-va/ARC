package ru.arc.treasurechests

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.util.Logging.error
import ru.arc.util.ItemStackFactory
import java.util.UUID
import kotlin.Function1
import kotlin.math.ceil

/**
 * Owns the temporary spawn-hunt hook, its inventory guards, and each short-lived
 * client-only shot. Only the exact spawn world receives the item.
 */
internal class TreasureHuntGrapple(
    private val plugin: JavaPlugin,
    private val scheduler: TaskScheduler,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin),
) : Listener, AutoCloseable {
    private companion object {
        const val SPAWN_WORLD = "rc_origin_spawn"
        const val MAX_SHOT_LIFETIME_TICKS = 120
        const val ACTIVE_REDIRECT_COOLDOWN_TICKS = 4L
        const val BODY_CENTER_Y = 0.9
        const val CABLE_WIDTH = 0.075f
        val CABLE_GLOW = Color.fromRGB(94, 224, 255)
    }

    private data class Shot(
        val playerId: UUID,
        val sessionId: String,
        val worldId: UUID,
        val target: Vector,
        val targetBlock: Block?,
        val travelTicks: Int,
        val head: PacketItemDisplay,
        val cable: List<PacketBlockDisplay>,
        var ageTicks: Int = 0,
        var pulling: Boolean = false,
    )

    private val hookKey = NamespacedKey(plugin, "treasure_hunt_grapple")
    private val sessionKey = NamespacedKey(plugin, "treasure_hunt_grapple_session")
    private val shots = mutableMapOf<UUID, Shot>()
    private val nextAllowedTick = mutableMapOf<UUID, Long>()
    private val fullHotbarPlayers = linkedSetOf<UUID>()
    private val pendingHuskSyncPlayers = mutableSetOf<UUID>()
    private var fullHotbarRetry: ScheduledTask? = null
    private val respawnRetries = mutableMapOf<UUID, ScheduledTask>()
    private var settings: TreasureHuntGrappleSettings? = null
    private var activeHunts: List<ActiveHunt> = emptyList()
    private var activeSessionId: String? = null
    private var shotTask: ScheduledTask? = null
    private var closed = false
    private var huskSyncAvailable = false
    private var huskSyncListener: Listener? = null

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        huskSyncAvailable = plugin.server.pluginManager.getPlugin("HuskSync")?.isEnabled == true
        if (huskSyncAvailable) {
            val listener = createHuskSyncListener()
            huskSyncListener = listener
            if (listener == null) {
                error("HuskSync is enabled, but the treasure-hunt post-sync listener could not be registered")
            } else {
                plugin.server.pluginManager.registerEvents(listener, plugin)
            }
        }
        plugin.server.onlinePlayers.toList().forEach(::removeTaggedItems)
    }

    private fun createHuskSyncListener(): Listener? = try {
        val listenerClass = Class.forName(
            "ru.arc.treasurechests.TreasureHuntGrappleSyncListener",
            true,
            plugin.javaClass.classLoader,
        )
        val constructor = listenerClass.getDeclaredConstructor(Function1::class.java)
        constructor.isAccessible = true
        constructor.newInstance(::onHuskSyncComplete) as? Listener
    } catch (failure: ReflectiveOperationException) {
        error("Could not link treasure-hunt grapple to HuskSync", failure)
        null
    } catch (failure: LinkageError) {
        error("Could not link treasure-hunt grapple to HuskSync", failure)
        null
    }

    fun reloadConfig(newSettings: TreasureHuntGrappleSettings) {
        if (closed) return
        settings = newSettings
        stopAllShots(dampVelocity = true)
        plugin.server.onlinePlayers.toList().forEach(::removeTaggedItems)
        onActiveHuntsChanged(activeHunts)
    }

    /** Called only after the hunt service has committed its active-hunt change. */
    fun onActiveHuntsChanged(hunts: Collection<ActiveHunt>) {
        if (closed) return
        activeHunts = hunts.toList()
        val hasSpawnHunt = activeHunts.any { it.world.name == SPAWN_WORLD }
        if (!hasSpawnHunt || settings?.enabled != true) {
            activeSessionId = null
            stopAllShots(dampVelocity = true)
            plugin.server.onlinePlayers.toList().forEach(::removeAndForget)
            fullHotbarPlayers.clear()
            stopHotbarRetry()
            return
        }
        if (activeSessionId == null) activeSessionId = UUID.randomUUID().toString()
        plugin.server.onlinePlayers.toList().forEach { player ->
            if (isEligible(player)) ensureHook(player) else removeAndForget(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        removeTaggedItems(event.player)
        if (huskSyncAvailable) {
            pendingHuskSyncPlayers += event.player.uniqueId
            return
        }
        if (isEligible(event.player)) ensureHook(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        stopShot(event.player.uniqueId, dampVelocity = true, allowWorldMismatch = true)
        if (isEligible(event.player)) ensureHook(event.player) else removeAndForget(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val fromWorld = event.from.world?.uid
        val toWorld = event.to.world?.uid
        if (fromWorld != toWorld || event.from.distanceSquared(event.to) > 9.0) {
            stopShot(event.player.uniqueId, dampVelocity = true)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        pendingHuskSyncPlayers.remove(event.player.uniqueId)
        stopShot(event.player.uniqueId, dampVelocity = false)
        removeAndForget(event.player)
        respawnRetries.remove(event.player.uniqueId)?.cancel()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf(::isTagged)
        stopShot(event.player.uniqueId, dampVelocity = false)
        nextAllowedTick.remove(event.player.uniqueId)
        removeTaggedItems(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        val id = event.player.uniqueId
        respawnRetries.remove(id)?.cancel()
        if (event.respawnLocation.world?.name != SPAWN_WORLD || activeSessionId == null) return
        respawnRetries[id] = scheduler.runLater(1L) {
            respawnRetries.remove(id)
            plugin.server.getPlayer(id)?.takeIf(::isEligible)?.let(::ensureHook)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !isTagged(event.item)) return
        event.setUseItemInHand(Event.Result.DENY)
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR &&
            event.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
        ) {
            return
        }
        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)

        val player = event.player
        val config = settings ?: return
        val session = activeSessionId ?: return
        if (!isEligible(player) || !isCurrentTool(event.item, session)) {
            removeTaggedItems(player)
            return
        }
        val now = player.ticksLived.toLong()
        val playerId = player.uniqueId
        val activeShot = shots[playerId]
        if (activeShot != null) {
            val redirectCooldown = minOf(config.cooldownTicks, ACTIVE_REDIRECT_COOLDOWN_TICKS)
            if (activeShot.ageTicks.toLong() < redirectCooldown) return
            // Retargeting replaces packet visuals only; preserve any velocity from an active pull.
            stopShot(playerId, dampVelocity = false)
        } else if (now < (nextAllowedTick[playerId] ?: Long.MIN_VALUE)) {
            return
        }
        nextAllowedTick[playerId] = now + config.cooldownTicks
        shoot(player, session, config)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrop(event: PlayerDropItemEvent) {
        if (isTagged(event.itemDrop.itemStack)) {
            event.isCancelled = true
            return
        }
        retryWhenSpaceMayBeAvailable(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val hotbarItem = event.hotbarButton.takeIf { it in 0..8 }?.let(player.inventory::getItem)
        val offhandSwap = event.click == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
        if (isTagged(event.currentItem) || isTagged(event.cursor) || isTagged(hotbarItem) ||
            (offhandSwap && isTagged(player.inventory.itemInOffHand))
        ) {
            event.isCancelled = true
        } else {
            retryWhenSpaceMayBeAvailable(player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (isTagged(event.oldCursor) || event.newItems.values.any(::isTagged)) {
            event.isCancelled = true
        } else {
            retryWhenSpaceMayBeAvailable(player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        (event.player as? Player)?.let(::retryWhenSpaceMayBeAvailable)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        if (event.inventory.matrix.any(::isTagged)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        if (isTagged(event.item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryPickup(event: InventoryPickupItemEvent) {
        if (isTagged(event.item.itemStack)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityPickup(event: EntityPickupItemEvent) {
        if (isTagged(event.item.itemStack)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHandSwap(event: PlayerSwapHandItemsEvent) {
        if (isTagged(event.mainHandItem) || isTagged(event.offHandItem)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHeldSlotChange(event: PlayerItemHeldEvent) {
        if (shots.containsKey(event.player.uniqueId)) {
            scheduler.runLater(1L) {
                val player = plugin.server.getPlayer(event.player.uniqueId) ?: return@runLater
                val shot = shots[player.uniqueId] ?: return@runLater
                if (!isCurrentTool(player.inventory.itemInMainHand, shot.sessionId)) {
                    stopShot(player.uniqueId, dampVelocity = true)
                }
            }
        }
    }

    private fun shoot(player: Player, session: String, config: TreasureHuntGrappleSettings) {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        if (direction.isZero) return

        val hit = player.world.rayTraceBlocks(
            eye,
            direction,
            config.maxDistance,
            FluidCollisionMode.NEVER,
            true,
        )
        val target = hit?.hitPosition ?: eye.toVector().add(direction.multiply(config.maxDistance))
        val distance = eye.toVector().distance(target)
        val travelTicks = ceil(distance / config.projectileBlocksPerTick).toInt().coerceIn(1, MAX_SHOT_LIFETIME_TICKS)
        var head: PacketItemDisplay? = null
        var registeredShot: Shot? = null
        val cable = mutableListOf<PacketBlockDisplay>()
        try {
            val createdHead = displays.spawnItem(eye, ItemStackFactory.create(Material.TRIPWIRE_HOOK))
            head = createdHead
            createdHead.apply {
                isVisibleByDefault = false
                showTo(player)
                itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                brightness = org.bukkit.entity.Display.Brightness(15, 15)
                isGlowing = true
                glowColorOverride = CABLE_GLOW
                viewRange = 0.7f
                transformation = Transformation(
                    Vector3f(),
                    Quaternionf(),
                    Vector3f(0.55f, 0.55f, 0.55f),
                    Quaternionf(),
                )
            }
            repeat(config.cableSegments) {
                val display = displays.spawnBlock(eye, Material.IRON_CHAIN.createBlockData())
                cable += display
                display.apply {
                    isVisibleByDefault = false
                    showTo(player)
                    brightness = org.bukkit.entity.Display.Brightness(15, 15)
                    isGlowing = true
                    glowColorOverride = CABLE_GLOW
                    viewRange = 0.7f
                    interpolationDuration = 1
                }
            }
            val shot = Shot(
                playerId = player.uniqueId,
                sessionId = session,
                worldId = player.world.uid,
                target = target.clone(),
                targetBlock = hit?.hitBlock,
                travelTicks = travelTicks,
                head = createdHead,
                cable = cable,
            )
            updateVisuals(shot, eye.toVector(), eye.toVector(), player.world)
            shots[player.uniqueId] = shot
            registeredShot = shot
            player.world.playSound(eye, Sound.ITEM_TRIDENT_THROW, 0.7f, 1.25f)
            player.world.spawnParticle(Particle.END_ROD, eye, 5, 0.12, 0.12, 0.12, 0.015)
            startShotTask()
        } catch (failure: RuntimeException) {
            registeredShot?.let { if (shots[player.uniqueId] === it) shots.remove(player.uniqueId) }
            head?.remove()
            cable.forEach(PacketDisplay::remove)
            if (shots.isEmpty()) {
                shotTask?.cancel()
                shotTask = null
            }
            error("Could not create treasure-hunt grapple visuals for player " + player.uniqueId, failure)
            player.sendActionBar(config.messages.missed)
        }
    }

    private fun startShotTask() {
        if (shotTask == null) shotTask = scheduler.runTimer(1L, 1L) { tickShots() }
    }

    private fun tickShots() {
        if (closed) return
        for (shot in shots.values.toList()) {
            val player = plugin.server.getPlayer(shot.playerId)
            if (player == null || !isShotValid(player, shot) || shot.ageTicks >= MAX_SHOT_LIFETIME_TICKS) {
                val damp = player != null && player.isOnline && !player.isDead && shot.pulling
                val worldChanged = player?.world?.uid != shot.worldId
                stopShot(shot.playerId, dampVelocity = damp, allowWorldMismatch = worldChanged)
                continue
            }
            shot.ageTicks++
            if (shot.pulling) tickPull(player, shot) else tickFlight(player, shot)
        }
        if (shots.isEmpty()) {
            shotTask?.cancel()
            shotTask = null
        }
    }

    private fun tickFlight(player: Player, shot: Shot) {
        val progress = (shot.ageTicks.toDouble() / shot.travelTicks).coerceIn(0.0, 1.0)
        val headPosition = TreasureHuntGrappleMath.interpolate(player.eyeLocation.toVector(), shot.target, progress)
        updateVisuals(shot, player.eyeLocation.toVector(), headPosition, player.world)
        shot.head.teleport(headPosition.toLocation(player.world))
        if (progress < 1.0) return

        if (shot.targetBlock == null) {
            player.sendActionBar(settings?.messages?.missed ?: Component.empty())
            stopShot(player.uniqueId, dampVelocity = false)
            return
        }
        val config = settings ?: run {
            stopShot(player.uniqueId, dampVelocity = false)
            return
        }
        shot.pulling = true
        shot.targetBlock.location.toCenterLocation().world?.let { world ->
            val targetLocation = shot.target.toLocation(world)
            world.spawnParticle(Particle.ELECTRIC_SPARK, targetLocation, 12, 0.16, 0.16, 0.16, 0.03)
            world.spawnParticle(Particle.END_ROD, targetLocation, 5, 0.12, 0.12, 0.12, 0.01)
            world.playSound(targetLocation, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.5f)
        }
        player.sendActionBar(config.messages.pulling)
    }

    private fun tickPull(player: Player, shot: Shot) {
        val config = settings ?: run {
            stopShot(player.uniqueId, dampVelocity = false)
            return
        }
        val block = shot.targetBlock
        if (block == null || block.type.isAir || !block.type.isSolid ||
            block.world.uid != shot.worldId
        ) {
            stopShot(player.uniqueId, dampVelocity = true)
            return
        }
        val center = player.location.toVector().add(Vector(0.0, BODY_CENTER_Y, 0.0))
        val delta = shot.target.clone().subtract(center)
        val distance = delta.length()
        if (distance <= config.stopDistance || shot.ageTicks - shot.travelTicks >= config.maxPullTicks) {
            stopShot(player.uniqueId, dampVelocity = true)
            return
        }
        val clearDistance = (distance - config.stopDistance).coerceAtLeast(0.0)
        if (clearDistance > 0.1) {
            val obstacle = player.world.rayTraceBlocks(
                center.toLocation(player.world),
                delta.clone().normalize(),
                clearDistance,
                FluidCollisionMode.NEVER,
                true,
            )
            if (obstacle != null) {
                stopShot(player.uniqueId, dampVelocity = true)
                return
            }
        }
        val velocity = TreasureHuntGrappleMath.pullVelocity(center, shot.target, config.stopDistance, config.pullSpeed)
        if (velocity == null) {
            stopShot(player.uniqueId, dampVelocity = true)
            return
        }
        player.velocity = velocity
        updateVisuals(shot, player.eyeLocation.toVector(), shot.target, player.world)
    }

    private fun updateVisuals(shot: Shot, from: Vector, to: Vector, world: World) {
        val segments = TreasureHuntGrappleMath.cableSegments(from, to, shot.cable.size)
        shot.cable.forEachIndexed { index, display ->
            val segment = segments.getOrNull(index)
            if (segment == null) {
                display.transformation = Transformation(
                    Vector3f(),
                    Quaternionf(),
                    Vector3f(0.001f, 0.001f, 0.001f),
                    Quaternionf(),
                )
                return@forEachIndexed
            }
            val transform = TreasureHuntGrappleMath.cableTransform(segment, CABLE_WIDTH)
            display.teleport(segment.center.toLocation(world))
            display.transformation = Transformation(
                transform.translation,
                transform.rotation,
                Vector3f(CABLE_WIDTH, segment.length.toFloat(), CABLE_WIDTH),
                Quaternionf(),
            )
        }
    }

    private fun isShotValid(player: Player, shot: Shot): Boolean =
        player.isOnline && !player.isDead && player.world.uid == shot.worldId &&
            activeSessionId == shot.sessionId &&
            activeHunts.any { it.world.uid == shot.worldId } &&
            isCurrentTool(player.inventory.itemInMainHand, shot.sessionId)

    private fun stopShot(playerId: UUID, dampVelocity: Boolean, allowWorldMismatch: Boolean = false) {
        val shot = shots.remove(playerId) ?: return
        shot.head.remove()
        shot.cable.forEach(PacketDisplay::remove)
        if (dampVelocity && shot.pulling) {
            plugin.server.getPlayer(playerId)
                ?.takeIf { it.isOnline && !it.isDead && (allowWorldMismatch || it.world.uid == shot.worldId) }
                ?.velocity = Vector()
        }
    }

    private fun stopAllShots(dampVelocity: Boolean) {
        shots.keys.toList().forEach { stopShot(it, dampVelocity = dampVelocity) }
        shotTask?.cancel()
        shotTask = null
    }

    private fun ensureHook(player: Player): Boolean {
        if (player.uniqueId in pendingHuskSyncPlayers) return false
        val session = activeSessionId ?: return false
        val config = settings ?: return false
        if (!isEligible(player)) return false

        val inventory = player.inventory
        val currentSlot = (0..8).firstOrNull { slot -> isCurrentTool(inventory.getItem(slot), session) }
        clearTaggedItems(player, keepSlot = currentSlot)
        if (currentSlot != null) {
            inventory.getItem(currentSlot)?.let { item ->
                if (item.amount != 1) {
                    item.amount = 1
                    inventory.setItem(currentSlot, item)
                }
            }
            fullHotbarPlayers.remove(player.uniqueId)
            if (fullHotbarPlayers.isEmpty()) stopHotbarRetry()
            return true
        }

        val freeSlot = (0..8).firstOrNull { slot ->
            val item = inventory.getItem(slot)
            item == null || item.type.isAir
        }
        if (freeSlot == null) {
            if (fullHotbarPlayers.add(player.uniqueId)) player.sendActionBar(config.messages.inventoryFull)
            startHotbarRetry()
            return false
        }
        inventory.setItem(freeSlot, createHook(session, config))
        fullHotbarPlayers.remove(player.uniqueId)
        if (fullHotbarPlayers.isEmpty()) stopHotbarRetry()
        player.sendActionBar(config.messages.itemReceived)
        return true
    }

    private fun createHook(session: String, config: TreasureHuntGrappleSettings) =
        ItemStackFactory.create(Material.TRIPWIRE_HOOK).apply {
            editMeta { meta ->
                meta.displayName(config.itemName)
                meta.lore(config.itemLore)
                meta.persistentDataContainer.set(hookKey, PersistentDataType.BYTE, 1.toByte())
                meta.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, session)
            }
        }

    private fun isTagged(item: ItemStack?): Boolean =
        item?.itemMeta?.persistentDataContainer?.get(hookKey, PersistentDataType.BYTE) == 1.toByte()

    private fun isCurrentTool(item: ItemStack?, session: String): Boolean =
        isTagged(item) &&
            item?.itemMeta?.persistentDataContainer?.get(sessionKey, PersistentDataType.STRING) == session

    private fun clearTaggedItems(player: Player, keepSlot: Int? = null) {
        val inventory = player.inventory
        inventory.contents.indices
            .filter { it != keepSlot && isTagged(inventory.getItem(it)) }
            .forEach { inventory.setItem(it, null) }
        if (isTagged(player.itemOnCursor)) player.setItemOnCursor(null)
    }

    private fun removeAndForget(player: Player) {
        stopShot(player.uniqueId, dampVelocity = false)
        removeTaggedItems(player)
        fullHotbarPlayers.remove(player.uniqueId)
        nextAllowedTick.remove(player.uniqueId)
        respawnRetries.remove(player.uniqueId)?.cancel()
    }

    private fun removeTaggedItems(player: Player) = clearTaggedItems(player)

    private fun onHuskSyncComplete(playerId: UUID) {
        val reconcile = Runnable {
            if (closed) return@Runnable
            pendingHuskSyncPlayers.remove(playerId)
            val player = plugin.server.getPlayer(playerId) ?: return@Runnable
            if (isEligible(player)) ensureHook(player) else removeAndForget(player)
        }
        if (Bukkit.isPrimaryThread()) reconcile.run() else scheduler.runSync(reconcile)
    }

    private fun isEligible(player: Player): Boolean =
        player.uniqueId !in pendingHuskSyncPlayers && activeSessionId != null && settings?.enabled == true &&
            player.world.name == SPAWN_WORLD &&
            activeHunts.any { it.world.uid == player.world.uid }

    private fun retryWhenSpaceMayBeAvailable(player: Player) {
        if (player.uniqueId in fullHotbarPlayers && isEligible(player)) ensureHook(player)
    }

    private fun startHotbarRetry() {
        if (fullHotbarRetry != null || closed) return
        fullHotbarRetry = scheduler.runTimer(20L, 20L) {
            for (id in fullHotbarPlayers.toList()) {
                val player = plugin.server.getPlayer(id)
                if (player == null || !isEligible(player)) {
                    fullHotbarPlayers.remove(id)
                } else {
                    ensureHook(player)
                }
            }
            if (fullHotbarPlayers.isEmpty()) stopHotbarRetry()
        }
    }

    private fun stopHotbarRetry() {
        fullHotbarRetry?.cancel()
        fullHotbarRetry = null
    }

    override fun close() {
        if (closed) return
        closed = true
        stopAllShots(dampVelocity = true)
        stopHotbarRetry()
        respawnRetries.values.forEach(ScheduledTask::cancel)
        respawnRetries.clear()
        fullHotbarPlayers.clear()
        pendingHuskSyncPlayers.clear()
        nextAllowedTick.clear()
        activeSessionId = null
        activeHunts = emptyList()
        plugin.server.onlinePlayers.toList().forEach(::removeTaggedItems)
        HandlerList.unregisterAll(this)
        huskSyncListener?.let { HandlerList.unregisterAll(it) }
        displays.close()
    }
}
