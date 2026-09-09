package ru.arc.onboarding

import me.angeschossen.lands.api.LandsIntegration
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
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
import ru.arc.paper.audience.NativePaperAudienceEffects as effects
import ru.arc.util.Logging.error
import java.time.Duration
import java.util.UUID

/** Read-only Lands guidance. All entities are transient and visible to just their owner. */
internal class ClaimBlockGuide(private val config: OnboardingConfig) : Listener, AutoCloseable {
    private val integration = LandsIntegration.of(ARC.instance)
    private val tasks = LifecycleTaskScope()
    private val sessions = mutableMapOf<UUID, Session>()
    private val titleAfter = mutableMapOf<UUID, Long>()
    private val text = OnboardingConfig.CLAIM_TEXT.keys.associateWith(config::claimText)
    private var tick = 0L

    private class Session(val world: UUID) {
        val borders = mutableListOf<Entity>()
        var label: TextDisplay? = null
        var button: TextDisplay? = null
        var buttonLand: String? = null
        var anchor: Location? = null
        var clickAfter = 0L
        var geometry: Any? = null
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
                    }
                } catch (failure: Exception) {
                    // Stop this viewer until reconnect, avoiding a 4 Hz error loop.
                    clear(player)
                    titleAfter[player.uniqueId] = Long.MAX_VALUE
                    error("Claim guide failed for {} in {}; disabled until reconnect", player.name, player.world.name, failure)
                }
            }
        }
    }

    private fun update(player: Player) {
        if (titleAfter[player.uniqueId] == Long.MAX_VALUE) return
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
            if (tick >= (titleAfter[player.uniqueId] ?: 0L)) {
                showTitle(player, "title", "subtitle")
                effects.sendMessage(player, text.getValue("remove"))
                titleAfter[player.uniqueId] = tick + 600
            }
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
        val y = player.eyeLocation.y
        val ownChunks = linkedSetOf<GuideChunk>()
        // Bounded local view of the selected region; never load terrain to draw a guide.
        if (selected != null) for (x in target.x - 1..target.x + 1) for (z in target.z - 1..target.z + 1) {
            if (world.isChunkLoaded(x, z) && integration.getLandByUnloadedChunk(world, x, z) == selected) {
                ownChunks += GuideChunk(x, z)
            }
        }
        val currentChunk = GuideChunk(player.location.blockX shr 4, player.location.blockZ shr 4)
        val gridChunks = claimGuideChunks(currentChunk, 1)
        val gridClaims = buildMap {
            for (x in currentChunk.x - 2..currentChunk.x + 2)
                for (z in currentChunk.z - 2..currentChunk.z + 2) {
                    val chunk = GuideChunk(x, z)
                    put(chunk, integration.getLandByUnloadedChunk(world, x, z))
                }
        }
        val gridEdges = claimGuideWildernessEdges(gridChunks, gridClaims.filterValues { it != null }.keys)
        val geometry = listOf(footprint, state, ownChunks, claims, gridEdges)
        if (session.geometry != geometry || session.borders.any { !it.isValid }) {
            session.borders.forEach(Entity::remove)
            session.borders.clear()
            session.geometry = geometry
            session.borderY = y
            // Remove artificial edges at the local view's cutoff using real adjacent Lands claims.
            if (selected != null) {
                claimGuideEdges(ownChunks).filter { edge ->
                    val outside = claimGuideEdgeOutside(edge, ownChunks)
                    integration.getLandByUnloadedChunk(world, outside.x, outside.z) != selected
                }.forEach { drawEdge(player, session, it, y, Material.LIGHT_BLUE_CONCRETE, ownChunks) }
            }
            claims.entries.groupBy { (_, land) -> land?.ulid?.toString() }.forEach { (_, entries) ->
                val land = entries.first().value
                val color = when (land) {
                    null -> Material.LIME_CONCRETE
                    else -> if (land.ownerUID == player.uniqueId) Material.LIGHT_BLUE_CONCRETE else Material.RED_CONCRETE
                }
                val interior = entries.mapTo(linkedSetOf()) { it.key }
                claimGuideEdges(interior).filter { edge ->
                    val outside = claimGuideEdgeOutside(edge, interior)
                    land == null || integration.getLandByUnloadedChunk(world, outside.x, outside.z)?.ulid != land.ulid
                }.forEach { drawEdge(player, session, it, y, color, interior) }
            }
            gridEdges.forEach { drawGridEdge(player, session, it, y, gridChunks) }
        }
        if (session.borderY != y) {
            session.borders.forEach { border -> border.teleport(border.location.apply { this.y = y }) }
            session.borderY = y
        }
        val eye = session.anchor ?: player.eyeLocation
        val labelLocation = claimGuideLabelLocation(eye)
        val label = session.label?.takeIf { it.isValid } ?: world.spawn(labelLocation, TextDisplay::class.java) {
            configure(it)
            it.billboard = Display.Billboard.CENTER
            it.isSeeThrough = true
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(190, 15, 23, 30)
            it.lineWidth = 230
            it.teleportDuration = 2
            it.setTransformationMatrix(Matrix4f().scaling(1.30f))
        }.also { session.label = it; player.showEntity(ARC.instance, it) }
        if (label.location.distanceSquared(labelLocation) > 0.01) label.teleport(labelLocation)
        label.text(claimGuideLandText(text.getValue(state), selected?.name))
        if (holding && selected != null && LandsUiModule.isAvailable()) {
            val button = session.button?.takeIf { it.isValid }
                ?: world.spawn(claimGuideButtonLocation(eye), TextDisplay::class.java) {
                    configure(it)
                    it.billboard = Display.Billboard.CENTER
                    it.isSeeThrough = true
                    it.isShadowed = true
                    it.backgroundColor = Color.fromARGB(220, 15, 40, 50)
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
        if (tick % 20L == 0L) {
            val action = when {
                success || tick % 160L >= 100L -> "remove"
                gridEdges.isNotEmpty() && tick % 160L in 60L..99L -> "grid-legend"
                state == "own" -> "action-own"
                state == "occupied" -> "action-occupied"
                state == "expand" -> "action-expand"
                else -> "action-free"
            }
            effects.sendActionBar(player, claimGuideLandText(text.getValue(action), selected?.name))
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
        if (!claimGuideButtonGesture(event.action, event.hand, player.isSneaking)) return
        if (!ClaimBlockIdentity.matches(player.inventory.itemInMainHand) &&
            !ClaimBlockIdentity.matches(player.inventory.itemInOffHand)) return
        val session = sessions[player.uniqueId] ?: return
        val button = session.button?.takeIf { it.isValid } ?: return
        if (tick < session.clickAfter || !claimGuideButtonHit(player.eyeLocation, button.location)) return
        val landId = session.buttonLand ?: return
        val selected = integration.getLandPlayer(player.uniqueId)?.getEditLand(false) ?: return
        if (selected.ulid.toString() != landId || !LandsUiModule.isAvailable()) return
        event.isCancelled = true
        session.clickAfter = tick + 10
        LandsUiModule.openAddMember(player, landId)
    }

    private fun drawEdge(player: Player, session: Session, edge: GuideEdge, y: Double, material: Material, interior: Set<GuideChunk>) {
        // One straight edge at eye height; keep its origin inside the represented chunk.
        val positiveSide = GuideChunk(edge.x shr 4, edge.z shr 4) in interior
        val inset = if (positiveSide) 0.0 else -0.45
        val world = player.world
        val location = Location(world, edge.x + if (edge.alongX) 0.0 else inset,
            y, edge.z + if (edge.alongX) inset else 0.0)
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        // A broad ribbon and tall end posts stay centered on eye height, even after teleporting.
        val shapes = listOf(
            Matrix4f().translation(0f, -0.30f, 0f)
                .scale(if (edge.alongX) 16f else 0.35f, 0.60f, if (edge.alongX) 0.35f else 16f),
            Matrix4f().translation(0f, -1.20f, 0f).scale(0.45f, 2.40f, 0.45f),
            Matrix4f().translation(if (edge.alongX) 15.55f else 0f, -1.20f, if (edge.alongX) 0f else 15.55f)
                .scale(0.45f, 2.40f, 0.45f),
        )
        shapes.forEach { shape ->
            val display = world.spawn(location, BlockDisplay::class.java) {
                configure(it)
                it.block = material.createBlockData()
                it.setTransformationMatrix(shape)
                it.isGlowing = true
                it.glowColorOverride = when (material) {
                    Material.LIME_CONCRETE -> Color.LIME
                    Material.RED_CONCRETE -> Color.RED
                    else -> Color.AQUA
                }
            }
            session.borders += display
            player.showEntity(ARC.instance, display)
        }
    }

    private fun drawGridEdge(player: Player, session: Session, edge: GuideEdge, y: Double, interior: Set<GuideChunk>) {
        val positiveSide = GuideChunk(edge.x shr 4, edge.z shr 4) in interior
        val inset = if (positiveSide) 0.02 else -0.02
        val world = player.world
        val location = Location(world, edge.x + if (edge.alongX) 0.0 else inset, y,
            edge.z + if (edge.alongX) inset else 0.0)
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        val display = world.spawn(location, BlockDisplay::class.java) {
            configure(it)
            it.block = Material.LIGHT_GRAY_CONCRETE.createBlockData()
            it.setTransformationMatrix(Matrix4f().scaling(if (edge.alongX) 16f else 0.05f, 0.045f, if (edge.alongX) 0.05f else 16f))
        }
        session.borders += display
        player.showEntity(ARC.instance, display)
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
            showTitle(player, "success-title", "success-subtitle")
        }
        session.confirmed += chunk
        session.successUntil = tick + 100
    }

    private fun showTitle(player: Player, title: String, subtitle: String) {
        effects.showTitle(player, Title.title(text.getValue(title), text.getValue(subtitle),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(400))))
    }

    private fun clearVisuals(session: Session) {
        session.borders.forEach(Entity::remove)
        session.borders.clear()
        session.label?.remove()
        session.label = null
        session.button?.remove()
        session.button = null
        session.buttonLand = null
        session.geometry = null
    }

    private fun clear(player: Player) {
        sessions.remove(player.uniqueId)?.let {
            clearVisuals(it)
            effects.sendActionBar(player, Component.empty())
        }
    }

    @EventHandler fun quit(event: PlayerQuitEvent) { clear(event.player); titleAfter.remove(event.player.uniqueId) }
    @EventHandler fun worldChanged(event: PlayerChangedWorldEvent) = clear(event.player)
    @EventHandler fun died(event: PlayerDeathEvent) = clear(event.entity)

    override fun close() {
        tasks.close()
        sessions.values.forEach(::clearVisuals)
        sessions.clear()
        titleAfter.clear()
    }
}
