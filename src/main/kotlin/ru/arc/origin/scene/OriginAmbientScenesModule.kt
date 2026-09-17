package ru.arc.origin.scene

import com.denizenscript.denizen.objects.NPCTag
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Lidded
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.npc.CitizensNpcRouteController
import ru.arc.npc.NpcRouteEvent
import ru.arc.npc.NpcRouteObstacleSource
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.SoundUtils
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Kotlin owner of Origin's non-interactive forge and mount-yard choreography.
 *
 * Service/shop clicks remain owned by their dedicated handlers. A click merely
 * interrupts this ambient lease and returns the actor home before the service
 * handler takes over.
 */
object OriginAmbientScenesModule : PluginModule, Listener {
    override val name = "OriginAmbientScenes"
    override val priority = 27

    private var service: OriginSceneService? = null

    override fun init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            warn("ORIGIN_SCENE phase=DISABLED reason=citizens-unavailable")
            return
        }
        val current = OriginSceneService(OriginScenePlan.load(ARC.instance.dataPath))
        service = current
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        current.start()
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        service?.close()
        service = null
    }

    fun cycleKeys(): List<OriginSceneCycleKey> = service?.cycleKeys().orEmpty()

    fun startCycle(sceneId: String, cycleId: String): OriginSceneStartResult =
        service?.startManual(sceneId, cycleId) ?: OriginSceneStartResult.Unavailable("scene-engine-not-ready")

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onNpcClick(event: NPCRightClickEvent) {
        service?.interruptActor(event.npc.id, "player-click")
    }
}

data class OriginSceneCycleKey(val sceneId: String, val cycleId: String)

sealed interface OriginSceneStartResult {
    data class Started(val key: OriginSceneCycleKey) : OriginSceneStartResult
    data class Unknown(val sceneId: String, val cycleId: String) : OriginSceneStartResult
    data class Busy(val key: OriginSceneCycleKey, val reason: String) : OriginSceneStartResult
    data class Unavailable(val reason: String) : OriginSceneStartResult
}

private data class ActiveOriginSceneCycle(
    val lease: OriginSceneLease,
    val scene: OriginSceneDefinition,
    val cycle: OriginSceneCycle,
    val previousHands: MutableMap<Int, ItemStack?> = mutableMapOf(),
    val mountedPairs: MutableSet<Pair<Int, Int>> = mutableSetOf(),
    val openedContainers: MutableSet<Location> = mutableSetOf(),
    val manual: Boolean = false,
    var returning: Boolean = false,
)

