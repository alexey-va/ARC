package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.entity.Player
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.joml.Matrix4f
import ru.arc.ARC
import ru.arc.BukkitPortalOriginGate
import ru.arc.OriginGateOpeningCurve
import ru.arc.PortalOriginGateController
import ru.arc.PortalOriginGateSettings
import ru.arc.PortalVisualStyle
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.nio.file.Path
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class OriginPortalId(
    val key: String,
    val central: Boolean,
    val defaultWorld: String,
    val defaultStyle: PortalVisualStyle,
    val defaultCommand: String,
    val defaultLabel: String,
    val defaultX: Double,
    val defaultY: Double,
    val defaultZ: Double,
    val defaultYaw: Float,
    val defaultWidth: Double,
    val defaultHeight: Double,
    val defaultEnabled: Boolean = true,
    val destinationServer: String? = null,
    val defaultParticleRadius: Double? = null,
    val defaultParticleHeight: Double? = null,
    val defaultPulseAmplitude: Double? = null,
    val maxParticleRadius: Double = 8.0,
    val maxParticleHeight: Double = 16.0,
    val maxTransferDistance: Double = 8.0,
    val liftDisplayByConfiguredOffset: Boolean = false,
) {
    SURVIVAL(
        key = "survival",
        central = true,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = PortalVisualStyle.ORIGIN,
        defaultCommand = "arc rtp survival --only-if-first",
        defaultLabel = "Новые биомы",
        defaultX = 9.5,
        defaultY = 70.0,
        defaultZ = 0.5,
        defaultYaw = 270f,
        defaultWidth = 12.0,
        defaultHeight = 16.8,
    ),
    MINING(
        key = "mining",
        central = true,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = PortalVisualStyle.ASTRAL,
        defaultCommand = "arc rtp mining --only-if-first",
        defaultLabel = "Мир добычи",
        defaultX = 6.914214,
        defaultY = 70.0,
        defaultZ = -5.914214,
        defaultYaw = 225f,
        defaultWidth = 12.0,
        defaultHeight = 16.8,
    ),
    VANILLA(
        key = "vanilla",
        central = true,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = PortalVisualStyle.VOID,
        defaultCommand = "arc rtp vanilla --only-if-first",
        defaultLabel = "Ванильные биомы",
        defaultX = 6.914214,
        defaultY = 70.0,
        defaultZ = 6.914214,
        defaultYaw = 315f,
        defaultWidth = 12.0,
        defaultHeight = 16.8,
    ),
    GALLERY_EXIT(
        key = "gallery_exit",
        central = false,
        defaultWorld = "rc_atelier_furniture_gallery",
        defaultStyle = PortalVisualStyle.ORIGIN,
        defaultCommand = "rcfurniturereturn",
        defaultLabel = "Вернуться в Origin",
        defaultX = 36.5,
        defaultY = 67.0,
        defaultZ = 60.5,
        defaultYaw = 90f,
        defaultWidth = 4.5,
        defaultHeight = 6.3,
    ),
    SLIMEFUN(
        key = "slimefun",
        central = false,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = PortalVisualStyle.ORIGIN,
        defaultCommand = "arc originportals enter slimefun",
        defaultLabel = "Slimefun",
        // Disabled by default; the anchor is the floor-level entry point.
        defaultX = -17.5,
        defaultY = 72.0,
        defaultZ = -52.5,
        defaultYaw = 180f,
        defaultWidth = 2.8,
        defaultHeight = 2.8,
        defaultEnabled = false,
        destinationServer = "slimefun",
        defaultParticleRadius = 1.25,
        defaultParticleHeight = 3.0,
        defaultPulseAmplitude = 0.0,
        maxParticleRadius = 1.4,
        maxParticleHeight = 2.8,
        maxTransferDistance = 8.0,
        liftDisplayByConfiguredOffset = true,
    ),
    ;

    companion object {
        fun parse(raw: String?): OriginPortalId? =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) }
    }
}

