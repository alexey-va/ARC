package ru.arc.onboarding

import me.angeschossen.lands.api.LandsIntegration
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.joml.Matrix4f
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.landsui.LandsUiModule
import ru.arc.landsui.RegionToolItem
import ru.arc.util.Logging.error
import java.util.UUID

/** Read-only Lands guidance. All entities are transient and visible to just their owner. */
internal class ClaimBlockGuide(private val config: OnboardingConfig) : Listener, AutoCloseable {
    private val integration = LandsIntegration.of(ARC.instance)
    private val tasks = LifecycleTaskScope()
    private val sessions = mutableMapOf<UUID, Session>()
    private val failedViewers = mutableSetOf<UUID>()
    private val text = OnboardingConfig.CLAIM_TEXT.keys.associateWith(config::claimText)
    private var tick = 0L

    private class Session(val world: UUID) {
        val borders = mutableMapOf<GuideBorder, BlockDisplay>()
        var label: TextDisplay? = null
        var button: TextDisplay? = null
        var buttonLand: String? = null
        var menuLand: String? = null
        var anchor: Location? = null
        var clickAfter = 0L
        var borderY = Double.NaN
        var successUntil = 0L
        val confirmed = linkedSetOf<GuideChunk>()
        var aimed: GuideChunk? = null
        var radius = 0
    }

    fun start() {
        tasks.runTimer(1L, 1L) {
            tick++
            Bukkit.getOnlinePlayers().forEach { player ->
                try {
                    sessions[player.uniqueId]?.let { session ->
                        // Freeze while sneaking so a side button can actually be aimed at.
                        if (!player.isSneaking) session.anchor = player.eyeLocation
                    }
                    if (tick == 1L || tick % 5L == 0L) update(player)
                    sessions[player.uniqueId]?.let { session ->
                        val eye = session.anchor ?: player.eyeLocation
                        follow(session.label, claimGuideLabelLocation(eye))
                        follow(session.button, claimGuideButtonLocation(eye))
                        val y = claimGuideBorderY(player.eyeLocation.y)
                        if (session.borderY != y) {
                            session.borders.values.forEach { it.teleport(it.location.apply { this.y = y }) }
                            session.borderY = y
                        }
                    }
                } catch (failure: Exception) {
                    // Stop this viewer until reconnect, avoiding a 4 Hz error loop.
                    clear(player)
                    failedViewers += player.uniqueId
                    error("Claim guide failed for {} in {}; disabled until reconnect", player.name, player.world.name, failure)
                }
            }
        }
    }

