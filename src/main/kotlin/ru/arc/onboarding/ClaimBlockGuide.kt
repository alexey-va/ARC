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
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.joml.Matrix4f
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
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
        var geometry: Any? = null
        var successUntil = 0L
        val confirmed = linkedSetOf<GuideChunk>()
        var aimed: GuideChunk? = null
        var radius = 0
    }

    fun start() {
        tasks.runTimer(1L, 5L) {
            tick += 5
            Bukkit.getOnlinePlayers().forEach { player ->
                try {
                    update(player)
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
            session = Session(player.world.uid)
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
        if (placement == null && !success) {
            clearVisuals(session)
            effects.sendActionBar(player, text.getValue("aim"))
            return
        }
        val target = if (success) session.aimed?.takeIf { it in session.confirmed } ?: session.confirmed.first()
            else GuideChunk(placement!!.x shr 4, placement.z shr 4)
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
        val y = (placement?.y ?: player.location.blockY).coerceIn(world.minHeight, world.maxHeight - 1)
        val ownChunks = linkedSetOf<GuideChunk>()
        // Bounded local view of the selected region; never load terrain to draw a guide.
        if (selected != null) for (x in target.x - 1..target.x + 1) for (z in target.z - 1..target.z + 1) {
            if (world.isChunkLoaded(x, z) && integration.getLandByUnloadedChunk(world, x, z) == selected) {
                ownChunks += GuideChunk(x, z)
            }
        }
        val geometry = listOf(footprint, y, state, ownChunks, claims)
        if (session.geometry != geometry || session.borders.any { !it.isValid }) {
            session.borders.forEach(Entity::remove)
            session.borders.clear()
            session.geometry = geometry
            // Remove artificial edges at the local view's cutoff using real adjacent Lands claims.
            if (selected != null) {
                claimGuideEdges(ownChunks).filter { edge ->
                    val outside = if (edge.alongX) {
                        val north = GuideChunk(edge.x shr 4, (edge.z shr 4) - 1)
                        if (north in ownChunks) GuideChunk(north.x, north.z + 1) else north
                    } else {
                        val west = GuideChunk((edge.x shr 4) - 1, edge.z shr 4)
                        if (west in ownChunks) GuideChunk(west.x + 1, west.z) else west
                    }
                    integration.getLandByUnloadedChunk(world, outside.x, outside.z) != selected
                }.forEach { drawEdge(player, session, it, y, Material.LIGHT_BLUE_CONCRETE, ownChunks) }
            }
            claims.entries.groupBy { (_, land) ->
                when {
                    land == null -> Material.LIME_CONCRETE
                    land.ownerUID == player.uniqueId -> Material.LIGHT_BLUE_CONCRETE
                    else -> Material.RED_CONCRETE
                }
            }.forEach { (color, entries) ->
                val interior = entries.mapTo(linkedSetOf()) { it.key }
                claimGuideEdges(interior).forEach { drawEdge(player, session, it, y, color, interior) }
            }
        }
        val labelLocation = if (success) {
            Location(world, target.x * 16 + 8.0, y + 1.7, target.z * 16 + 8.0)
        } else placement!!.location.add(0.5, 1.6, 0.5)
        val label = session.label?.takeIf { it.isValid } ?: world.spawn(labelLocation, TextDisplay::class.java) {
            configure(it)
            it.billboard = Display.Billboard.CENTER
            it.isSeeThrough = true
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(190, 15, 23, 30)
            it.lineWidth = 230
        }.also { session.label = it; player.showEntity(ARC.instance, it) }
        if (label.location.distanceSquared(labelLocation) > 0.01) label.teleport(labelLocation)
        label.text(text.getValue(state))
        if (tick % 20L == 0L) {
            val action = when {
                success || tick % 160L >= 100L -> "remove"
                state == "own" -> "action-own"
                state == "occupied" -> "action-occupied"
                else -> "action-free"
            }
            effects.sendActionBar(player, text.getValue(action))
        }
    }

    private fun drawEdge(player: Player, session: Session, edge: GuideEdge, baseY: Int, material: Material, interior: Set<GuideChunk>) {
        // Four short segments follow nearby terrain and remain visible over small slopes.
        for (offset in 0 until 16 step 4) {
            val x = edge.x + if (edge.alongX) offset else 0
            val z = edge.z + if (edge.alongX) 0 else offset
            // Both the terrain sample and entity origin stay on the interior side of this edge.
            val positiveSide = GuideChunk(edge.x shr 4, edge.z shr 4) in interior
            val sampleX = x + if (edge.alongX) 2 else if (positiveSide) 0 else -1
            val sampleZ = z + if (edge.alongX) { if (positiveSide) 0 else -1 } else 2
            val world = player.world
            if (!world.isChunkLoaded(sampleX shr 4, sampleZ shr 4)) continue
            val top = (baseY + 8).coerceAtMost(world.maxHeight - 1)
            val bottom = (baseY - 12).coerceAtLeast(world.minHeight)
            val y = (top downTo bottom).firstOrNull { !world.getBlockAt(sampleX, it, sampleZ).isPassable }
                ?.plus(1)?.toDouble() ?: baseY.toDouble()
            val inset = if (positiveSide) 0.03 else -0.13
            val location = Location(world, x + if (edge.alongX) 0.03 else inset,
                y + 0.06, z + if (edge.alongX) inset else 0.03)
            // The entity's origin must also remain in an already loaded chunk.
            if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) continue
            val display = world.spawn(location, BlockDisplay::class.java) {
                configure(it)
                it.block = material.createBlockData()
                it.setTransformationMatrix(Matrix4f().scaling(if (edge.alongX) 3.94f else 0.10f, 0.10f, if (edge.alongX) 0.10f else 3.94f))
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