internal data class OriginPortalAnchor(
    val id: OriginPortalId,
    val enabled: Boolean = true,
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val width: Double,
    val height: Double,
    val entryDepth: Double,
    val command: String,
    val label: String,
    val verticalOffset: Double,
    val labelFrontDistance: Double,
    val labelSideOffset: Double,
    val labelHeightOffset: Double,
    val labelScale: Float,
    val labelBackgroundGray: Int,
    val labelBackgroundAlpha: Int,
    val style: PortalVisualStyle,
    val particleRadius: Double = 5.5,
    val particleHeight: Double = 8.4,
    val pulseAmplitude: Float = 0.035f,
    val transferDistance: Double = 8.0,
) {
    fun center(world: org.bukkit.World): Location = Location(world, x, y, z, yaw, 0f)

    fun labelLocation(world: org.bukkit.World): Location = labelLocation(world, labelFrontDistance)

    private fun labelLocation(world: org.bukkit.World, frontDistance: Double): Location {
        val angle = yaw * PI / 180.0
        return Location(
            world,
            x + sin(angle) * frontDistance + cos(angle) * labelSideOffset,
            y + verticalOffset + labelHeightOffset,
            z - cos(angle) * frontDistance + sin(angle) * labelSideOffset,
            yaw,
            0f,
        )
    }

    fun labelLocations(world: org.bukkit.World): List<Location> {
        val front = labelLocation(world)
        if (!id.central) return listOf(front)
        // One sign in front of the portal, with a readable face on either side.
        // Separate the faces slightly so their backgrounds do not hide the text.
        return listOf(
            labelLocation(world, labelFrontDistance + 0.01).apply { yaw += 180f },
            labelLocation(world, labelFrontDistance - 0.01),
        )
    }

    /** A thin, yaw-aware interaction plane keeps neighbouring central portals independent. */
    fun contains(location: Location): Boolean {
        if (!enabled || location.world?.name != worldName) return false
        val dx = location.x - x
        val dz = location.z - z
        val angle = yaw * PI / 180.0
        val side = (dx * cos(angle)) + (dz * sin(angle))
        val depth = (-dx * sin(angle)) + (dz * cos(angle))
        return abs(side) <= width / 2.0 && abs(depth) <= entryDepth && abs(location.y - y) <= height / 2.0
    }
}

