package ru.arc.mounts

import org.bukkit.Input
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Bat
import org.bukkit.entity.Horse
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
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
    COOLDOWN,
    INVALID_ENTITY,
    SPAWN_FAILED,
}

enum class MountRemovalReason {
    DISMOUNTED,
    DOUBLE_SNEAK,
    EXPIRED,
    LEFT_WATER,
    HEIGHT_LIMIT,
    WORLD_BORDER,
    IDLE,
    TELEPORTED,
    CHANGED_WORLD,
    QUIT,
    DIED,
    KNOCKED_OFF,
    RELOAD,
    INVALID,
}

private data class MountSession(
    val playerId: UUID,
    val entityId: UUID,
    val definition: MountDefinition,
    val speed: Double,
    val walkingStepHeight: Double,
    val handlingMultiplier: Double,
    val sprintMultiplier: Double,
    val skin: MountSkinDefinition?,
    val abilityUpgrades: List<MountAbilityUpgradeDefinition>,
    val expiresAtMillis: Long,
    val sneakGesture: DoubleSneakGesture,
    var input: MountInputState = MountInputState(),
    var allowDismount: Boolean = false,
    var stopping: Boolean = false,
    var lastHintAtMillis: Long = Long.MIN_VALUE,
    var lastActiveAtMillis: Long = System.currentTimeMillis(),
    var ticks: Long = 0L,
    var riderMountHidden: Boolean = false,
)

