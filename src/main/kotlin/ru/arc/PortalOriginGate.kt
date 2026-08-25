package ru.arc

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.config.Config
import ru.arc.util.Logging.warn

internal const val ORIGIN_GATE_PERMISSION = "arc.portal.origin-gate"

internal fun shouldUseOriginGate(
    enabled: Boolean,
    hasPermission: Boolean,
): Boolean = enabled && hasPermission

internal data class PortalOriginGateSettings(
    val astralItemId: String,
    val chaosItemId: String,
    val openingStartTick: Int,
    val openingDurationTicks: Int,
    val closingDurationTicks: Int,
    val width: Float,
    val height: Float,
    val verticalOffset: Double,
    val viewRange: Float,
) {
    companion object {
        private val namespacedId = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        fun load(config: Config): PortalOriginGateSettings? {
            val path = "portal.origin-gate"
            if (!config.bool("$path.enabled", false)) return null

            return validated(
                astralItemId = config.string("$path.astral-item", ""),
                chaosItemId = config.string("$path.chaos-item", ""),
                openingStartTick = config.integer("$path.opening-start-tick", 32),
                openingDurationTicks = config.integer("$path.opening-duration-ticks", 24),
                closingDurationTicks = config.integer("$path.closing-duration-ticks", 12),
                width = config.real("$path.width", 1.2).toFloat(),
                height = config.real("$path.height", 2.2).toFloat(),
                verticalOffset = config.real("$path.vertical-offset", 2.0),
                viewRange = config.real("$path.view-range", 1.0).toFloat(),
            ).also { settings ->
                if (settings == null) {
                    warn("Portal origin-gate config is invalid; falling back to the legacy portal visual")
                }
            }
        }

        internal fun validated(
            astralItemId: String,
            chaosItemId: String,
            openingStartTick: Int,
            openingDurationTicks: Int,
            closingDurationTicks: Int,
            width: Float,
            height: Float,
            verticalOffset: Double,
            viewRange: Float,
        ): PortalOriginGateSettings? {
            val normalizedAstral = astralItemId.trim().lowercase()
            val normalizedChaos = chaosItemId.trim().lowercase()
            if (normalizedAstral.length !in 3..128 || !namespacedId.matches(normalizedAstral)) return null
            if (normalizedChaos.length !in 3..128 || !namespacedId.matches(normalizedChaos)) return null
            if (openingStartTick !in 0..58) return null
            if (openingDurationTicks !in 1..60 || closingDurationTicks !in 1..60) return null
            if (!width.isFinite() || width !in 0.1f..4.0f) return null
            if (!height.isFinite() || height !in 0.1f..4.0f) return null
            if (!verticalOffset.isFinite() || verticalOffset !in 0.5..4.0) return null
            if (!viewRange.isFinite() || viewRange !in 0.1f..4.0f) return null

            return PortalOriginGateSettings(
                astralItemId = normalizedAstral,
                chaosItemId = normalizedChaos,
                openingStartTick = openingStartTick,
                openingDurationTicks = openingDurationTicks,
                closingDurationTicks = closingDurationTicks,
                width = width,
                height = height,
                verticalOffset = verticalOffset,
                viewRange = viewRange,
            )
        }
    }
}

internal interface PortalOriginGateHandle {
    fun beginOpening(durationTicks: Int)

    fun beginClosing(durationTicks: Int)

    fun remove()
}