internal class OriginPortalsConfig private constructor(
    private val source: Config,
    val enabled: Boolean,
    val verticalOffset: Double,
    val entryDepth: Double,
    val pulseAmplitude: Float,
    val pulsePeriodTicks: Int,
    val particlesEnabled: Boolean,
    val particleStreams: Int,
    val reducedParticleStreams: Int,
    val particlePointsPerStream: Int,
    val reducedParticlePointsPerStream: Int,
    val particleRadius: Double,
    val particleHeight: Double,
    val particleTurns: Double,
    val particleSize: Float,
    val particleCoreCount: Int,
    val fullParticleDistance: Double,
    val reducedParticleDistance: Double,
    val itemIds: Map<PortalVisualStyle, String>,
    val anchors: List<OriginPortalAnchor>,
) {
    fun persistFeet(id: OriginPortalId, location: Location) {
        val path = "origin-portals.anchors.${id.key}"
        source.setDouble("$path.x", location.x)
        source.setDouble("$path.y", location.y)
        source.setDouble("$path.z", location.z)
        source.setDouble("$path.yaw", location.yaw.toDouble())
        source.saveStrict()
    }

    companion object {
        private const val RESOURCE = "origin-spawn.yml"
        private val DEFAULT_ITEMS =
            mapOf(
                PortalVisualStyle.ORIGIN to "origin_gate_portals:origin_portal",
                PortalVisualStyle.ASTRAL to "origin_gate_portals:astral_portal",
                PortalVisualStyle.VOID to "origin_gate_portals:void_portal",
            )

        fun load(dataPath: Path): OriginPortalsConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")
            val root = "origin-portals"
            val itemIds =
                PortalVisualStyle.entries
                    .filter { it.usesOriginGate }
                    .associateWith { style -> source.string("$root.items.${style.id}", DEFAULT_ITEMS.getValue(style)) }
            val enabled = source.bool("$root.enabled", true)
            val verticalOffset = source.real("$root.vertical-offset", 5.5).finite(5.5).coerceIn(0.5, 12.0)
            val pulseAmplitude = source.real("$root.pulse.amplitude", 0.035).toFloat().coerceIn(0.0f, 0.1f)
            val particleRadius = source.real("$root.particles.radius", 5.5).coerceIn(0.25, 8.0)
            val particleHeight = source.real("$root.particles.height", 8.4).coerceIn(0.25, 16.0)
            val anchors = OriginPortalId.entries.map { id ->
                anchor(source, root, id, verticalOffset, particleRadius, particleHeight, pulseAmplitude)
            }
            return OriginPortalsConfig(
                source = source,
                enabled = enabled,
                verticalOffset = verticalOffset,
                entryDepth = source.real("$root.entry-depth", 2.0).coerceIn(0.5, 6.0),
                pulseAmplitude = pulseAmplitude,
                pulsePeriodTicks = source.integer("$root.pulse.period-ticks", 36).coerceIn(8, 200),
                particlesEnabled = source.bool("$root.particles.enabled", true),
                particleStreams = source.integer("$root.particles.streams", 4).coerceIn(1, 8),
                reducedParticleStreams = source.integer("$root.particles.reduced-streams", 2).coerceIn(1, 8),
                particlePointsPerStream = source.integer("$root.particles.points-per-stream", 2).coerceIn(1, 4),
                reducedParticlePointsPerStream = source.integer("$root.particles.reduced-points-per-stream", 1).coerceIn(1, 4),
                particleRadius = particleRadius,
                particleHeight = particleHeight,
                particleTurns = source.real("$root.particles.turns", 1.75).coerceIn(0.25, 4.0),
                particleSize = source.real("$root.particles.size", 0.6).toFloat().coerceIn(0.1f, 2.0f),
                particleCoreCount = source.integer("$root.particles.core-count", 4).coerceIn(0, 12),
                fullParticleDistance = source.real("$root.particles.full-distance", 24.0).coerceIn(1.0, 48.0),
                reducedParticleDistance = source.real("$root.particles.reduced-distance", 40.0).coerceIn(1.0, 64.0),
                itemIds = itemIds,
                anchors = anchors,
            )
        }

        private fun anchor(
            source: Config,
            root: String,
            id: OriginPortalId,
            verticalOffset: Double,
            globalParticleRadius: Double,
            globalParticleHeight: Double,
            globalPulseAmplitude: Float,
        ): OriginPortalAnchor {
            val path = "$root.anchors.${id.key}"
            val style = PortalVisualStyle.parse(source.string("$path.style", id.defaultStyle.id)) ?: id.defaultStyle
            val width = source.real("$path.width", id.defaultWidth).finite(id.defaultWidth).coerceIn(0.1, 12.0)
            val height = source.real("$path.height", id.defaultHeight).finite(id.defaultHeight).coerceIn(0.1, 20.0)
            val particleRadius = source.real(
                "$path.particles.radius",
                id.defaultParticleRadius ?: globalParticleRadius,
            ).finite(id.defaultParticleRadius ?: globalParticleRadius).coerceIn(0.25, id.maxParticleRadius)
            val particleHeight = source.real(
                "$path.particles.height",
                id.defaultParticleHeight ?: globalParticleHeight,
            ).finite(id.defaultParticleHeight ?: globalParticleHeight).coerceIn(0.25, id.maxParticleHeight)
            val pulseAmplitude = source.real(
                "$path.pulse.amplitude",
                id.defaultPulseAmplitude ?: globalPulseAmplitude.toDouble(),
            ).finite(id.defaultPulseAmplitude ?: globalPulseAmplitude.toDouble()).toFloat().coerceIn(0.0f, 0.1f)
            return OriginPortalAnchor(
                id = id,
                enabled = source.bool("$path.enabled", id.defaultEnabled),
                worldName = source.string("$path.world", id.defaultWorld).trim().ifEmpty { id.defaultWorld },
                x = source.real("$path.x", id.defaultX).finite(id.defaultX),
                y = source.real("$path.y", id.defaultY).finite(id.defaultY),
                z = source.real("$path.z", id.defaultZ).finite(id.defaultZ),
                yaw = source.real("$path.yaw", id.defaultYaw.toDouble()).finite(id.defaultYaw.toDouble()).toFloat(),
                width = width,
                height = height,
                entryDepth = source.real("$path.entry-depth", source.real("$root.entry-depth", 2.0)).finite(2.0).coerceIn(0.5, 6.0),
                command = source.string("$path.command", id.defaultCommand).trim().ifEmpty { id.defaultCommand },
                label = source.string("$path.hologram.text", source.string("$path.label", id.defaultLabel)).trim(),
                verticalOffset = source.real("$path.vertical-offset", verticalOffset).finite(verticalOffset).coerceIn(0.5, 12.0),
                labelFrontDistance = source.real("$path.hologram.front-distance", 0.0).finite(0.0).coerceIn(-20.0, 20.0),
                labelSideOffset = source.real("$path.hologram.side-offset", 0.0).finite(0.0).coerceIn(-20.0, 20.0),
                labelHeightOffset = source.real("$path.hologram.height-offset", if (id.central) -0.5 else 0.75)
                    .finite(if (id.central) -0.5 else 0.75).coerceIn(-20.0, 20.0),
                labelScale = source.real("$path.hologram.scale", if (id.central) 6.0 else 0.9)
                    .finite(if (id.central) 6.0 else 0.9).toFloat().coerceIn(0.1f, 8.0f),
                labelBackgroundGray = source.integer("$path.hologram.background-gray", if (id.central) 48 else 0)
                    .coerceIn(0, 255),
                labelBackgroundAlpha = source.integer("$path.hologram.background-alpha", if (id.central) 180 else 0)
                    .coerceIn(0, 255),
                style = style.takeIf { it.usesOriginGate } ?: id.defaultStyle,
                particleRadius = particleRadius,
                particleHeight = particleHeight,
                pulseAmplitude = pulseAmplitude,
                transferDistance = source.real("$path.transfer-distance", id.maxTransferDistance)
                    .finite(id.maxTransferDistance).coerceIn(1.0, id.maxTransferDistance),
            )
        }

        private fun Double.finite(fallback: Double): Double = takeIf(Double::isFinite) ?: fallback
    }

    fun gateSettings(anchor: OriginPortalAnchor): PortalOriginGateSettings? =
        PortalOriginGateSettings.validated(
            defaultStyle = anchor.style.id,
            itemIds = itemIds,
            openingStartTick = 0,
            openingDurationTicks = 1,
            openingCurve = OriginGateOpeningCurve.SMOOTH.name,
            closingDurationTicks = 1,
            width = anchor.width.toFloat(),
            height = anchor.height.toFloat(),
            verticalOffset = anchor.verticalOffset,
            yawOffsetDegrees = 0f,
            viewRange = 2.0f,
            openingSoundEnabled = false,
            openingSoundDelayTicks = 0,
            openingSoundId = "minecraft:block.end_portal.spawn",
            openingSoundVolume = 1.0f,
            openingSoundPitch = 1.0f,
            suctionEnabled = particlesEnabled,
            suctionStreams = particleStreams.coerceAtMost(8),
            reducedSuctionStreams = reducedParticleStreams.coerceAtMost(particleStreams),
            suctionPointsPerStream = particlePointsPerStream.coerceAtMost(4),
            reducedSuctionPointsPerStream = reducedParticlePointsPerStream.coerceAtMost(particlePointsPerStream),
            suctionRadius = anchor.particleRadius,
            suctionHeight = anchor.particleHeight,
            suctionTurns = particleTurns,
            suctionParticleSize = particleSize,
            suctionCoreCount = particleCoreCount,
            maxHeight = 20.0f,
        )
}