class MountSessionController(
    private val plugin: JavaPlugin,
    private val scheduler: TaskScheduler,
    private val configProvider: () -> MountModuleConfig,
    private val allowedMountIds: Set<String>,
    private val message: (Player, String, String) -> Unit,
    private val onStateChanged: () -> Unit = {},
    private val setRiderMountHidden: (Player, LivingEntity, Boolean) -> Unit = { _, _, _ -> },
) : Listener {
    private val sessionsByPlayer = ConcurrentHashMap<UUID, MountSession>()
    private val playerByEntity = ConcurrentHashMap<UUID, UUID>()
    private val ownerKey = NamespacedKey(plugin, "mount_owner")
    private val mountIdKey = NamespacedKey(plugin, "mount_id")
    private val spawnTokenKey = NamespacedKey(plugin, "mount_spawn_token")
    private val airborneMiningModifier =
        AttributeModifier(
            NamespacedKey(plugin, "mount_airborne_mining"),
            airborneMiningCompensationAmount(),
            AttributeModifier.Operation.MULTIPLY_SCALAR_1,
        )
    private val pendingSpawnTokens = ConcurrentHashMap.newKeySet<UUID>()
    private val lastSummonAt = ConcurrentHashMap<UUID, Long>()
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
        pendingSpawnTokens.clear()
    }

    fun shutdown() {
        stopAll()
        tickTask?.cancel()
        tickTask = null
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    fun isRiding(playerId: UUID): Boolean = sessionsByPlayer.containsKey(playerId)

    fun activeSessionCount(): Int = sessionsByPlayer.size

    fun spawn(
        player: Player,
        definition: MountDefinition,
        speed: Double,
        walkingStepHeight: Double,
        handlingMultiplier: Double = 1.0,
        sprintMultiplier: Double = 1.0,
        durationMillis: Long,
        glow: Boolean,
        skin: MountSkinDefinition? = null,
        abilityUpgrades: List<MountAbilityUpgradeDefinition> = emptyList(),
    ): MountSpawnResult {
        val config = configProvider()
        require(walkingStepHeight.isFinite() && walkingStepHeight in 0.6..4.0) {
            "Walking mount step height must be between 0.60 and 4.00 blocks"
        }
        val now = System.currentTimeMillis()
        lastSummonAt.entries.removeIf { now - it.value >= config.summonCooldown.toMillis() }
        if (sessionsByPlayer.containsKey(player.uniqueId)) return MountSpawnResult.ALREADY_RIDING
        if (player.vehicle != null) return MountSpawnResult.ALREADY_IN_VEHICLE
        val lastSummon = lastSummonAt[player.uniqueId]
        if (lastSummon != null && now - lastSummon < config.summonCooldown.toMillis()) return MountSpawnResult.COOLDOWN
        if (config.allowedWorlds.isNotEmpty() && player.world.name.lowercase(java.util.Locale.ROOT) !in config.allowedWorlds) {
            return MountSpawnResult.WORLD_NOT_ALLOWED
        }
        if (
            definition.movement == MountMovement.SWIMMING &&
            !isAquaticEnvironment(
                player.isInWater || player.location.block.type == org.bukkit.Material.BUBBLE_COLUMN,
                player.location.block.isLiquid,
            )
        ) {
            return MountSpawnResult.WATER_REQUIRED
        }
        require(abilityUpgrades.all { definition.ability(it.id) == it }) { "Active mount ability is not configured for ${definition.id}" }

        val entityType = runCatching { org.bukkit.entity.EntityType.valueOf(definition.entityType) }.getOrNull()
            ?: return MountSpawnResult.INVALID_ENTITY
        if (!entityType.isAlive || !entityType.isSpawnable) return MountSpawnResult.INVALID_ENTITY
        val spawnToken = UUID.randomUUID()
        pendingSpawnTokens.add(spawnToken)
        val spawned =
            try {
                player.world.spawnEntity(
                    player.location,
                    entityType,
                    CreatureSpawnEvent.SpawnReason.CUSTOM,
                ) { entity ->
                    (entity as? LivingEntity)?.let {
                        tagEntity(it, definition, player)
                        it.persistentDataContainer.set(spawnTokenKey, PersistentDataType.STRING, spawnToken.toString())
                    }
                }
            } catch (_: Throwable) {
                null
            } finally {
                pendingSpawnTokens.remove(spawnToken)
            } as? LivingEntity
            ?: return MountSpawnResult.SPAWN_FAILED
        if (!spawned.isInWorld || !spawned.isValid) {
            spawned.remove()
            return MountSpawnResult.SPAWN_FAILED
        }

        return try {
            configureEntity(spawned, definition, glow, player, skin, walkingStepHeight)
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
                    walkingStepHeight = walkingStepHeight,
                    handlingMultiplier = handlingMultiplier,
                    sprintMultiplier = sprintMultiplier,
                    skin = skin,
                    abilityUpgrades = abilityUpgrades,
                    expiresAtMillis = System.currentTimeMillis() + durationMillis.coerceAtLeast(1L),
                    sneakGesture = DoubleSneakGesture(config.doubleSneakWindow.toMillis()),
                )
            sessionsByPlayer[player.uniqueId] = session
            playerByEntity[spawned.uniqueId] = player.uniqueId
            setAirborneMiningCompensation(
                player,
                airborneMiningModifier,
                definition.movement == MountMovement.FLYING && config.compensateAirborneMining,
            )
            lastSummonAt[player.uniqueId] = now
            onStateChanged()
            player.world.spawnParticle(Particle.END_ROD, player.location, 10, 0.4, 0.4, 0.4, 0.01)
            player.world.playSound(player.location, Sound.ENTITY_HORSE_SADDLE, 1.0f, 1.0f)
            val (controlsKey, controlsFallback) =
                when {
                    spawned is Horse ->
                        "horse-controls" to
                            "<gray>WASD — движение, удерживайте Space и отпустите — прыжок, двойной Shift — спешиться"
                    definition.movement == MountMovement.WALKING ->
                        "ground-controls" to "<gray>WASD — движение, Space — прыжок, двойной Shift — спешиться"
                    else ->
                        "flight-controls" to
                            "<gray>WASD — движение, Space — вверх, Shift — вниз, двойной Shift — спешиться"
                }
            player.sendActionBar(TextUtil.mm(config.message(controlsKey, controlsFallback), true))
            ru.arc.metrics.MetricsModule.recordProductOutcome(
                player,
                ru.arc.metrics.ProductOutcome.MOUNT_RIDE,
                ru.arc.metrics.ProductFeature.MOUNTS,
                ru.arc.metrics.ProductEntryPoint.GAMEPLAY,
            )
            MountSpawnResult.SUCCESS
        } catch (failure: Throwable) {
            sessionsByPlayer.remove(player.uniqueId)
            playerByEntity.remove(spawned.uniqueId)
            setAirborneMiningCompensation(player, airborneMiningModifier, enabled = false)
            spawned.remove()
            warn("Unable to spawn mount {} for {}: {}", definition.id, player.name, failure.javaClass.simpleName)
            MountSpawnResult.SPAWN_FAILED
        }
    }

    fun remove(playerId: UUID, reason: MountRemovalReason) {
        val session = sessionsByPlayer.remove(playerId) ?: return
        if (session.stopping) return
        session.stopping = true
        onStateChanged()
        playerByEntity.remove(session.entityId)
        val player = plugin.server.getPlayer(playerId)
        val entity = plugin.server.getEntity(session.entityId) as? LivingEntity
        val effectLocation = entity?.location ?: player?.location

        if (player != null) {
            if (entity != null && session.riderMountHidden) {
                runCatching { setRiderMountHidden(player, entity, false) }
            }
            setAirborneMiningCompensation(player, airborneMiningModifier, enabled = false)
        }
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
                    val (messageKey, fallback) =
                        if (session.definition.movement == MountMovement.WALKING) {
                            "dismount-hint" to "<yellow>Ещё раз Shift — спешиться"
                        } else {
                            "descending" to "<aqua>Снижение… <gray>двойной Shift — спешиться"
                        }
                    event.player.sendActionBar(
                        TextUtil.mm(config.message(messageKey, fallback), true),
                    )
                }
            }
            SneakGestureResult.NONE -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        val data = event.entity.persistentDataContainer
        if (
            shouldAllowCancelledMountSpawn(
                cancelled = event.isCancelled,
                reason = event.spawnReason,
                owner = data.get(ownerKey, PersistentDataType.STRING),
                mountId = data.get(mountIdKey, PersistentDataType.STRING),
                spawnToken = data.get(spawnTokenKey, PersistentDataType.STRING),
                expectedMountIds = allowedMountIds,
                pendingSpawnTokens = pendingSpawnTokens,
            )
        ) {
            event.isCancelled = false
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        val session = sessionsByPlayer[player.uniqueId] ?: return
        if (event.dismounted.uniqueId != session.entityId || session.stopping) return

        if (shouldCancelUnauthorizedDismount(session.allowDismount, event.isCancellable)) {
            event.isCancelled = true
            return
        }
        scheduler.runLater(1L, Runnable { remove(player.uniqueId, MountRemovalReason.DISMOUNTED) })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMountDamage(event: EntityDamageEvent) {
        if (
            playerByEntity.containsKey(event.entity.uniqueId) &&
            shouldCancelMountDamage(MountDamageTarget.MOUNT, event.cause)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onRiderDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!sessionsByPlayer.containsKey(player.uniqueId)) return
        if (shouldCancelMountDamage(MountDamageTarget.RIDER, event.cause)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRiderKnockoff(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!sessionsByPlayer.containsKey(player.uniqueId)) return
        if (!shouldKnockRiderOff(event.finalDamage, configProvider().riderKnockoffDamage)) return

        scheduler.runLater(1L, Runnable { remove(player.uniqueId, MountRemovalReason.KNOCKED_OFF) })
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
                now - session.lastActiveAtMillis >= configProvider().idleTimeout.toMillis() -> {
                    remove(session.playerId, MountRemovalReason.IDLE)
                }
                entity.location.y > entity.world.maxHeight + configProvider().maximumHeightAboveWorld -> {
                    remove(session.playerId, MountRemovalReason.HEIGHT_LIMIT)
                }
                entity.location.y < entity.world.minHeight - configProvider().maximumHeightAboveWorld -> {
                    remove(session.playerId, MountRemovalReason.HEIGHT_LIMIT)
                }
                !entity.world.worldBorder.isInside(entity.location) -> {
                    remove(session.playerId, MountRemovalReason.WORLD_BORDER)
                }
                session.definition.movement == MountMovement.SWIMMING &&
                    !isAquaticEnvironment(
                        entity.isInWater || entity.location.block.type == org.bukkit.Material.BUBBLE_COLUMN,
                        entity.location.block.isLiquid,
                    ) -> {
                    remove(session.playerId, MountRemovalReason.LEFT_WATER)
                }
                else -> {
                    session.input = player.currentInput.toState()
                    if (session.input.hasMovementIntent) session.lastActiveAtMillis = now
                    session.ticks++
                    (entity as? Mob)?.let(::maintainMountMobState)
                    if (session.ticks == 1L || session.ticks % ABILITY_REFRESH_TICKS == 0L) {
                        refreshAbilityEffects(player, session.abilityUpgrades)
                    }
                    updateRiderMountVisibility(player, entity, session)
                    move(player, entity, session)
                    emitTrail(entity, session)
                }
            }
        }
    }

    private fun updateRiderMountVisibility(player: Player, entity: LivingEntity, session: MountSession) {
        val config = configProvider()
        val hidden =
            config.hideFlyingMountFromRider &&
                nextRiderMountHidden(
                    session.definition.movement,
                    session.riderMountHidden,
                    player.location.pitch,
                    config.hideFlyingMountPitch.toFloat(),
                    config.showFlyingMountPitch.toFloat(),
                )
        if (hidden == session.riderMountHidden) return
        session.riderMountHidden = hidden
        runCatching { setRiderMountHidden(player, entity, hidden) }
            .onFailure { warn("Unable to update rider-only mount visibility for {}: {}", player.name, it.javaClass.simpleName) }
    }

    private fun move(player: Player, entity: LivingEntity, session: MountSession) {
        val config = configProvider()
        val speedScale =
            when (session.definition.movement) {
                MountMovement.WALKING -> config.walkingSpeedScale
                MountMovement.FLYING -> config.flyingSpeedScale
                MountMovement.SWIMMING -> config.swimmingSpeedScale
            }
        val sprint = if (session.input.sprint) config.sprintMultiplier * session.sprintMultiplier else 1.0
        val abilitySpeed = activeAbilitySpeedMultiplier(session.abilityUpgrades)
        val maximumSpeed = (session.speed * speedScale * sprint * abilitySpeed).coerceAtMost(config.maximumSpeedBlocksPerTick)
        if (session.definition.movement == MountMovement.WALKING && entity is Horse) {
            configureNativeHorseMotion(
                horse = entity,
                maximumSpeedBlocksPerTick = maximumSpeed,
                jumpVelocity = walkingJumpVelocity(config.jumpVelocity, session.definition.abilities),
                stepHeight = session.walkingStepHeight,
            )
            entity.fallDistance = 0.0f
            return
        }
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
                        (config.acceleration * session.handlingMultiplier).coerceAtMost(1.0),
                        (config.deceleration * session.handlingMultiplier).coerceAtMost(1.0),
                    )
                val vertical =
                    if (session.input.jump && entity.isOnGround) {
                        walkingJumpVelocity(config.jumpVelocity, session.definition.abilities)
                    }
                    else current.y
                MotionVector(horizontal.x, vertical, horizontal.z)
            } else {
                MountMotion.smooth(
                    current,
                    target,
                    (config.acceleration * session.handlingMultiplier).coerceAtMost(1.0),
                    (config.deceleration * session.handlingMultiplier).coerceAtMost(1.0),
                )
            }

        entity.velocity = velocity.toBukkit()
        entity.fallDistance = 0.0f
        val targetYaw = MountMotion.facingYaw(target, entity.yaw)
        entity.setRotation(MountMotion.smoothYaw(entity.yaw, targetYaw, config.turnSmoothing), 0.0f)
    }

    private fun emitTrail(entity: LivingEntity, session: MountSession) {
        val trail = session.skin?.trail ?: return
        if (session.ticks % trail.intervalTicks != 0L) return
        val particle = runCatching { Particle.valueOf(trail.particle) }.getOrNull() ?: return
        if (particle.dataType != Void::class.java && particle.dataType != java.lang.Void::class.java) return
        entity.world.spawnParticle(particle, entity.location, trail.count, 0.12, 0.12, 0.12, 0.0)
    }

    private fun refreshAbilityEffects(player: Player, abilities: Collection<MountAbilityUpgradeDefinition>) {
        abilities.forEach { ability ->
            val effectType =
                when (ability.effect) {
                    MountAbilityEffect.WATER_BREATHING -> PotionEffectType.WATER_BREATHING
                    MountAbilityEffect.NIGHT_VISION -> PotionEffectType.NIGHT_VISION
                    MountAbilityEffect.FIRE_RESISTANCE -> PotionEffectType.FIRE_RESISTANCE
                    MountAbilityEffect.DOLPHINS_GRACE -> PotionEffectType.DOLPHINS_GRACE
                }
            player.addPotionEffect(
                PotionEffect(effectType, ABILITY_EFFECT_DURATION_TICKS, 0, false, false, true),
            )
        }
    }

    private fun configureEntity(
        entity: LivingEntity,
        definition: MountDefinition,
        glow: Boolean,
        player: Player,
        skin: MountSkinDefinition?,
        walkingStepHeight: Double,
    ) {
        entity.customName(Component.text(player.name, NamedTextColor.GRAY))
        entity.isCustomNameVisible = false
        entity.isPersistent = false
        entity.isGlowing = glow
        configureMountDurability(entity)
        entity.setGravity(definition.movement == MountMovement.WALKING)
        if (definition.movement == MountMovement.WALKING) {
            configureWalkingStepHeight(entity, walkingStepHeight)
        }
        (entity as? Mob)?.let(::configureMountMob)
        (entity as? Horse)?.let { configureNativeHorse(it, player) }
        MountAppearanceApplicator.apply(entity, skin?.appearance ?: definition.appearance)
        tagEntity(entity, definition, player)
    }

    private fun tagEntity(
        entity: LivingEntity,
        definition: MountDefinition,
        player: Player,
    ) {
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

    private val MountInputState.hasMovementIntent: Boolean
        get() = forward || backward || left || right || jump || sneak

    private fun Vector.toMotion() = MotionVector(x, y, z)
    private fun MotionVector.toBukkit() = Vector(x, y, z)

    companion object {
        private const val ABILITY_REFRESH_TICKS = 20L
        private const val ABILITY_EFFECT_DURATION_TICKS = 280
    }
}