internal class PortalOriginGateController(
    private val settings: PortalOriginGateSettings,
    private val spawn: () -> PortalOriginGateHandle?,
) {
    private var handle: PortalOriginGateHandle? = null
    private var spawnAttempted = false
    private var openingStarted = false
    private var closing = false
    private var closingTicks = 0
    private var removed = false

    val isActive: Boolean
        get() = !removed && handle != null

    fun tickOpening(phase: Int): Boolean {
        if (removed || closing || phase < settings.openingStartTick) return isActive

        if (!spawnAttempted) {
            spawnAttempted = true
            handle = spawn()
        }

        val activeHandle = handle ?: return false
        if (!openingStarted && phase > settings.openingStartTick) {
            openingStarted = true
            activeHandle.beginOpening(settings.openingDurationTicks)
        }
        return true
    }

    fun beginClosing() {
        val activeHandle = handle ?: return
        if (removed || closing) return
        closing = true
        closingTicks = 0
        activeHandle.beginClosing(settings.closingDurationTicks)
    }

    fun tickClosing(): Boolean {
        if (removed || !closing || handle == null) return false
        if (closingTicks >= settings.closingDurationTicks) return false
        closingTicks++
        return true
    }

    fun remove() {
        if (removed) return
        removed = true
        handle?.remove()
        handle = null
    }
}

internal object BukkitPortalOriginGate {
    fun spawn(
        base: Block,
        settings: PortalOriginGateSettings,
    ): PortalOriginGateHandle? {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null

        val astral = item(settings.astralItemId) ?: return missing(settings.astralItemId)
        val chaos = item(settings.chaosItemId) ?: return missing(settings.chaosItemId)
        val location =
            base.location.clone().add(
                0.5,
                settings.verticalOffset,
                0.5,
            )

        var display: ItemDisplay? = null
        return try {
            val created = base.world.spawn(location, ItemDisplay::class.java)
            display = created
            configure(created, astral, settings)
            BukkitPortalOriginGateHandle(created, chaos, settings)
        } catch (failure: Exception) {
            display?.remove()
            warn(
                "Could not spawn portal origin-gate display; falling back to the legacy visual: {}",
                failure.message ?: failure.javaClass.simpleName,
            )
            null
        }
    }

    private fun item(namespacedId: String): ItemStack? =
        runCatching { CustomStack.getInstance(namespacedId)?.itemStack?.clone() }.getOrNull()

    private fun missing(namespacedId: String): PortalOriginGateHandle? {
        warn("Portal origin-gate item '{}' is unavailable; falling back to the legacy visual", namespacedId)
        return null
    }

    private fun configure(
        display: ItemDisplay,
        astral: ItemStack,
        settings: PortalOriginGateSettings,
    ) {
        display.setItemStack(astral)
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        display.billboard = Display.Billboard.CENTER
        display.brightness = Display.Brightness(15, 15)
        display.shadowRadius = 0f
        display.shadowStrength = 0f
        display.viewRange = settings.viewRange
        display.displayWidth = settings.width * 1.25f
        display.displayHeight = settings.height * 1.25f
        display.isPersistent = false
        display.setGravity(false)
        display.isInvulnerable = true
        display.transformation = transformation(TINY_SCALE, TINY_SCALE, TINY_SCALE)
    }

    private fun transformation(
        x: Float,
        y: Float,
        z: Float,
    ): Transformation =
        Transformation(
            Vector3f(),
            AxisAngle4f(),
            Vector3f(x, y, z),
            AxisAngle4f(),
        )

    private const val TINY_SCALE = 0.025f

    private class BukkitPortalOriginGateHandle(
        private val display: ItemDisplay,
        private val chaos: ItemStack,
        private val settings: PortalOriginGateSettings,
    ) : PortalOriginGateHandle {
        override fun beginOpening(durationTicks: Int) {
            updateTransformation(durationTicks, settings.width, settings.height, 1f)
        }

        override fun beginClosing(durationTicks: Int) {
            if (!display.isValid) return
            display.setItemStack(chaos)
            updateTransformation(durationTicks, TINY_SCALE, TINY_SCALE, TINY_SCALE)
        }

        override fun remove() {
            if (display.isValid) display.remove()
        }

        private fun updateTransformation(
            durationTicks: Int,
            x: Float,
            y: Float,
            z: Float,
        ) {
            if (!display.isValid) return
            display.interpolationDelay = 0
            display.interpolationDuration = durationTicks
            display.transformation = transformation(x, y, z)
        }
    }
}