    private fun update(player: Player) {
        if (player.uniqueId in failedViewers) return
        if (RegionToolItem.matches(player.inventory.itemInMainHand)) { clear(player); return }
        if (!config.allowsWorld(player.world.name) || player.isDead || player.gameMode == GameMode.SPECTATOR ||
            integration.getWorld(player.world) == null
        ) {
            clear(player)
            return
        }
        val heldRadius = ClaimBlockIdentity.radius(player.inventory.itemInMainHand)
            ?: ClaimBlockIdentity.radius(player.inventory.itemInOffHand)
        val holding = heldRadius != null
        var session = sessions[player.uniqueId]
        if (session != null && session.world != player.world.uid) {
            clear(player)
            session = null
        }
        if (!holding && (session == null || tick >= session.successUntil)) {
            clear(player)
            return
        }
        if (session == null) {
            session = Session(player.world.uid).also { it.anchor = player.eyeLocation }
            sessions[player.uniqueId] = session

        }
        if (heldRadius != null && heldRadius != session.radius) {
            session.radius = heldRadius
            session.confirmed.clear()
            session.successUntil = 0
        }
        val hit = player.rayTraceBlocks(5.0, FluidCollisionMode.NEVER)
        val hitBlock = hit?.hitBlock
        val placement = hitBlock?.let { block ->
            if (block.isReplaceable) block else hit.hitBlockFace?.let(block::getRelative)
        }?.takeIf { it.y in player.world.minHeight until player.world.maxHeight }
        val success = tick < session.successUntil && (!holding || placement == null ||
            GuideChunk(placement.x shr 4, placement.z shr 4) in session.confirmed)
        val target = if (success) session.aimed?.takeIf { it in session.confirmed } ?: session.confirmed.first()
            else claimGuideTarget(placement?.x, placement?.z, player.location.blockX, player.location.blockZ)
        if (!success && session.aimed != target) {
            session.aimed = target
            session.confirmed.clear()
            session.successUntil = 0
        }
        val world = player.world
        if (!world.isChunkLoaded(target.x, target.z)) {
            clearVisuals(session)
            return
        }
        val footprint = if (success) session.confirmed.toSet() else claimGuideChunks(target, session.radius)
        val claims = footprint.associateWith { integration.getLandByUnloadedChunk(world, it.x, it.z) }
        val selected = integration.getLandPlayer(player.uniqueId)?.getEditLand(false)
        val own = claims.values.all { it != null && it.ownerUID == player.uniqueId }
        val occupied = claims.values.any { it != null && it.ownerUID != player.uniqueId }
        val state = when {
            success -> "success"
            own -> "own"
            occupied -> "occupied"
            selected != null -> "expand"
            else -> "free"
        }
        val currentChunk = GuideChunk(player.location.blockX shr 4, player.location.blockZ shr 4)
        val visible = claimGuideChunks(currentChunk, 1)
        val nearby = buildMap {
            for (x in currentChunk.x - 2..currentChunk.x + 2)
                for (z in currentChunk.z - 2..currentChunk.z + 2) {
                    put(GuideChunk(x, z), integration.getLandByUnloadedChunk(world, x, z))
                }
        }
        val plan = claimGuideBorders(visible, nearby.mapValues { it.value?.ulid?.toString() }).toSet()
        val iterator = session.borders.iterator()
        while (iterator.hasNext()) {
            val (key, display) = iterator.next()
            if (key !in plan || !display.isValid) { display.remove(); iterator.remove() }
        }
        val landsById = nearby.values.filterNotNull().associateBy { it.ulid.toString() }
        for (border in plan) {
            val material = when {
                border.landId == null -> Material.LIGHT_GRAY_CONCRETE
                landsById[border.landId]?.ownerUID == player.uniqueId -> Material.LIGHT_BLUE_CONCRETE
                else -> Material.RED_CONCRETE
            }
            val display = session.borders.getOrPut(border) { drawBorder(player, border, material) }
            if (display.block.material != material) {
                display.block = material.createBlockData()
                display.glowColorOverride = if (material == Material.RED_CONCRETE) Color.RED else Color.AQUA
            }
        }
        val eye = session.anchor ?: player.eyeLocation
        val labelLocation = claimGuideLabelLocation(eye)
        val label = session.label?.takeIf { it.isValid } ?: world.spawn(labelLocation, TextDisplay::class.java) {
            configure(it)
            it.billboard = Display.Billboard.CENTER
            it.isSeeThrough = true
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(255, 15, 23, 30)
            it.lineWidth = 230
            it.teleportDuration = 2
            it.setTransformationMatrix(Matrix4f().scaling(1.30f))
        }.also { session.label = it; player.showEntity(ARC.instance, it) }
        if (label.location.distanceSquared(labelLocation) > 0.01) label.teleport(labelLocation)
        session.menuLand = selected?.ulid?.toString()
        val message = claimGuideLandText(text.getValue(state), selected?.name?.take(24))
        label.text(if (LandsUiModule.isAvailable()) message.append(Component.newline()).append(text.getValue("menu")) else message)
        if (holding && selected != null && LandsUiModule.isAvailable()) {
            val button = session.button?.takeIf { it.isValid }
                ?: world.spawn(claimGuideButtonLocation(eye), TextDisplay::class.java) {
                    configure(it)
                    it.billboard = Display.Billboard.CENTER
                    it.isSeeThrough = true
                    it.isShadowed = true
                    it.backgroundColor = Color.fromARGB(255, 15, 40, 50)
                    it.lineWidth = 160
                    it.teleportDuration = 2
                    it.setTransformationMatrix(Matrix4f().scaling(1.10f))
                }.also { session.button = it; player.showEntity(ARC.instance, it) }
            session.buttonLand = selected.ulid.toString()
            button.text(claimGuideLandText(text.getValue("add-friend"), selected.name.let { if (it.length > 24) it.take(23) + "…" else it }))
        } else {
            session.button?.remove()
            session.button = null
            session.buttonLand = null
        }
    }

