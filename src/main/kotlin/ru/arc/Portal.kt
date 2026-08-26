package ru.arc

import com.destroystokyo.paper.ParticleBuilder
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType.BLINDNESS
import org.bukkit.util.BoundingBox
import ru.arc.PortalData.ActionType.COMMAND
import ru.arc.PortalData.ActionType.HUSK
import ru.arc.PortalData.ActionType.TELEPORT
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.sync
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.util.CooldownManager
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.ParticleManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import ru.arc.config.material
import ru.arc.config.materialSet
import ru.arc.config.particle
import ru.arc.config.sound

internal enum class PortalAccess {
    ALLOWED,
    VISITOR_DENIED,
    OWNER_DENIED,
}

internal fun evaluatePortalAccess(
    isOwner: Boolean,
    visitorAllowsForeignPortals: Boolean,
    ownerAllowsVisitors: Boolean,
): PortalAccess =
    when {
        isOwner -> PortalAccess.ALLOWED
        !visitorAllowsForeignPortals -> PortalAccess.VISITOR_DENIED
        !ownerAllowsVisitors -> PortalAccess.OWNER_DENIED
        else -> PortalAccess.ALLOWED
    }

internal fun hasPortalClearance(
    blockUp: Block,
    blockUp2: Block,
): Boolean = blockUp.isPassable && blockUp2.isPassable

internal fun touchesPortalEntrance(
    playerLocation: Location,
    playerBounds: BoundingBox,
    portalBase: Location,
): Boolean {
    val portalX = portalBase.blockX.toDouble()
    val portalZ = portalBase.blockZ.toDouble()
    val touchesHorizontalBounds =
        playerBounds.maxX >= portalX &&
            playerBounds.minX <= portalX + 1.0 &&
            playerBounds.maxZ >= portalZ &&
            playerBounds.minZ <= portalZ + 1.0
    val feetOffset = playerLocation.y - portalBase.y

    return touchesHorizontalBounds && feetOffset > -1.0 && feetOffset <= 1.5
}

class Portal(uuid: UUID, private val portalData: PortalData) {

    private val borderLocations = ArrayList<Location>()
    private val reducedBorderLocations = ArrayList<Location>()
    private val seenBlocks = HashSet<Block>()
    private val blockChangePlayers = ConcurrentSkipListSet(Comparator.comparing(UUID::toString))

    private val phase = AtomicInteger()
    private val success = AtomicBoolean()
    private val removed = AtomicBoolean()

    private var centerBlock: Block? = null
    private val player: Player?
    private var task: ScheduledTask? = null
    private var originGate: PortalOriginGateController? = null
    private var originGateSettings: PortalOriginGateSettings? = null

    companion object {
        private val occupiedBlocks = ConcurrentHashMap.newKeySet<Block>()
        private val portals = ConcurrentHashMap<UUID, Portal>()
        private val config = ConfigManager.of(ARC.instance.dataPath, "misc.yml")

        @JvmStatic
        fun isOccupied(block: Block): Boolean = occupiedBlocks.contains(block)

        @JvmStatic
        fun removeAll() {
            portals.values.toList().forEach(Portal::removePortal)
            portals.clear()
            occupiedBlocks.clear()
        }
    }

    init {
        player = Bukkit.getPlayer(uuid)
        if (player == null) {
            error("Player is null")
        } else {
            centerBlock = findPortalLocation()

            if (centerBlock == null) {
                executeAction(player)
                info("Could not find suitable location for portal near {}", player.name)
            } else {
                portals[player.uniqueId]?.removePortal()
                reservePortal(centerBlock!!)
                createOriginGate(player, centerBlock!!)
                task = createTask()
                portals[player.uniqueId] = this
                player.sendMessage(config.component("portal.message", "<green>Портал создан!"))
            }
        }
    }

    private fun isSuitable(block: Block?): Boolean {
        if (block == null || seenBlocks.contains(block)) return false
        val blockUp = block.getRelative(0, 1, 0)
        val blockUp2 = blockUp.getRelative(0, 1, 0)
        seenBlocks.add(block)

        if (occupiedBlocks.contains(block) || occupiedBlocks.contains(blockUp) || occupiedBlocks.contains(blockUp2)) return false

        return (block.isSolid || block.type == Material.WATER) && hasPortalClearance(blockUp, blockUp2)
    }