internal fun configureMountMob(mob: Mob) {
    mob.setAware(false)
    mob.canPickupItems = false
    mob.removeWhenFarAway = false
    maintainMountMobState(mob)
}

internal fun configureWalkingStepHeight(entity: LivingEntity, stepHeight: Double) {
    entity.getAttribute(Attribute.STEP_HEIGHT)?.baseValue = stepHeight
}

internal fun configureNativeHorse(horse: Horse, player: Player) {
    horse.setAware(true)
    horse.isTamed = true
    horse.owner = player
}

internal fun configureNativeHorseMotion(
    horse: Horse,
    maximumSpeedBlocksPerTick: Double,
    jumpVelocity: Double,
    stepHeight: Double,
) {
    horse.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = nativeHorseMovementAttribute(maximumSpeedBlocksPerTick)
    horse.jumpStrength = jumpVelocity
    configureWalkingStepHeight(horse, stepHeight)
}

internal fun nativeHorseMovementAttribute(maximumSpeedBlocksPerTick: Double): Double =
    (maximumSpeedBlocksPerTick / HORSE_ATTRIBUTE_BLOCKS_PER_TICK).coerceIn(0.01, 1.0)

internal fun maintainMountMobState(mob: Mob) {
    (mob as? Bat)?.setAwake(true)
}