    private fun follow(display: TextDisplay?, position: Location) {
        if (display != null && display.isValid && display.world == position.world &&
            display.location.distanceSquared(position) > 0.0001) display.teleport(position)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun clickButton(event: PlayerInteractEvent) {
        val player = event.player
        // Displays have no physical hitbox. Never intercept placement, including Shift + RMB.
        if (RegionToolItem.matches(player.inventory.itemInMainHand)) return
        if (!claimGuideButtonGesture(event.action, event.hand, player.isSneaking)) return
        if (!ClaimBlockIdentity.matches(player.inventory.itemInMainHand) &&
            !ClaimBlockIdentity.matches(player.inventory.itemInOffHand)) return
        val session = sessions[player.uniqueId] ?: return
        if (tick < session.clickAfter || !LandsUiModule.isAvailable()) return
        val friend = session.button?.takeIf { it.isValid }
            ?.let { claimGuideButtonHit(player.eyeLocation, it.location) } == true
        val menu = session.label?.takeIf { it.isValid }
            ?.let { claimGuideButtonHit(player.eyeLocation, it.location, halfWidth = 3.8, height = 1.0) } == true
        if (!friend && !menu) return
        val shownLand = if (friend) session.buttonLand else session.menuLand
        val selected = integration.getLandPlayer(player.uniqueId)?.getEditLand(false)
        if (shownLand != selected?.ulid?.toString()) { update(player); return }
        event.isCancelled = true
        session.clickAfter = tick + 10
        if (friend && shownLand != null) LandsUiModule.openAddMember(player, shownLand)
        else if (shownLand != null) LandsUiModule.openDetails(player, shownLand)
        else LandsUiModule.open(player)
    }

    private fun drawBorder(player: Player, border: GuideBorder, material: Material): BlockDisplay {
        // Reuse stable edge entities as the local window moves; never load a boundary chunk.
        val edge = border.edge
        val width = if (border.landId == null) 0.05f else 0.14f
        val height = if (border.landId == null) 0.045f else 0.20f
        val origin = player.location.apply { y = claimGuideBorderY(player.eyeLocation.y) }
        return player.world.spawn(origin, BlockDisplay::class.java) {
            configure(it)
            it.block = material.createBlockData()
            it.teleportDuration = 3
            it.setTransformationMatrix(Matrix4f().translation(
                (edge.x - origin.x).toFloat() - if (edge.alongX) 0f else width / 2,
                -height / 2,
                (edge.z - origin.z).toFloat() - if (edge.alongX) width / 2 else 0f,
            ).scale(if (edge.alongX) 16f else width, height, if (edge.alongX) width else 16f))
            it.isGlowing = true
            it.glowColorOverride = when (material) {
                Material.RED_CONCRETE -> Color.RED
                Material.LIGHT_BLUE_CONCRETE -> Color.AQUA
                else -> Color.fromRGB(165, 190, 205)
            }
        }.also { player.showEntity(ARC.instance, it) }
    }

    private fun configure(display: Display) {
        display.isPersistent = false
        display.isVisibleByDefault = false
        display.setGravity(false)
        display.brightness = Display.Brightness(15, 15)
        display.viewRange = 1.5f
    }

    fun claimed(player: Player, worldName: String, x: Int, z: Int) {
        if (worldName != player.world.name || !config.allowsWorld(worldName)) return
        val session = sessions[player.uniqueId] ?: return
        val chunk = GuideChunk(x, z)
        val aimed = session.aimed ?: return
        if (chunk !in claimGuideChunks(aimed, session.radius)) return
        if (tick >= session.successUntil) {
            session.confirmed.clear()
        }
        session.confirmed += chunk
        session.successUntil = tick + 100
    }

    private fun clearVisuals(session: Session) {
        session.borders.values.forEach(Entity::remove)
        session.borders.clear()
        session.label?.remove()
        session.label = null
        session.menuLand = null
        session.button?.remove()
        session.button = null
        session.buttonLand = null
    }

    fun hasHologram(player: Player): Boolean = sessions[player.uniqueId]?.label?.isValid == true

    private fun clear(player: Player) {
        sessions.remove(player.uniqueId)?.let {
            clearVisuals(it)
        }
    }

    @EventHandler fun quit(event: PlayerQuitEvent) { clear(event.player); failedViewers.remove(event.player.uniqueId) }
    @EventHandler fun worldChanged(event: PlayerChangedWorldEvent) = clear(event.player)
    @EventHandler fun died(event: PlayerDeathEvent) = clear(event.entity)

    override fun close() {
        tasks.close()
        sessions.values.forEach(::clearVisuals)
        sessions.clear()
        failedViewers.clear()
    }
}