    private fun createTask(): ScheduledTask {
        val cb = centerBlock!!
        return repeating(1.ticks, delay = 1.ticks) {
            if (phase.get() > 400 || player?.isOnline != true) {
                removePortal()
                return@repeating
            }
            if (success.get()) {
                if (originGate?.tickClosing() == true) {
                    originGateSettings?.let { displayOriginGateParticles(nearbyPlayers(cb), it) }
                    phase.incrementAndGet()
                    return@repeating
                }
                removePortal()
                return@repeating
            }
            val nearbyPlayers = nearbyPlayers(cb)
            if (phase.get() >= 58 && config.bool("portal.blindness", true)) {
                val radius = config.real("portal.blindness-radius", 2.0)
                val duration = config.integer("portal.blindness-duration", 40)
                val closePlayers = nearbyPlayers.filter { p ->
                    p.location.distance(cb.location.clone().add(0.5, 0.0, 0.5)) < radius
                }
                val potionEffect = PotionEffect(BLINDNESS, duration, 0, false, false, false)
                for (p in closePlayers) {
                    if (!p.hasPotionEffect(BLINDNESS)) p.addPotionEffect(potionEffect)
                }
            }
            if (phase.get() == 58 && originGate?.isActive != true) {
                cb.world.playSound(cb.location, org.bukkit.Sound.BLOCK_END_PORTAL_SPAWN, 1f, 1f)
            }

            val originGateActive = originGate?.tickOpening(phase.get()) == true
            if (originGateActive) {
                originGateSettings?.let { displayOriginGateParticles(nearbyPlayers, it) }
            } else {
                addLocations()
                displayParticles(nearbyPlayers)
                if (phase.get() >= 58 && (phase.get() == 58 || phase.get() % 10 == 0)) {
                    placeBlocksPackets(nearbyPlayers)
                }
            }
            val entryTick = originGateSettings?.entryTick?.takeIf { originGate?.isActive == true } ?: 61
            if (phase.get() >= entryTick) {
                val enteredPlayer = getEnteredPlayer(nearbyPlayers)
                if (enteredPlayer != null && !success.getAndSet(true)) {
                    originGate?.beginClosing()
                    executeAction(enteredPlayer)
                }
            }
            phase.incrementAndGet()
        }
    }

    private fun createOriginGate(
        owner: Player,
        base: Block,
    ) {
        val settings = PortalOriginGateSettings.load(config) ?: return
        if (!shouldUseOriginGate(enabled = true, hasPermission = owner.hasPermission(ORIGIN_GATE_PERMISSION))) return
        originGateSettings = settings
        originGate =
            PortalOriginGateController(settings) {
                BukkitPortalOriginGate.spawn(base, settings, owner.location.clone())
            }
    }

    private fun nearbyPlayers(base: Block): HashSet<Player> {
        val particleDistance = config.real("portal.particle-distance", 50.0)
        val nearby = HashSet<Player>()
        for (candidate in base.world.players) {
            if (candidate.location.distance(base.location) < particleDistance) nearby.add(candidate)
        }
        return nearby
    }

    private fun getEnteredPlayer(nearby: Collection<Player>): Player? {
        val cb = centerBlock ?: return null
        val owner = player ?: return null
        for (p in nearby) {
            if (!inPortal(p, cb.location)) continue
            when (
                evaluatePortalAccess(
                    isOwner = p == owner,
                    visitorAllowsForeignPortals = p.hasPermission("arc.portal.tp-by-other"),
                    ownerAllowsVisitors = owner.hasPermission("arc.portal.tp-other"),
                )
            ) {
                PortalAccess.ALLOWED -> return p
                PortalAccess.VISITOR_DENIED -> {
                    CooldownManager.onCooldown(p.uniqueId, "portal_tp_by_other_message", 60) {
                        p.sendMessage(config.component("portal.tp-by-other-disabled.message", "<red>Вы не можете телепортироваться через порталы других игроков!"))
                    }
                }
                PortalAccess.OWNER_DENIED -> {
                    CooldownManager.onCooldown(p.uniqueId, "portal_tp_other_message", 60) {
                        p.sendMessage(config.component("portal.tp-other-disabled.message", "<red>Этот игрок не разрешил другим игрокам телепортироваться через его порталы!"))
                    }
                }
            }
        }
        return null
    }