internal enum class MountDamageTarget {
    MOUNT,
    RIDER,
}

internal fun configureMountDurability(entity: LivingEntity) {
    entity.isInvulnerable = true
}

internal fun shouldCancelMountDamage(
    target: MountDamageTarget,
    cause: EntityDamageEvent.DamageCause,
): Boolean = target == MountDamageTarget.MOUNT || cause == EntityDamageEvent.DamageCause.SUFFOCATION

internal fun shouldCancelUnauthorizedDismount(allowDismount: Boolean, cancellable: Boolean): Boolean =
    !allowDismount && cancellable

internal fun shouldKnockRiderOff(finalDamage: Double, threshold: Double): Boolean =
    finalDamage.isFinite() && threshold.isFinite() && threshold > 0.0 && finalDamage >= threshold

internal fun isAquaticEnvironment(inWaterOrBubbleColumn: Boolean, blockIsLiquid: Boolean): Boolean =
    inWaterOrBubbleColumn || blockIsLiquid

internal fun nextRiderMountHidden(
    movement: MountMovement,
    currentlyHidden: Boolean,
    pitch: Float,
    hideAtPitch: Float,
    showAtPitch: Float,
): Boolean {
    if (movement != MountMovement.FLYING || !pitch.isFinite()) return false
    return if (currentlyHidden) pitch > showAtPitch else pitch >= hideAtPitch
}

internal fun airborneMiningCompensationAmount(): Double = 4.0

internal fun setAirborneMiningCompensation(
    player: Player,
    modifier: AttributeModifier,
    enabled: Boolean,
) {
    player.getAttribute(Attribute.BLOCK_BREAK_SPEED)?.let { attribute ->
        attribute.removeModifier(modifier.key)
        if (enabled) attribute.addTransientModifier(modifier)
    }
}

internal fun shouldAllowCancelledMountSpawn(
    cancelled: Boolean,
    reason: CreatureSpawnEvent.SpawnReason,
    owner: String?,
    mountId: String?,
    spawnToken: String?,
    expectedMountIds: Set<String>,
    pendingSpawnTokens: Set<UUID>,
): Boolean =
    cancelled &&
        reason == CreatureSpawnEvent.SpawnReason.CUSTOM &&
        mountId in expectedMountIds &&
        owner != null &&
        runCatching { UUID.fromString(owner) }.isSuccess &&
        spawnToken != null &&
        runCatching { UUID.fromString(spawnToken) }.getOrNull() in pendingSpawnTokens

private const val HORSE_ATTRIBUTE_BLOCKS_PER_TICK = 2.1
