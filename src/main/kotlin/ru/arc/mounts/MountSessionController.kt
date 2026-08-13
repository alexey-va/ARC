package ru.arc.mounts

import org.bukkit.Input
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class MountSpawnResult {
    SUCCESS,
    ALREADY_RIDING,
    ALREADY_IN_VEHICLE,
    WORLD_NOT_ALLOWED,
    WATER_REQUIRED,
    INVALID_ENTITY,
    SPAWN_FAILED,
}

enum class MountRemovalReason {
    DISMOUNTED,
    DOUBLE_SNEAK,
    DAMAGED,
    EXPIRED,
    LEFT_WATER,
    HEIGHT_LIMIT,
    TELEPORTED,
    CHANGED_WORLD,
    QUIT,
    DIED,
    RELOAD,
    INVALID,
}

private data class MountSession(
    val playerId: UUID,
    val entityId: UUID,
    val definition: MountDefinition,
    val speed: Double,
    val expiresAtMillis: Long,
    val sneakGesture: DoubleSneakGesture,
    var input: MountInputState = MountInputState(),
    var allowDismount: Boolean = false,
    var stopping: Boolean = false,
    var lastHintAtMillis: Long = Long.MIN_VALUE,
)

class MountSessionController(
    private val plugin: JavaPlugin,
    private val scheduler: TaskScheduler,
    private val configProvider: () -> MountModuleConfig,
    private val message: (Player, String, String) -> Unit,
) : Listener {
    private val sessionsByPlayer = ConcurrentHashMap<UUID, MountSession>()
    private val playerByEntity = ConcurrentHashMap<UUID, UUID>()
    private val ownerKey = NamespacedKey(plugin, "mount_owner")
    private val mountIdKey = NamespacedKey(plugin, "mount_id")
    private var tickTask: ScheduledTask? = null

    fun start() {
        if (tickTask != null) return
        plugin.server.pluginManager.registerEvents(this, plugin)
        tickTask = scheduler.runTimer(1L, 1L, Runnable(::tick))
    }

    fun stopAll(reason: MountRemovalReason = MountRemovalReason.RELOAD) {
        sessionsByPlayer.keys.toList().forEach { remove(it, reason) }
        sessionsByPlayer.clear()
        playerByEntity.clear()
    }

    fun shutdown() {
        stopAll()
        tickTask?.cancel()
        tickTask = null
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    fun isRiding(playerId: UUID): Boolean = sessionsByPlayer.containsKey(playerId)

    fun spawn(
        player: Player,
        definition: MountDefinition,
        speed: Double,
        durationMillis: Long,
        glow: Boolean,
    ): MountSpawnResult {
        val config = configProvider()
        if (sessionsByPlayer.containsKey(player.uniqueId)) return MountSpawnResult.ALREADY_RIDING
        if (player.vehicle != null) return MountSpawnResult.ALREADY_IN_VEHICLE
        if (config.allowedWorlds.isNotEmpty() && player.world.name.lowercase(java.util.Locale.ROOT) !in config.allowedWorlds) {
            return MountSpawnResult.WORLD_NOT_ALLOWED
        }
        if (definition.movement == MountMovement.SWIMMING && !player.location.block.isLiquid) {
            return MountSpawnResult.WATER_REQUIRED
        }

        val entityType = runCatching { org.bukkit.entity.EntityType.valueOf(definition.entityType) }.getOrNull()
            ?: return MountSpawnResult.INVALID_ENTITY
        if (!entityType.isAlive || !entityType.isSpawnable) return MountSpawnResult.INVALID_ENTITY
        val spawned = runCatching { player.world.spawnEntity(player.location, entityType) }.getOrNull() as? LivingEntity
            ?: return MountSpawnResult.SPAWN_FAILED

        return try {
            configureEntity(spawned, definition, glow, player)
            if (!spawned.addPassenger(player)) {
                spawned.remove()
                return MountSpawnResult.SPAWN_FAILED
            }
            val session =
                MountSession(
                    playerId = player.uniqueId,
                    entityId = spawned.uniqueId,
                    definition = definition,
                    speed = speed,
                    expiresAtMillis = System.currentTimeMillis() + durationMillis.coerceAtLeast(1L),
                    sneakGesture = DoubleSneakGesture(config.doubleSneakWindow.toMillis()),
                )
            sessionsByPlayer[player.uniqueId] = session
            playerByEntity[spawned.uniqueId] = player.uniqueId
            player.world.spawnParticle(Particle.END_ROD, player.location, 10, 0.4, 0.4, 0.4, 0.01)
            player.world.playSound(player.location, Sound.ENTITY_HORSE_SADDLE, 1.0f, 1.0f)
            if (definition.movement != MountMovement.WALKING) {
                player.sendActionBar(TextUtil.mm(config.message("flight-controls", "<gray>Space — вверх, Shift — вниз, двойной Shift — спешиться"), true))
            }
            MountSpawnResult.SUCCESS
        } catch (failure: Throwable) {
            spawned.remove()
            warn("Unable to spawn mount {} for {}: {}", definition.id, player.name, failure.javaClass.simpleName)
            MountSpawnResult.SPAWN_FAILED
        }
    }

    fun remove(playerId: UUID, reason: MountRemovalReason) {
        val session = sessionsByPlayer.remove(playerId) ?: return
        if (session.stopping) return
        session.stopping = true
        playerByEntity.remove(session.entityId)
        val player = plugin.server.getPlayer(playerId)
        val entity = plugin.server.getEntity(session.entityId) as? LivingEntity
        val effectLocation = entity?.location ?: player?.location

        runCatching { entity?.remove() }
        if (effectLocation != null && effectLocation.world != null) {
            effectLocation.world.spawnParticle(Particle.SOUL, effectLocation, 20, 0.6, 0.6, 0.6, 0.02)
            effectLocation.world.playSound(effectLocation, Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 1.15f)
        }
        if (player != null && session.definition.movement == MountMovement.FLYING && reason != MountRemovalReason.DIED) {
            val ticks = (configProvider().postFlightSlowFalling.toMillis() / 50L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            if (ticks > 0) player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, false, false, true))
        }
        if (player != null && reason == MountRemovalReason.EXPIRED) {
            message(player, "expired", "<gray>Время поездки на маунте закончилось.")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInput(event: PlayerInputEvent) {
        val session = sessionsByPlayer[event.player.uniqueId] ?: return
        val updated = event.input.toState()
        session.input = updated
        if (session.definition.movement == MountMovement.WALKING) return

        when (session.sneakGesture.update(updated.sneak, System.currentTimeMillis())) {
            SneakGestureResult.DOUBLE_PRESSED -> {
                session.allowDismount = true
                remove(event.player.uniqueId, MountRemovalReason.DOUBLE_SNEAK)
            }
            SneakGestureResult.PRESSED -> {
                val config = configProvider()
                val now = System.currentTimeMillis()
                if (session.lastHintAtMillis == Long.MIN_VALUE || now - session.lastHintAtMillis >= config.descendingHintCooldown.toMillis()) {
                    session.lastHintAtMillis = now
                    event.player.sendActionBar(
                        TextUtil.mm(config.message("descending", "<aqua>Снижение… <gray>двойной Shift — спешиться"), true),
                    )
                }
            }
            SneakGestureResult.NONE -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        val session = sessionsByPlayer[player.uniqueId] ?: return
        if (event.dismounted.uniqueId != session.entityId || session.stopping) return

        if (session.definition.movement != MountMovement.WALKING && !session.allowDismount && event.isCancellable) {
            event.isCancelled = true
            return
        }
        scheduler.runLater(1L, Runnable { remove(player.uniqueId, MountRemovalReason.DISMOUNTED) })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMountDamage(event: EntityDamageEvent) {
        val mountedPlayer = playerByEntity[event.entity.uniqueId]
        if (mountedPlayer != null) {
            event.isCancelled = true
            remove(mountedPlayer, MountRemovalReason.DAMAGED)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRiderDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!sessionsByPlayer.containsKey(player.uniqueId)) return
        scheduler.runLater(1L, Runnable { remove(player.uniqueId, MountRemovalReason.DAMAGED) })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCombust(event: EntityCombustEvent) {
        if (playerByEntity.containsKey(event.entity.uniqueId)) event.isCancelled = true
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) = remove(event.player.uniqueId, MountRemovalReason.QUIT)
    @EventHandler fun onDeath(event: PlayerDeathEvent) = remove(event.entity.uniqueId, MountRemovalReason.DIED)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = remove(event.player.uniqueId, MountRemovalReason.CHANGED_WORLD)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (sessionsByPlayer.containsKey(event.player.uniqueId)) {
            scheduler.runLater(1L, Runnable { remove(event.player.uniqueId, MountRemovalReason.TELEPORTED) })
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        sessionsByPlayer.values.toList().forEach { session ->
            val player = plugin.server.getPlayer(session.playerId)
            val entity = plugin.server.getEntity(session.entityId) as? LivingEntity
            when {
                player == null || !player.isOnline -> remove(session.playerId, MountRemovalReason.QUIT)
                entity == null || !entity.isValid || !entity.passengers.contains(player) -> remove(session.playerId, MountRemovalReason.INVALID)
                now >= session.expiresAtMillis -> remove(session.playerId, MountRemovalReason.EXPIRED)
                entity.location.y > entity.world.maxHeight + configProvider().maximumHeightAboveWorld -> {
                    remove(session.playerId, MountRemovalReason.HEIGHT_LIMIT)
                }
                session.definition.movement == MountMovement.SWIMMING && !entity.location.block.isLiquid -> {
                    remove(session.playerId, MountRemovalReason.LEFT_WATER)
                }
                else -> move(player, entity, session)
            }
        }
    }

    private fun move(player: Player, entity: LivingEntity, session: MountSession) {
        val config = configProvider()
        val speedScale =
            when (session.definition.movement) {
                MountMovement.WALKING -> config.walkingSpeedScale
                MountMovement.FLYING -> config.flyingSpeedScale
                MountMovement.SWIMMING -> config.swimmingSpeedScale
            }
        val sprint = if (session.input.sprint) config.sprintMultiplier else 1.0
        val maximumSpeed = session.speed * speedScale * sprint
        val planar = MountMotion.planarDirection(player.location.yaw, session.input)
        val target =
            when (session.definition.movement) {
                MountMovement.WALKING -> planar * maximumSpeed
                MountMovement.FLYING,
                MountMovement.SWIMMING,
                -> MountMotion.airborneTarget(
                    pitchDegrees = player.location.pitch,
                    input = session.input,
                    planar = planar,
                    maximumSpeed = maximumSpeed,
                    verticalSpeedRatio = config.verticalSpeedRatio,
                    maximumVerticalSpeed = config.maximumVerticalSpeed,
                    pitchInfluence = config.flightPitchInfluence,
                )
            }
        val current = entity.velocity.toMotion()

        val velocity =
            if (session.definition.movement == MountMovement.WALKING) {
                val horizontal =
                    MountMotion.smooth(
                        MotionVector(current.x, 0.0, current.z),
                        target,
                        config.acceleration,
                        config.deceleration,
                    )
                val vertical =
                    if (session.input.jump && entity.isOnGround) config.jumpVelocity
                    else current.y
                MotionVector(horizontal.x, vertical, horizontal.z)
            } else {
                MountMotion.smooth(current, target, config.acceleration, config.deceleration)
            }

        entity.velocity = velocity.toBukkit()
        entity.fallDistance = 0.0f
        val targetYaw = MountMotion.facingYaw(target, entity.yaw)
        entity.setRotation(MountMotion.smoothYaw(entity.yaw, targetYaw, config.turnSmoothing), 0.0f)
    }

    private fun configureEntity(
        entity: LivingEntity,
        definition: MountDefinition,
        glow: Boolean,
        player: Player,
    ) {
        entity.customName(TextUtil.mm("<gray>${player.name}", true))
        entity.isCustomNameVisible = false
        entity.isPersistent = false
        entity.isGlowing = glow
        entity.isInvulnerable = false
        entity.setGravity(definition.movement == MountMovement.WALKING)
        (entity as? Mob)?.let { mob ->
            mob.setAI(false)
            mob.canPickupItems = false
            mob.removeWhenFarAway = false
        }
        entity.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
        entity.persistentDataContainer.set(mountIdKey, PersistentDataType.STRING, definition.id)
    }

    private fun Input.toState(): MountInputState =
        MountInputState(
            forward = isForward,
            backward = isBackward,
            left = isLeft,
            right = isRight,
            jump = isJump,
            sneak = isSneak,
            sprint = isSprint,
        )

    private fun Vector.toMotion() = MotionVector(x, y, z)
    private fun MotionVector.toBukkit() = Vector(x, y, z)
}
