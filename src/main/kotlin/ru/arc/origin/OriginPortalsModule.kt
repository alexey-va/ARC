package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
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
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.Logging.warn
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
) {
    SURVIVAL(
        key = "survival",
        central = true,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = PortalVisualStyle.ORIGIN,
        defaultCommand = "arc rtp survival --only-if-first",
        defaultLabel = "Выживание",
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
        defaultLabel = "Ресурсы",
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
        defaultLabel = "Ваниль",
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
    ;

    companion object {
        fun parse(raw: String?): OriginPortalId? =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) }
    }
}

internal data class OriginPortalAnchor(
    val id: OriginPortalId,
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
    val style: PortalVisualStyle,
) {
    fun center(world: org.bukkit.World): Location = Location(world, x, y, z, yaw, 0f)

    /** A thin, yaw-aware interaction plane keeps neighbouring central portals independent. */
    fun contains(location: Location): Boolean {
        if (location.world?.name != worldName) return false
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
                PortalVisualStyle.CHAOS to "origin_gate_portals:chaos_portal",
                PortalVisualStyle.SOLAR to "origin_gate_portals:solar_portal",
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
            val anchors = OriginPortalId.entries.map { id -> anchor(source, root, id) }
            return OriginPortalsConfig(
                source = source,
                enabled = enabled,
                verticalOffset = source.real("$root.vertical-offset", 5.5).coerceIn(0.5, 12.0),
                entryDepth = source.real("$root.entry-depth", 2.0).coerceIn(0.5, 6.0),
                pulseAmplitude = source.real("$root.pulse.amplitude", 0.035).toFloat().coerceIn(0.0f, 0.1f),
                pulsePeriodTicks = source.integer("$root.pulse.period-ticks", 36).coerceIn(8, 200),
                particlesEnabled = source.bool("$root.particles.enabled", true),
                particleStreams = source.integer("$root.particles.streams", 4).coerceIn(1, 8),
                reducedParticleStreams = source.integer("$root.particles.reduced-streams", 2).coerceIn(1, 8),
                particlePointsPerStream = source.integer("$root.particles.points-per-stream", 2).coerceIn(1, 4),
                reducedParticlePointsPerStream = source.integer("$root.particles.reduced-points-per-stream", 1).coerceIn(1, 4),
                particleRadius = source.real("$root.particles.radius", 5.5).coerceIn(0.25, 8.0),
                particleHeight = source.real("$root.particles.height", 8.4).coerceIn(0.25, 16.0),
                particleTurns = source.real("$root.particles.turns", 1.75).coerceIn(0.25, 4.0),
                particleSize = source.real("$root.particles.size", 0.6).toFloat().coerceIn(0.1f, 2.0f),
                particleCoreCount = source.integer("$root.particles.core-count", 4).coerceIn(0, 12),
                fullParticleDistance = source.real("$root.particles.full-distance", 24.0).coerceIn(1.0, 48.0),
                reducedParticleDistance = source.real("$root.particles.reduced-distance", 40.0).coerceIn(1.0, 64.0),
                itemIds = itemIds,
                anchors = anchors,
            )
        }

        private fun anchor(source: Config, root: String, id: OriginPortalId): OriginPortalAnchor {
            val path = "$root.anchors.${id.key}"
            val style = PortalVisualStyle.parse(source.string("$path.style", id.defaultStyle.id)) ?: id.defaultStyle
            val width = source.real("$path.width", id.defaultWidth).finite(id.defaultWidth).coerceIn(0.1, 12.0)
            val height = source.real("$path.height", id.defaultHeight).finite(id.defaultHeight).coerceIn(0.1, 20.0)
            return OriginPortalAnchor(
                id = id,
                worldName = source.string("$path.world", id.defaultWorld).trim().ifEmpty { id.defaultWorld },
                x = source.real("$path.x", id.defaultX).finite(id.defaultX),
                y = source.real("$path.y", id.defaultY).finite(id.defaultY),
                z = source.real("$path.z", id.defaultZ).finite(id.defaultZ),
                yaw = source.real("$path.yaw", id.defaultYaw.toDouble()).finite(id.defaultYaw.toDouble()).toFloat(),
                width = width,
                height = height,
                entryDepth = source.real("$path.entry-depth", source.real("$root.entry-depth", 2.0)).finite(2.0).coerceIn(0.5, 6.0),
                command = source.string("$path.command", id.defaultCommand).trim().ifEmpty { id.defaultCommand },
                label = source.string("$path.label", id.defaultLabel).trim().ifEmpty { id.defaultLabel },
                style = style.takeIf { it.usesOriginGate } ?: id.defaultStyle,
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
            verticalOffset = verticalOffset,
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
            suctionRadius = particleRadius,
            suctionHeight = particleHeight,
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
    private var label: TextDisplay? = null
    private var spawnAttempted = false

    fun tick(tick: Int) {
        val world = Bukkit.getWorld(anchor.worldName) ?: return
        val settings = config.gateSettings(anchor) ?: return
        if (!spawnAttempted) {
            spawnAttempted = true
            controller =
                PortalOriginGateController(settings) {
                    BukkitPortalOriginGate.spawn(anchor.center(world), settings, anchor.style)
                }
            label = spawnLabel(world)
        }
        val active = controller?.tickOpening(settings.entryTick + tick) == true
        if (!active) return
        val pulse = 1f + config.pulseAmplitude * sin((tick * 2.0 * PI) / config.pulsePeriodTicks).toFloat()
        // The renderer owns the display transform; ARC only supplies a bounded idle pulse.
        controller?.updateScale(pulse)
        renderParticles(world, tick, settings)
    }

    private fun spawnLabel(world: org.bukkit.World): TextDisplay {
        val location = anchor.center(world).add(0.0, config.verticalOffset + 0.75, 0.0)
        return world.spawn(location, TextDisplay::class.java) {
            it.text(
                Component.text(anchor.label, labelColor(anchor.style))
                    .decorate(TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false),
            )
            it.billboard = Display.Billboard.CENTER
            it.isSeeThrough = false
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(0, 0, 0, 0)
            it.lineWidth = 220
            it.viewRange = 1.25f
            it.setTransformationMatrix(Matrix4f().scaling(if (anchor.id.central) 1.15f else 0.9f))
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
        label?.remove()
        label = null
        spawnAttempted = false
    }

    private fun labelColor(style: PortalVisualStyle): TextColor =
        when (style) {
            PortalVisualStyle.ORIGIN -> TextColor.color(0x7EE787)
            PortalVisualStyle.ASTRAL -> TextColor.color(0x77D9FF)
            PortalVisualStyle.CHAOS -> TextColor.color(0xFF8B6B)
            PortalVisualStyle.SOLAR -> TextColor.color(0xFFD166)
            PortalVisualStyle.VOID -> TextColor.color(0xD7A8FF)
            else -> TextColor.color(0xF2E8D5)
        }
}

object OriginPortalsModule : PluginModule, Listener {
    override val name = "OriginPortals"
    override val priority = 23

    private var config: OriginPortalsConfig? = null
    private var visuals: List<OriginPortalVisual> = emptyList()
    private var task: ScheduledTask? = null
    private var tick = 0
    private val inside = mutableMapOf<UUID, OriginPortalId>()

    override fun init() {
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
        visuals.forEach(OriginPortalVisual::remove)
        visuals = emptyList()
        config = null
        inside.clear()
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val current = config?.takeIf { it.enabled }?.anchors?.nearestContaining(event.to)
        val previous = config?.anchors?.nearestContaining(event.from)
        if (current == null) {
            inside.remove(event.player.uniqueId)
            return
        }
        if (previous?.id == current.id || inside[event.player.uniqueId] == current.id) return
        inside[event.player.uniqueId] = current.id
        if (current.id.central && event.player.hasPermission(BYPASS_PERMISSION)) return
        event.player.performCommand(current.command)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        inside.remove(event.player.uniqueId)
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
        visuals = next.anchors.map { OriginPortalVisual(it, next) }
        task = repeating(1.ticks, delay = 1.ticks) {
            visuals.forEach { it.tick(tick) }
            tick = if (tick == Int.MAX_VALUE) 0 else tick + 1
        }
    }

    private fun List<OriginPortalAnchor>.nearestContaining(location: Location): OriginPortalAnchor? =
        asSequence()
            .filter { it.contains(location) }
            .minByOrNull {
                val dx = location.x - it.x
                val dy = location.y - it.y
                val dz = location.z - it.z
                dx * dx + dy * dy + dz * dz
            }

    private const val BYPASS_PERMISSION = "arc.origin.portals.bypass"
}