private class OriginPortalVisual(
    private val anchor: OriginPortalAnchor,
    private val config: OriginPortalsConfig,
) {
    private var controller: PortalOriginGateController? = null
    private var labels: List<TextDisplay> = emptyList()
    private var spawnAttempted = false
    private var gateSpawned = false

    fun tick(tick: Int) {
        val world = Bukkit.getWorld(anchor.worldName) ?: run {
            remove()
            return
        }
        val settings = config.gateSettings(anchor) ?: run {
            remove()
            return
        }
        val chunksLoaded = originPortalVisualChunksLoaded(anchor, world)
        if (
            shouldResetOriginPortalVisual(
                spawnAttempted = spawnAttempted,
                chunksLoaded = chunksLoaded,
                gateSpawned = gateSpawned,
                gateActive = controller?.isActive == true,
            )
        ) {
            remove()
            if (!chunksLoaded) return
        }
        if (spawnAttempted && labels.any { !it.isValid }) {
            labels.forEach { if (it.isValid) it.remove() }
            labels = emptyList()
        }
        if (!spawnAttempted) {
            spawnAttempted = true
            controller =
                PortalOriginGateController(settings) {
                BukkitPortalOriginGate.spawn(originPortalDisplayCenter(anchor, world), settings, anchor.style).also {
                        gateSpawned = it != null
                    }
                }
        }
        if (labels.isEmpty()) {
            labels = anchor.labelLocations(world).map { spawnLabel(world, it) }
        }
        val active = controller?.tickOpening(settings.entryTick + tick) == true
        if (!active) return
        val pulse = 1f + anchor.pulseAmplitude * sin((tick * 2.0 * PI) / config.pulsePeriodTicks).toFloat()
        // The renderer owns the display transform; ARC only supplies a bounded idle pulse.
        controller?.updateScale(pulse)
        renderParticles(world, tick, settings)
    }

    private fun spawnLabel(world: org.bukkit.World, location: Location): TextDisplay {
        return world.spawn(location, TextDisplay::class.java) {
            it.text(
                Component.text(anchor.label, labelColor(anchor.style))
                    .decorate(TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false),
            )
            it.billboard = if (anchor.id.central) Display.Billboard.FIXED else Display.Billboard.CENTER
            it.isSeeThrough = false
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(
                anchor.labelBackgroundAlpha,
                anchor.labelBackgroundGray,
                anchor.labelBackgroundGray,
                anchor.labelBackgroundGray,
            )
            it.lineWidth = 220
            it.viewRange = 1.25f
            it.isPersistent = false
            it.setTransformationMatrix(Matrix4f().scaling(anchor.labelScale))
        }
    }

    private fun renderParticles(
        world: org.bukkit.World,
        tick: Int,
        settings: PortalOriginGateSettings,
    ) {
        val center = anchor.center(world)
        val nearby = world.players.filter { it.location.distanceSquared(center) <= config.reducedParticleDistance * config.reducedParticleDistance }
        val full = nearby.filter { it.location.distanceSquared(center) <= config.fullParticleDistance * config.fullParticleDistance }
        val reduced = nearby.filterNot { it in full }
        if (full.isEmpty() && reduced.isEmpty()) return
        BukkitPortalOriginGate.renderSuction(center, tick, settings, anchor.style, full, reduced)
    }

    fun remove() {
        controller?.remove()
        controller = null
        labels.forEach { if (it.isValid) it.remove() }
        labels = emptyList()
        spawnAttempted = false
        gateSpawned = false
    }

    private fun labelColor(style: PortalVisualStyle): TextColor =
        when (style) {
            PortalVisualStyle.ORIGIN -> TextColor.color(0x7EE787)
            PortalVisualStyle.ASTRAL -> TextColor.color(0x77D9FF)
            PortalVisualStyle.VOID -> TextColor.color(0xD7A8FF)
            else -> TextColor.color(0xF2E8D5)
        }
}