private class OriginSceneService(
    private val plan: OriginScenePlan,
) : AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val coordinator = OriginSceneCoordinator()
    private val routeController = CitizensNpcRouteController(::logRouteEvent, NpcRouteObstacleSource(::originFurnitureObstacleCells))
    private val active = mutableMapOf<UUID, ActiveOriginSceneCycle>()
    private val speech = mutableMapOf<Int, TextDisplay>()
    private var closed = false

    fun start() {
        val now = System.currentTimeMillis()
        plan.scenes.forEach { scene ->
            scene.cycles.forEach { cycle ->
                coordinator.delay(scene.id, cycle.id, now + cycle.initialDelayMillis)
            }
        }
        tasks.runTimer(plan.tickTicks, plan.tickTicks, ::tick)
        info(
            "ORIGIN_SCENE phase=READY world={} scenes={} cycles={} owner=arc-kotlin",
            plan.world,
            plan.scenes.joinToString(",") { it.id },
            plan.scenes.sumOf { it.cycles.size },
        )
    }

    fun cycleKeys(): List<OriginSceneCycleKey> = plan.scenes.flatMap { scene ->
        scene.cycles.map { cycle -> OriginSceneCycleKey(scene.id, cycle.id) }
    }

    fun startManual(sceneId: String, cycleId: String): OriginSceneStartResult {
        if (closed) return OriginSceneStartResult.Unavailable("scene-engine-closed")
        val scene = plan.scenes.firstOrNull { it.id == sceneId }
            ?: return OriginSceneStartResult.Unknown(sceneId, cycleId)
        val cycle = scene.cycles.firstOrNull { it.id == cycleId }
            ?: return OriginSceneStartResult.Unknown(sceneId, cycleId)
        val key = OriginSceneCycleKey(sceneId, cycleId)

        active.values.filter { running -> !running.manual && running.cycle.actorIds.any(cycle.actorIds::contains) }
            .toList()
            .forEach { interruptCycle(it, "manual-preempt") }

        val actors = cycle.actorIds.mapNotNull(::npc).takeIf { it.size == cycle.actorIds.size }
            ?: return OriginSceneStartResult.Unavailable("actor-missing")
        val blocked = actors.firstOrNull { !available(it, scene) }
        if (blocked != null) return OriginSceneStartResult.Busy(key, "actor-${blocked.id}-busy")

        val lease = coordinator.tryAcquire(scene.id, cycle.id, cycle.actorIds, System.currentTimeMillis(), ignoreDue = true)
            ?: return OriginSceneStartResult.Busy(key, "actor-lease-busy")
        launch(scene, cycle, lease, manual = true)
        return OriginSceneStartResult.Started(key)
    }

    private fun tick() {
        if (closed) return
        if (Bukkit.getWorld(plan.world) == null) return
        val now = System.currentTimeMillis()
        for (scene in plan.scenes.shuffled()) {
            if (!hasAudience(scene)) continue
            if (active.values.count { it.scene.id == scene.id } >= scene.maxConcurrentCycles) continue
            for (cycle in scene.cycles.shuffled()) {
                if (!coordinator.isDue(scene.id, cycle.id, now)) continue
                val actors = cycle.actorIds.mapNotNull(::npc).takeIf { it.size == cycle.actorIds.size }
                if (actors == null || actors.any { !available(it, scene) }) {
                    coordinator.delay(scene.id, cycle.id, now + scene.retryMillis)
                    continue
                }
                val lease = coordinator.tryAcquire(scene.id, cycle.id, cycle.actorIds, now) ?: continue
                launch(scene, cycle, lease, manual = false)
                break
            }
        }
    }

    private fun launch(scene: OriginSceneDefinition, cycle: OriginSceneCycle, lease: OriginSceneLease, manual: Boolean) {
        val running = ActiveOriginSceneCycle(lease, scene, cycle, manual = manual)
        active[lease.token] = running
        cycle.actorIds.mapNotNull(::npc).forEach { it.entity.addScoreboardTag(BUSY_TAG) }
        info(
            "ORIGIN_SCENE phase=CYCLE_STARTED scene={} cycle={} actors={} trigger={}",
            scene.id,
            cycle.id,
            cycle.actorIds.sorted().joinToString(","),
            if (manual) "manual" else "ambient",
        )
        runStep(running, 0)
    }

    private fun hasAudience(scene: OriginSceneDefinition): Boolean {
        val rangeSquared = scene.audienceRange * scene.audienceRange
        return Bukkit.getOnlinePlayers().any { player ->
            player.world.name == plan.world && player.location.distanceSquared(scene.anchor.inWorld(player.world)) <= rangeSquared
        }
    }

    private fun available(npc: NPC, scene: OriginSceneDefinition): Boolean {
        if (!npc.isSpawned || npc.entity.world.name != plan.world || routeController.isNavigating(npc)) return false
        val flags = scene.actors.getValue(npc.id).deniedDenizenFlags
        return flags.none { hasDenizenFlag(npc, it) }
    }

    private fun runStep(running: ActiveOriginSceneCycle, index: Int) {
        if (!isCurrent(running) || running.returning) return
        if (index >= running.cycle.steps.size) {
            finish(running, "complete")
            return
        }
        if (hasDeniedFlag(running)) {
            finish(running, "denizen-busy")
            return
        }

        when (val step = running.cycle.steps[index]) {
            is OriginSceneStep.Move -> move(running, index, step)
            is OriginSceneStep.Wait -> later(running, step.ticks) { runStep(running, index + 1) }
            is OriginSceneStep.LookAtAnchor -> {
                npc(step.actorId)?.faceLocation(running.scene.anchors.getValue(step.anchor).inWorld(Bukkit.getWorld(plan.world)!!))
                runStep(running, index + 1)
            }
            is OriginSceneStep.LookAtActor -> {
                val target = npc(step.targetActorId)?.takeIf(NPC::isSpawned)
                if (target == null) finish(running, "look-target-missing")
                else {
                    npc(step.actorId)?.faceLocation(target.entity.location)
                    runStep(running, index + 1)
                }
            }
            is OriginSceneStep.Equip -> {
                equip(running, step.actorId, step.material)
                runStep(running, index + 1)
            }
            is OriginSceneStep.Swing -> swing(running, index, step, 0)
            is OriginSceneStep.Sound -> {
                stepLocation(running, step.actorId, step.anchor)?.let { location ->
                    SoundUtils.getSound(step.sound)?.let { location.world.playSound(location, it, step.volume, step.pitch) }
                }
                runStep(running, index + 1)
            }
            is OriginSceneStep.Particle -> {
                stepLocation(running, step.actorId, step.anchor)?.let { location ->
                    runCatching { Particle.valueOf(step.particle) }.onSuccess { location.world.spawnParticle(it, location.clone().add(0.0, 1.0, 0.0), step.count, 0.22, 0.22, 0.22, 0.01) }
                }
                runStep(running, index + 1)
            }
            is OriginSceneStep.Speech -> {
                showSpeech(step.actorId, step.text)
                runStep(running, index + 1)
            }
            is OriginSceneStep.ContainerLid -> {
                setContainerLid(running, step)
                runStep(running, index + 1)
            }
            is OriginSceneStep.Mount -> {
                val rider = npc(step.actorId)?.takeIf(NPC::isSpawned)?.entity
                val vehicle = npc(step.vehicleActorId)?.takeIf(NPC::isSpawned)?.entity
                val mounted = rider != null && vehicle != null &&
                    (rider.vehicle == vehicle || rider.vehicle == null && vehicle.addPassenger(rider))
                if (!mounted) finish(running, "mount-failed")
                else {
                    running.mountedPairs += step.actorId to step.vehicleActorId
                    runStep(running, index + 1)
                }
            }
        }
    }

    private fun move(running: ActiveOriginSceneCycle, index: Int, step: OriginSceneStep.Move) {
        val actor = npc(step.actorId)?.takeIf(NPC::isSpawned)
        val world = Bukkit.getWorld(plan.world)
        if (actor == null || world == null) {
            finish(running, "move-actor-missing")
            return
        }
        val destination = running.scene.anchors.getValue(step.anchor).inWorld(world)
        if (!routeController.navigate(actor, destination, running.scene.routeProfiles.getValue(step.routeProfile))) {
            finish(running, "route-unavailable")
            return
        }
        awaitRoute(running, actor, destination, step.timeoutTicks) { success ->
            if (success) {
                if (running.scene.anchors.getValue(step.anchor).explicitPose) {
                    actor.entity.setRotation(destination.yaw, destination.pitch)
                }
                runStep(running, index + 1)
            } else finish(running, "route-timeout")
        }
    }

    private fun awaitRoute(
        running: ActiveOriginSceneCycle,
        actor: NPC,
        destination: org.bukkit.Location,
        remainingTicks: Long,
        done: (Boolean) -> Unit,
    ) {
        later(running, 2L) {
            if (hasDeniedFlag(running)) done(false)
            else if (!actor.isSpawned) done(false)
            else if (!routeController.isNavigating(actor)) {
                done(routeController.consumeOutcome(actor)?.successful == true)
            }
            else if (remainingTicks <= 2L) done(false)
            else awaitRoute(running, actor, destination, remainingTicks - 2L, done)
        }
    }

    private fun swing(running: ActiveOriginSceneCycle, index: Int, step: OriginSceneStep.Swing, repetition: Int) {
        if (!isCurrent(running)) return
        (npc(step.actorId)?.takeIf(NPC::isSpawned)?.entity as? LivingEntity)?.swingMainHand()
        if (repetition + 1 >= step.repetitions) runStep(running, index + 1)
        else later(running, step.periodTicks) { swing(running, index, step, repetition + 1) }
    }

    private fun equip(running: ActiveOriginSceneCycle, actorId: Int, materialName: String) {
        val actor = npc(actorId) ?: return
        val equipment = actor.getOrAddTrait(CitizensEquipment::class.java)
        running.previousHands.getOrPut(actorId) {
            equipment.get(CitizensEquipment.EquipmentSlot.HAND)?.takeUnless { it.type.isAir }?.clone()
        }
        val material = Material.matchMaterial(materialName) ?: return
        equipment.set(CitizensEquipment.EquipmentSlot.HAND, ItemStack(material))
    }

    private fun setContainerLid(running: ActiveOriginSceneCycle, step: OriginSceneStep.ContainerLid) {
        val world = Bukkit.getWorld(plan.world) ?: return
        val location = running.scene.anchors.getValue(step.anchor).inWorld(world).block.location
        val lidded = location.block.state as? Lidded ?: return
        if (step.open) {
            lidded.open()
            running.openedContainers += location
        } else {
            lidded.close()
            running.openedContainers.remove(location)
        }
    }

    private fun finish(running: ActiveOriginSceneCycle, reason: String) {
        if (!isCurrent(running) || running.returning) return
        running.returning = true
        val keepMounted = reason == "complete"
        running.cycle.actorIds.mapNotNull(::npc).forEach { actor ->
            routeController.stop(actor)
            if (!keepMounted) {
                actor.entity.leaveVehicle()
                actor.entity.eject()
            }
        }
        restoreHands(running)
        closeContainers(running)
        info("ORIGIN_SCENE phase=CYCLE_RETURNING scene={} cycle={} reason={}", running.scene.id, running.cycle.id, reason)
        returnHome(running, reason, keepMounted)
    }

    private fun returnHome(running: ActiveOriginSceneCycle, reason: String, keepMounted: Boolean) {
        val world = Bukkit.getWorld(plan.world)
        if (world == null) {
            release(running, reason)
            return
        }
        var pending = 0
        originSceneReturnActorIds(running.cycle.actorIds, running.mountedPairs, keepMounted).forEach { actorId ->
            val actor = npc(actorId)?.takeIf(NPC::isSpawned) ?: return@forEach
            val home = running.scene.actors.getValue(actorId).home.inWorld(world)
            val profile = running.scene.routeProfiles.values.firstOrNull { it.floorY == home.blockY && NpcBounds.contains(it, home) }
            if (profile == null || actor.entity.location.distanceSquared(home) < 0.8) {
                actor.entity.teleport(home)
                return@forEach
            }
            if (routeController.navigate(actor, home, profile)) pending++ else actor.entity.teleport(home)
        }
        if (pending == 0) {
            release(running, reason)
            return
        }
        awaitReturns(running, reason, 400L, keepMounted)
    }

    private fun awaitReturns(running: ActiveOriginSceneCycle, reason: String, remainingTicks: Long, keepMounted: Boolean) {
        later(running, 4L, allowReturning = true) {
            val moving = running.cycle.actorIds.mapNotNull(::npc).filter(routeController::isNavigating)
            if (moving.isEmpty()) {
                restoreHomePoses(running, keepMounted)
                release(running, reason)
            }
            else if (remainingTicks <= 4L) {
                moving.forEach(routeController::stop)
                restoreHomePoses(running, keepMounted)
                release(running, "$reason-return-timeout")
            } else awaitReturns(running, reason, remainingTicks - 4L, keepMounted)
        }
    }

    private fun restoreHands(running: ActiveOriginSceneCycle) {
        running.previousHands.forEach { (actorId, previous) ->
            npc(actorId)?.getOrAddTrait(CitizensEquipment::class.java)?.set(
                CitizensEquipment.EquipmentSlot.HAND,
                previous ?: ItemStack(Material.AIR),
            )
        }
        running.previousHands.clear()
    }

    private fun closeContainers(running: ActiveOriginSceneCycle) {
        running.openedContainers.forEach { location ->
            (location.block.state as? Lidded)?.close()
        }
        running.openedContainers.clear()
    }

    private fun restoreHomePoses(running: ActiveOriginSceneCycle, keepMounted: Boolean) {
        val world = Bukkit.getWorld(plan.world) ?: return
        originSceneReturnActorIds(running.cycle.actorIds, running.mountedPairs, keepMounted).forEach { actorId ->
            val actor = npc(actorId)?.takeIf(NPC::isSpawned) ?: return@forEach
            val home = running.scene.actors.getValue(actorId).home.inWorld(world)
            actor.entity.teleport(home)
        }
    }

    private fun release(running: ActiveOriginSceneCycle, reason: String) {
        if (active.remove(running.lease.token) !== running) return
        running.cycle.actorIds.mapNotNull(::npc).filter(NPC::isSpawned).forEach { it.entity.removeScoreboardTag(BUSY_TAG) }
        val cooldown = running.cycle.cooldownMillis.random()
        coordinator.release(running.lease, System.currentTimeMillis(), cooldown)
        info(
            "ORIGIN_SCENE phase=CYCLE_FINISHED scene={} cycle={} reason={} cooldown_seconds={}",
            running.scene.id,
            running.cycle.id,
            reason,
            (cooldown / 1_000.0).roundToInt(),
        )
    }

    fun interruptActor(actorId: Int, reason: String) {
        val running = active.values.firstOrNull { actorId in it.cycle.actorIds } ?: return
        interruptCycle(running, reason)
    }

    private fun interruptCycle(running: ActiveOriginSceneCycle, reason: String) {
        if (!isCurrent(running)) return
        running.returning = true
        val world = Bukkit.getWorld(plan.world)
        running.cycle.actorIds.mapNotNull(::npc).forEach { actor ->
            routeController.stop(actor)
            actor.entity.leaveVehicle()
            actor.entity.eject()
            world?.let { actor.entity.teleport(running.scene.actors.getValue(actor.id).home.inWorld(it)) }
        }
        restoreHands(running)
        closeContainers(running)
        release(running, reason)
    }

    private fun showSpeech(actorId: Int, text: String) {
        val actor = npc(actorId)?.takeIf(NPC::isSpawned) ?: return
        speech.remove(actorId)?.let { if (it.isValid) it.remove() }
        val display = actor.entity.world.spawn(actor.entity.location.clone().add(0.0, plan.speechHeight, 0.0), TextDisplay::class.java).apply {
            this.text(Component.text(text, NamedTextColor.GOLD))
            billboard = Display.Billboard.CENTER
            isShadowed = true
            backgroundColor = Color.fromARGB(190, 18, 13, 9)
            brightness = Display.Brightness(15, 15)
            lineWidth = 190
            viewRange = plan.speechViewRange
            displayWidth = 4.5f
            displayHeight = 2.0f
            teleportDuration = 2
            transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(plan.speechScale), AxisAngle4f())
            isPersistent = false
            addScoreboardTag(SPEECH_TAG)
        }
        speech[actorId] = display
        followSpeech(actorId, display, plan.speechDurationTicks)
        tasks.runLater(plan.speechDurationTicks) {
            if (speech.remove(actorId, display) && display.isValid) display.remove()
        }
    }

    private fun followSpeech(actorId: Int, display: TextDisplay, remainingTicks: Long) {
        if (speech[actorId] !== display || !display.isValid || remainingTicks <= 0L) return
        val actor = npc(actorId)?.takeIf(NPC::isSpawned)
        if (actor == null) {
            if (speech.remove(actorId, display)) display.remove()
            return
        }
        display.teleport(actor.entity.location.clone().add(0.0, plan.speechHeight, 0.0))
        tasks.runLater(2L) { followSpeech(actorId, display, remainingTicks - 2L) }
    }

    private fun stepLocation(running: ActiveOriginSceneCycle, actorId: Int?, anchor: String?) =
        when {
            anchor != null -> Bukkit.getWorld(plan.world)?.let { running.scene.anchors.getValue(anchor).inWorld(it) }
            actorId != null -> npc(actorId)?.takeIf(NPC::isSpawned)?.entity?.location
            else -> null
        }

    private fun later(
        running: ActiveOriginSceneCycle,
        ticks: Long,
        allowReturning: Boolean = false,
        block: () -> Unit,
    ) {
        tasks.runLater(ticks) {
            if (isCurrent(running) && (allowReturning || !running.returning)) block()
        }
    }

    private fun isCurrent(running: ActiveOriginSceneCycle): Boolean = active[running.lease.token] === running

    private fun hasDeniedFlag(running: ActiveOriginSceneCycle): Boolean =
        running.cycle.actorIds.any { actorId ->
            val actor = npc(actorId) ?: return@any true
            running.scene.actors.getValue(actorId).deniedDenizenFlags.any { hasDenizenFlag(actor, it) }
        }

    private fun npc(id: Int): NPC? = runCatching { CitizensAPI.getNPCRegistry().getById(id) }.getOrNull()

    private fun hasDenizenFlag(npc: NPC, flag: String): Boolean {
        if (flag.isBlank() || !Bukkit.getPluginManager().isPluginEnabled("Denizen")) return false
        return runCatching { NPCTag(npc).hasFlag(flag) }.getOrDefault(false)
    }

    private fun logRouteEvent(event: NpcRouteEvent) {
        info(
            "ORIGIN_SCENE phase=ROUTE_{} profile={} npc={} cells={} actual={},{},{} target={},{},{} reason={}",
            event.phase,
            event.profileId,
            event.npcId,
            event.cells,
            event.actual.x,
            event.actual.y,
            event.actual.z,
            event.target.x,
            event.target.y,
            event.target.z,
            event.reason ?: "none",
        )
    }

    override fun close() {
        closed = true
        active.values.toList().forEach { running ->
            running.cycle.actorIds.mapNotNull(::npc).forEach { actor ->
                routeController.stop(actor)
                actor.entity.leaveVehicle()
                actor.entity.eject()
                actor.entity.removeScoreboardTag(BUSY_TAG)
                Bukkit.getWorld(plan.world)?.let { actor.entity.teleport(running.scene.actors.getValue(actor.id).home.inWorld(it)) }
            }
            restoreHands(running)
        }
        active.clear()
        coordinator.clear()
        speech.values.forEach { if (it.isValid) it.remove() }
        speech.clear()
        routeController.close()
        tasks.close()
        info("ORIGIN_SCENE phase=STOPPED")
    }

    private object NpcBounds {
        fun contains(profile: ru.arc.npc.NpcRouteProfile, location: org.bukkit.Location): Boolean =
            location.blockX in profile.bounds.minX..profile.bounds.maxX && location.blockZ in profile.bounds.minZ..profile.bounds.maxZ
    }

    private companion object {
        const val SPEECH_TAG = "arc_origin_scene_speech"
        const val BUSY_TAG = "arc_origin_scene_busy"
    }
}