    private fun executeAction(player: Player) {
        when (portalData.actionType) {
            COMMAND -> {
                sync {
                    player.world.playSound(player.location, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
                    player.performCommand(portalData.command ?: return@sync)
                }
            }
            HUSK -> {
                if (HookRegistry.huskHomesHook == null) {
                    error("HuskHomes hook is not active!")
                    return
                }
                sync {
                    player.world.playSound(player.location, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
                    HookRegistry.huskHomesHook!!.teleport(portalData.huskTeleport ?: return@sync, player)
                }
            }
            TELEPORT -> {
                sync {
                    player.world.playSound(player.location, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
                    player.teleport(portalData.location!!)
                }
            }
            else -> {}
        }
    }

    @Suppress("DEPRECATION")
    private fun placeBlocksPackets(players: Set<Player>) {
        if (players.isEmpty()) return
        val cb = centerBlock ?: return
        val blockData: BlockData = Bukkit.createBlockData(Material.END_GATEWAY)
        val map = mapOf(
            cb.getRelative(0, 1, 0).location to blockData,
            cb.getRelative(0, 2, 0).location to blockData,
        )
        for (p in players) {
            if (p.world != cb.world) continue
            p.sendMultiBlockChange(map)
            blockChangePlayers.add(p.uniqueId)
        }
    }

    @Suppress("DEPRECATION")
    private fun clearBlockPackets() {
        val cb = centerBlock ?: return
        val map = mapOf(
            cb.getRelative(0, 1, 0).let { it.location to it.blockData },
            cb.getRelative(0, 2, 0).let { it.location to it.blockData },
        )
        for (uuid in blockChangePlayers) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            if (p.world != cb.world) continue
            p.sendMultiBlockChange(map)
        }
    }

    private fun addLocations() {
        val cb = centerBlock ?: return
        val x = cb.x.toDouble()
        val y = cb.y.toDouble() + 1
        val z = cb.z.toDouble()
        val world = cb.world
        if (phase.get() % 10 == 0 && phase.get() <= 40) cb.world.playSound(cb.location, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 30f, 1f)

        when {
            phase.get() <= 10 -> {
                val loc1 = Location(world, x + phase.get() / 10.0, y, z)
                val loc2 = Location(world, x, y + phase.get() / 10.0, z)
                val loc3 = Location(world, x, y, z + phase.get() / 10.0)
                borderLocations.addAll(listOf(loc1, loc2, loc3))
                if (phase.get() % 2 == 0 || phase.get() % 10 == 0) reducedBorderLocations.addAll(listOf(loc1, loc2, loc3))
            }
            phase.get() <= 20 -> {
                val p = phase.get()
                val loc1 = Location(world, x + 1, y + (p - 10) / 10.0, z)
                val loc2 = Location(world, x, y + p / 10.0, z)
                val loc3 = Location(world, x, y + (p - 10) / 10.0, z + 1)
                val loc4 = Location(world, x + 1, y, z + (p - 10) / 10.0)
                val loc5 = Location(world, x + (p - 10) / 10.0, y, z + 1)
                borderLocations.addAll(listOf(loc1, loc2, loc3, loc4, loc5))
                if (p % 2 == 0 || p % 10 == 0) reducedBorderLocations.addAll(listOf(loc1, loc2, loc3, loc4, loc5))
            }
            phase.get() <= 30 -> {
                val p = phase.get()
                val loc1 = Location(world, x + 1, y + (p - 10) / 10.0, z)
                val loc2 = Location(world, x + (p - 20) / 10.0, y + 2, z)
                val loc3 = Location(world, x, y + 2, z + (p - 20) / 10.0)
                val loc4 = Location(world, x, y + (p - 10) / 10.0, z + 1)
                val loc5 = Location(world, x + 1, y + (p - 20) / 10.0, z + 1)
                borderLocations.addAll(listOf(loc1, loc2, loc3, loc4, loc5))
                if (p % 2 == 0 || p % 10 == 0) reducedBorderLocations.addAll(listOf(loc1, loc2, loc3, loc4, loc5))
            }
            phase.get() <= 40 -> {
                val p = phase.get()
                val loc1 = Location(world, x + 1, y + 2, z + (p - 30) / 10.0)
                val loc2 = Location(world, x + (p - 30) / 10.0, y + 2, z + 1)
                val loc3 = Location(world, x + 1, y + 1 + (p - 30) / 10.0, z + 1)
                borderLocations.addAll(listOf(loc1, loc2, loc3))
                if (p % 2 == 0 || p % 10 == 0) reducedBorderLocations.addAll(listOf(loc1, loc2, loc3))
            }
        }
    }

    private fun displayParticles(nearbyPlayers: Collection<Player>) {
        val cb = centerBlock ?: return
        val nearPlayers = nearbyPlayers.filter { p -> p.isOnline && p.world == cb.world }

        val fullPlayers = ArrayList<Player>()
        val reducedParticles = ArrayList<Player>()
        for (p in nearPlayers) {
            if (p.hasPermission("myhome.reduce-particles")) reducedParticles.add(p)
            else fullPlayers.add(p)
        }

        val redStart = config.integer("portal.border.color.red-start", 121)
        val greenStart = config.integer("portal.border.color.green-start", 56)
        val blueStart = config.integer("portal.border.color.blue-start", 163)
        val redEnd = config.integer("portal.border.color.red-end", 0)
        val greenEnd = config.integer("portal.border.color.green-end", 0)
        val blueEnd = config.integer("portal.border.color.blue-end", 0)
        val size = config.real("portal.border.color.size", 0.5).toFloat()
        val offset = config.real("portal.border.offset", 0.015).toFloat()
        val count = config.integer("portal.border.count", 2)
        val particle = config.particle("portal.border.particle", Particle.DUST_COLOR_TRANSITION)
        val data = Particle.DustTransition(Color.fromRGB(redStart, greenStart, blueStart), Color.fromRGB(redEnd, greenEnd, blueEnd), size)

        if (fullPlayers.isNotEmpty()) {
            for (location in ArrayList(borderLocations)) {
                ParticleBuilder(particle).count(count).location(location).receivers(fullPlayers)
                    .offset(offset.toDouble(), offset.toDouble(), offset.toDouble()).data(data).spawn()
            }
        }
        if (reducedParticles.isNotEmpty()) {
            for (location in ArrayList(reducedBorderLocations)) {
                ParticleBuilder(particle).count(count).location(location).receivers(reducedParticles)
                    .offset(offset.toDouble(), offset.toDouble(), offset.toDouble()).data(data).spawn()
            }
        }

        if (phase.get() >= 41 && (phase.get() - 41) % 10 == 0) {
            val portalParticleCount = config.integer("portal.portal-particle.count", 5)
            val portalParticle = config.particle("portal.portal-particle.particle", Particle.PORTAL)
            val portalParticleExtra = config.real("portal.portal-particle.extra", 0.2)
            val portalParticleOffset = config.real("portal.portal-particle.offset", 0.3).toFloat()
            ParticleManager.queue(ParticleBuilder(portalParticle).count(portalParticleCount)
                .location(cb.getRelative(0, 1, 0).location.add(0.5, 0.5, 0.5)).receivers(nearPlayers)
                .extra(portalParticleExtra).offset(portalParticleOffset.toDouble(), portalParticleOffset.toDouble(), portalParticleOffset.toDouble()).spawn())
            ParticleManager.queue(ParticleBuilder(portalParticle).count(portalParticleCount)
                .location(cb.getRelative(0, 2, 0).location.add(0.5, 0.5, 0.5)).receivers(nearPlayers)
                .extra(portalParticleExtra).offset(portalParticleOffset.toDouble(), portalParticleOffset.toDouble(), portalParticleOffset.toDouble()).spawn())
        }
    }

    private fun displayOriginGateParticles(
        nearbyPlayers: Collection<Player>,
        settings: PortalOriginGateSettings,
    ) {
        val cb = centerBlock ?: return
        val fullPlayers = ArrayList<Player>()
        val reducedPlayers = ArrayList<Player>()
        for (candidate in nearbyPlayers) {
            if (!candidate.isOnline || candidate.world != cb.world) continue
            if (candidate.hasPermission("myhome.reduce-particles")) reducedPlayers.add(candidate)
            else fullPlayers.add(candidate)
        }
        val center = cb.location.clone().add(0.5, settings.verticalOffset, 0.5)
        BukkitPortalOriginGate.renderSuction(center, phase.get(), settings, fullPlayers, reducedPlayers)
    }

    private fun inPortal(player: Player, location: Location): Boolean {
        return touchesPortalEntrance(player.location, player.boundingBox, location)
    }

    private fun removePortal() {
        if (!removed.compareAndSet(false, true)) return
        task?.takeUnless { it.isCancelled }?.cancel()
        originGate?.remove()
        originGate = null
        originGateSettings = null
        centerBlock?.let(::releasePortal)
        player?.let { portals.remove(it.uniqueId, this) }
        clearBlockPackets()
    }

    private fun reservePortal(base: Block) {
        occupiedBlocks.add(base)
        occupiedBlocks.add(base.getRelative(0, 1, 0))
        occupiedBlocks.add(base.getRelative(0, 2, 0))
        occupiedBlocks.add(base.getRelative(0, 3, 0))
    }

    private fun releasePortal(base: Block) {
        occupiedBlocks.remove(base)
        occupiedBlocks.remove(base.getRelative(0, 1, 0))
        occupiedBlocks.remove(base.getRelative(0, 2, 0))
        occupiedBlocks.remove(base.getRelative(0, 3, 0))
    }

    private fun findPortalLocation(): Block? {
        var targetBlock = player?.getTargetBlockExact(4)
        if (isSuitable(targetBlock)) return targetBlock

        if (targetBlock == null) {
            val loc = player?.location?.add(0.0, -0.35, 0.0) ?: return null
            targetBlock = when (Math.round(loc.yaw / 90.0).toInt()) {
                -1 -> loc.block.getRelative(2, 0, 0)
                0 -> loc.block.getRelative(0, 0, 2)
                1 -> loc.block.getRelative(-2, 0, 0)
                else -> loc.block.getRelative(0, 0, -2)
            }
        }

        var found = false
        val tb = targetBlock
        outer@ for (i in -1..1) {
            for (j in -1..1) {
                for (k in -1..1) {
                    if (i == 0 && j == 0 && k == 0) continue
                    val newBlock = tb.getRelative(i, j, k)
                    if (isSuitable(newBlock)) {
                        found = true
                        targetBlock = newBlock
                        break@outer
                    }
                }
            }
        }

        return if (!found) getCloseSuitable(player!!.location) else targetBlock
    }

    private fun getCloseSuitable(location: Location): Block? {
        val b = location.block
        val radiusInitial = config.integer("portal.radius-initial", 3)
        for (x in -radiusInitial..radiusInitial) {
            for (y in -radiusInitial..radiusInitial) {
                for (z in -radiusInitial..radiusInitial) {
                    val testBlock = b.getRelative(x, y, z)
                    if (isSuitable(testBlock)) return testBlock
                }
            }
        }

        val radiusSecondary = config.integer("portal.radius-secondary", 6)
        for (x in -radiusSecondary..radiusSecondary) {
            for (y in -radiusSecondary..radiusSecondary) {
                for (z in -radiusSecondary..radiusSecondary) {
                    if (Math.abs(x) <= radiusInitial && Math.abs(y) <= radiusInitial && Math.abs(z) <= radiusInitial) continue
                    val testBlock = b.getRelative(x, y, z)
                    if (isSuitable(testBlock)) return testBlock
                }
            }
        }
        return null
    }
}