internal fun originPortalVisualChunksLoaded(anchor: OriginPortalAnchor, world: org.bukkit.World): Boolean {
    val locations = sequenceOf(anchor.center(world)) + anchor.labelLocations(world).asSequence()
    return locations
        .map { (it.blockX shr 4) to (it.blockZ shr 4) }
        .distinct()
        .all { (chunkX, chunkZ) -> world.isChunkLoaded(chunkX, chunkZ) }
}

internal fun originPortalDisplayCenter(anchor: OriginPortalAnchor, world: org.bukkit.World): Location =
    anchor.center(world).apply {
        if (anchor.id.liftDisplayByConfiguredOffset) y += anchor.verticalOffset
    }

internal fun shouldResetOriginPortalVisual(
    spawnAttempted: Boolean,
    chunksLoaded: Boolean,
    gateSpawned: Boolean,
    gateActive: Boolean,
): Boolean = !chunksLoaded || (spawnAttempted && gateSpawned && !gateActive)

object OriginPortalsModule : PluginModule, Listener {
    override val name = "OriginPortals"
    override val priority = 23

    private var config: OriginPortalsConfig? = null
    private var visuals: List<OriginPortalVisual> = emptyList()
    private var task: ScheduledTask? = null
    private var transferTasks = LifecycleTaskScope()
    private var tick = 0
    private val inside = mutableMapOf<UUID, OriginPortalId>()
    private val transfers = OriginPortalTransferTracker()

    override fun init() {
        transferTasks = LifecycleTaskScope()
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        apply()
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        task?.cancel()
        task = null
        transferTasks.close()
        transfers.clear()
        visuals.forEach(OriginPortalVisual::remove)
        visuals = emptyList()
        config = null
        inside.clear()
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val current = config?.takeIf { it.enabled }?.anchors?.nearestContaining(event.to)
        if (current == null) {
            inside.remove(event.player.uniqueId)
            return
        }
        if (shouldBypassOriginPortal(current.id, event.player.hasPermission(BYPASS_PERMISSION))) {
            inside.remove(event.player.uniqueId)
            return
        }
        val previous = config?.anchors?.nearestContaining(event.from)
        if (previous?.id == current.id || inside[event.player.uniqueId] == current.id) return
        inside[event.player.uniqueId] = current.id
        if (current.id.destinationServer != null) {
            beginTransfer(event.player, current)
        } else {
            event.player.performCommand(current.command)
        }
    }

    /**
     * CMI and other command-based portal integrations can dispatch the same route independently
     * of [onMove]. Keep the administrator bypass authoritative at the command boundary as well.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortalCommand(event: PlayerCommandPreprocessEvent) {
        if (!event.player.hasPermission(BYPASS_PERMISSION)) return
        val current = config?.takeIf { it.enabled }?.anchors?.nearestContaining(event.player.location) ?: return
        if (!shouldBypassOriginPortal(current.id, hasBypassPermission = true)) return
        if (matchesPortalCommand(event.message, current.command)) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        inside.remove(event.player.uniqueId)
        transfers.clearPlayer(event.player.uniqueId)
    }

    internal fun enter(id: OriginPortalId, player: Player): OriginPortalEnterResult {
        val current = config?.takeIf { it.enabled } ?: return OriginPortalEnterResult.PORTAL_DISABLED
        val anchor = current.anchors.firstOrNull { it.id == id && it.enabled }
            ?: return OriginPortalEnterResult.PORTAL_DISABLED
        val destination = id.destinationServer ?: return OriginPortalEnterResult.UNSUPPORTED_DESTINATION
        if (id != OriginPortalId.SLIMEFUN || destination != "slimefun") {
            return OriginPortalEnterResult.UNSUPPORTED_DESTINATION
        }
        if (anchor.worldName != id.defaultWorld || player.world.name != id.defaultWorld) {
            return OriginPortalEnterResult.WRONG_WORLD
        }
        if (player.location.distanceSquared(anchor.center(player.world)) > anchor.transferDistance * anchor.transferDistance) {
            return OriginPortalEnterResult.TOO_FAR
        }
        return beginTransfer(player, anchor)
    }

    internal fun move(id: OriginPortalId, player: Player): Boolean = move(id, player.location)

    internal fun move(id: OriginPortalId, location: Location): Boolean {
        val current = config ?: OriginPortalsConfig.load(ARC.instance.dataPath).also { config = it }
        return runCatching {
            current.persistFeet(id, location)
            apply()
        }.onFailure { warn("Could not persist Origin portal {} location: {}", id.key, it.message ?: it.javaClass.simpleName) }
            .isSuccess
    }

    internal fun anchors(): List<OriginPortalAnchor> = config?.anchors.orEmpty()

    private fun apply() {
        task?.cancel()
        task = null
        visuals.forEach(OriginPortalVisual::remove)
        visuals = emptyList()
        inside.clear()
        tick = 0
        val next = OriginPortalsConfig.load(ARC.instance.dataPath)
        config = next
        if (!next.enabled) return
        visuals = next.anchors.filter { it.enabled }.map { OriginPortalVisual(it, next) }
        task = repeating(1.ticks, delay = 1.ticks) {
            visuals.forEach { it.tick(tick) }
            tick = if (tick == Int.MAX_VALUE) 0 else tick + 1
        }
    }

    private fun List<OriginPortalAnchor>.nearestContaining(location: Location): OriginPortalAnchor? =
        asSequence()
            .filter { it.enabled }
            .filter { it.contains(location) }
            .minByOrNull {
                val dx = location.x - it.x
                val dy = location.y - it.y
                val dz = location.z - it.z
                dx * dx + dy * dy + dz * dz
            }

    private fun beginTransfer(player: Player, anchor: OriginPortalAnchor): OriginPortalEnterResult {
        val destination = anchor.id.destinationServer ?: return OriginPortalEnterResult.UNSUPPORTED_DESTINATION
        val sourceServer = ARC.serverName?.takeIf(String::isNotBlank)
            ?: return OriginPortalEnterResult.TRANSFER_UNAVAILABLE
        val attempt = transfers.begin(player.uniqueId)
        if (attempt !is OriginPortalTransferAttempt.Started) {
            return when (attempt) {
                OriginPortalTransferAttempt.AlreadyPending -> OriginPortalEnterResult.ALREADY_PENDING
                is OriginPortalTransferAttempt.CoolingDown -> OriginPortalEnterResult.COOLDOWN
                is OriginPortalTransferAttempt.Started -> error("unreachable")
            }
        }
        val messenger = ARC.pluginMessenger
        if (messenger == null || !runCatching { messenger.sendPlayerToServer(player, destination) }.getOrDefault(false)) {
            transfers.finish(player.uniqueId, attempt.token)
            return OriginPortalEnterResult.TRANSFER_UNAVAILABLE
        }
        player.sendMessage(TextUtil.mm("<green>Запрос на переход на сервер <white>Slimefun<green> отправлен. Проверяю соединение…"))
        transferTasks.runLater(TRANSFER_TIMEOUT_TICKS) {
            if (!transfers.finish(player.uniqueId, attempt.token)) return@runLater
            val currentPlayer = Bukkit.getPlayer(player.uniqueId) ?: return@runLater
            if (currentPlayer.isOnline && ARC.serverName.equals(sourceServer, ignoreCase = true)) {
                currentPlayer.sendMessage(
                    TextUtil.mm(
                        "<red>Переход не завершился: вы всё ещё на Origin. Попробуйте снова через несколько секунд.",
                    ),
                )
            }
        }
        return OriginPortalEnterResult.REQUESTED
    }

    private const val BYPASS_PERMISSION = "arc.origin.portals.bypass"
    private const val TRANSFER_TIMEOUT_TICKS = 200L
}

internal enum class OriginPortalEnterResult {
    REQUESTED,
    PORTAL_DISABLED,
    WRONG_WORLD,
    TOO_FAR,
    ALREADY_PENDING,
    COOLDOWN,
    TRANSFER_UNAVAILABLE,
    UNSUPPORTED_DESTINATION,
}

internal sealed interface OriginPortalTransferAttempt {
    data class Started(val token: Long) : OriginPortalTransferAttempt
    data object AlreadyPending : OriginPortalTransferAttempt
    data object CoolingDown : OriginPortalTransferAttempt
}

/** Per-player repeat guard; delayed callbacks are fenced by their unique token. */
internal class OriginPortalTransferTracker(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cooldownMillis: Long = 10_000L,
) {
    private val pending = mutableMapOf<UUID, Long>()
    private val cooldownUntil = mutableMapOf<UUID, Long>()
    private var nextToken = 0L

    fun begin(playerId: UUID): OriginPortalTransferAttempt {
        val now = nowMillis()
        cooldownUntil.entries.removeIf { it.value <= now && it.key !in pending }
        if (playerId in pending) return OriginPortalTransferAttempt.AlreadyPending
        if ((cooldownUntil[playerId] ?: Long.MIN_VALUE) > now) return OriginPortalTransferAttempt.CoolingDown
        val token = ++nextToken
        pending[playerId] = token
        cooldownUntil[playerId] = now + cooldownMillis
        return OriginPortalTransferAttempt.Started(token)
    }

    fun finish(playerId: UUID, token: Long): Boolean = pending.remove(playerId, token)

    fun clearPlayer(playerId: UUID) {
        pending.remove(playerId)
        cooldownUntil.remove(playerId)
    }

    fun clear() {
        pending.clear()
        cooldownUntil.clear()
    }
}

internal fun shouldBypassOriginPortal(id: OriginPortalId, hasBypassPermission: Boolean): Boolean =
    id.central && hasBypassPermission

internal fun matchesPortalCommand(message: String, configuredCommand: String): Boolean =
    message.trim().removePrefix("/").equals(configuredCommand.trim().removePrefix("/"), ignoreCase = true)
