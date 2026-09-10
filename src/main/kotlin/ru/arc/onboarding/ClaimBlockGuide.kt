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
import org.bukkit.event.player.PlayerTeleportEvent
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
    private val particles = ClaimGuideParticles.create()
    private val sessions = mutableMapOf<UUID, Session>()
    private val views = mutableMapOf<UUID, ClaimGuideView>()
    private val failedViewers = mutableSetOf<UUID>()
    private val text = OnboardingConfig.CLAIM_TEXT.keys.associateWith(config::claimText)
    private var tick = 0L

    private class Session(val world: UUID) {
        val borders = mutableMapOf<GuideBorder, BlockDisplay>()
        val posts = mutableMapOf<GuideChunk, BlockDisplay>()
        val walls = mutableMapOf<GuideBorder, BlockDisplay>()
        var label: TextDisplay? = null
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
                        session.anchor = claimGuideAnchor(session.anchor, player.eyeLocation, player.isSneaking)
                    }
                    if (tick == 1L || tick % 5L == 0L) update(player)
                    sessions[player.uniqueId]?.let { session ->
                        val eye = session.anchor ?: player.eyeLocation
                        val view = view(player)
                        followClaimGuideDisplay(session.label, claimGuideLabelLocation(eye, view.labelOffset))
                        val y = claimGuideBorderY(player.eyeLocation.y, view.snapBlocks, view.gridOffset)
                        if (session.borderY != y) {
                            (session.borders.values + session.posts.values + session.walls.values).forEach {
                                val destination = it.location.apply { this.y = y }
                                if (view.snapBlocks > 0) {
                                    it.teleportDuration = 0
                                    it.teleport(destination)
                                } else {
                                    followClaimGuideDisplay(it, destination)
                                }
                            }
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
        particles?.holding(player.uniqueId, holding)
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
        val view = view(player)
        val gridY = claimGuideBorderY(player.eyeLocation.y, view.snapBlocks, view.gridOffset)
        val visibleRadius = maxOf(session.radius, view.gridRadius)
        val visible = claimGuideChunks(currentChunk, visibleRadius)
        val nearby = buildMap {
            for (x in currentChunk.x - visibleRadius - 1..currentChunk.x + visibleRadius + 1)
                for (z in currentChunk.z - visibleRadius - 1..currentChunk.z + visibleRadius + 1) {
                    put(GuideChunk(x, z), integration.getLandByUnloadedChunk(world, x, z))
                }
        }
        val plan = claimGuideBorders(visible, nearby.mapValues { it.value?.ulid?.toString() }).toSet()
        val iterator = session.borders.iterator()
        while (iterator.hasNext()) {
            val (key, display) = iterator.next()
            if (key !in plan || !display.isValid) { display.remove(); iterator.remove() }
        }
        val wallIterator = session.walls.iterator()
        while (wallIterator.hasNext()) {
            val (border, display) = wallIterator.next()
            if (!config.claimGuideLandWallsEnabled || border !in plan || border.landId == null || !display.isValid) {
                display.remove()
                wallIterator.remove()
            }
        }
        val corners = claimGuideIntersections(visible)
        val postIterator = session.posts.iterator()
        while (postIterator.hasNext()) {
            val (corner, display) = postIterator.next()
            if (!view.showPosts || corner !in corners || !display.isValid) { display.remove(); postIterator.remove() }
        }
        if (view.showPosts) corners.forEach { corner ->
            session.posts.getOrPut(corner) { drawPost(player, corner, gridY) }
        }
        val landsById = nearby.values.filterNotNull().associateBy { it.ulid.toString() }
        for (border in plan) {
            val land = border.landId?.let(landsById::get)
                ?: nearby[GuideChunk(border.edge.x shr 4, border.edge.z shr 4)]
            val material = when {
                land == null -> Material.LIGHT_GRAY_CONCRETE
                land.ownerUID == player.uniqueId || player.uniqueId in land.trustedPlayers -> Material.LIME_CONCRETE
                else -> Material.RED_CONCRETE
            }
            val appearance = if (land == null) view.gridColor.appearance else ClaimGuideGridAppearance(
                material,
                claimGuideBorderColor(material),
            )
            val display = session.borders.getOrPut(border) { drawBorder(player, border, appearance, gridY) }
            if (display.block.material != appearance.material || display.glowColorOverride != appearance.glow) {
                display.block = appearance.material.createBlockData()
                display.glowColorOverride = appearance.glow
            }
            if (config.claimGuideLandWallsEnabled && border.landId != null) {
                val wallAppearance = claimGuideWallAppearance(material)
                val wall = session.walls.getOrPut(border) { drawWall(player, border, wallAppearance, gridY) }
                if (wall.block.material != wallAppearance.material || wall.glowColorOverride != wallAppearance.glow) {
                    wall.block = wallAppearance.material.createBlockData()
                    wall.glowColorOverride = wallAppearance.glow
                }
            }
        }
        val eye = session.anchor ?: player.eyeLocation
        val labelLocation = claimGuideLabelLocation(eye, view.labelOffset)
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
        followClaimGuideDisplay(label, labelLocation)
        val message = claimGuideLandText(text.getValue(state), selected?.name?.take(24))
        label.text(if (LandsUiModule.isAvailable()) message.append(Component.newline()).append(text.getValue("menu")) else message)
    }

    fun isMenuTarget(player: Player): Boolean = player.isSneaking &&
        sessions[player.uniqueId]?.label?.takeIf { it.isValid }
            ?.let { claimGuideButtonHit(player.eyeLocation, it.location, halfWidth = 3.8, height = 1.3) } == true

    @EventHandler(priority = EventPriority.HIGHEST)
    fun clickButton(event: PlayerInteractEvent) {
        val player = event.player
        // Only a Shift-click on a panel consumes placement; other right clicks still place blocks.
        if (RegionToolItem.matches(player.inventory.itemInMainHand)) return
        if (!claimGuideButtonGesture(event.action, event.hand, player.isSneaking)) return
        if (!ClaimBlockIdentity.matches(player.inventory.itemInMainHand) &&
            !ClaimBlockIdentity.matches(player.inventory.itemInOffHand)) return
        val session = sessions[player.uniqueId] ?: return
        if (tick < session.clickAfter || !LandsUiModule.isAvailable()) return
        if (!isMenuTarget(player)) return
        event.isCancelled = true
        session.clickAfter = tick + 10
        LandsUiModule.openCurrent(player)
    }

    private fun drawBorder(
        player: Player,
        border: GuideBorder,
        appearance: ClaimGuideGridAppearance,
        y: Double,
    ): BlockDisplay {
        // Each entity lives on its world edge. Sliding the visible window never moves retained lines.
        return player.world.spawn(borderLocation(player.world, border.edge, y), BlockDisplay::class.java) {
            configure(it)
            it.block = appearance.material.createBlockData()
            it.teleportDuration = 0
            positionBorder(it, border, y)
            it.isGlowing = true
            it.glowColorOverride = appearance.glow
        }.also { player.showEntity(ARC.instance, it) }
    }

    private fun positionBorder(display: BlockDisplay, border: GuideBorder, y: Double) {
        val edge = border.edge
        val width = if (border.landId == null) 0.05f else 0.14f
        val height = if (border.landId == null) 0.045f else 0.20f
        display.teleportDuration = 0
        display.teleport(borderLocation(display.world, edge, y))
        display.setTransformationMatrix(Matrix4f().translation(
            -if (edge.alongX) 0f else width / 2,
            -height / 2,
            -if (edge.alongX) width / 2 else 0f,
        ).scale(if (edge.alongX) 16f else width, height, if (edge.alongX) width else 16f))
    }

    private fun borderLocation(world: org.bukkit.World, edge: GuideEdge, y: Double): Location =
        Location(world, edge.x.toDouble(), y, edge.z.toDouble())

    private fun claimGuideBorderColor(material: Material): Color = when (material) {
        Material.RED_CONCRETE -> Color.RED
        Material.LIME_CONCRETE -> Color.LIME
        else -> Color.fromRGB(165, 190, 205)
    }

    private fun claimGuideWallAppearance(material: Material): ClaimGuideGridAppearance = when (material) {
        Material.RED_CONCRETE -> ClaimGuideGridAppearance(Material.RED_STAINED_GLASS, Color.RED)
        else -> ClaimGuideGridAppearance(Material.LIME_STAINED_GLASS, Color.LIME)
    }

    private fun drawWall(
        player: Player,
        border: GuideBorder,
        appearance: ClaimGuideGridAppearance,
        y: Double,
    ): BlockDisplay = player.world.spawn(borderLocation(player.world, border.edge, y), BlockDisplay::class.java) {
        configure(it)
        it.block = appearance.material.createBlockData()
        it.teleportDuration = 0
        positionWall(it, border, y)
        it.isGlowing = true
        it.glowColorOverride = appearance.glow
    }.also { player.showEntity(ARC.instance, it) }

    private fun positionWall(display: BlockDisplay, border: GuideBorder, y: Double) {
        val edge = border.edge
        val thickness = 0.04f
        display.teleportDuration = 0
        display.teleport(borderLocation(display.world, edge, y))
        display.setTransformationMatrix(Matrix4f().translation(
            -if (edge.alongX) 0f else thickness / 2,
            0f,
            -if (edge.alongX) thickness / 2 else 0f,
        ).scale(if (edge.alongX) 16f else thickness, 3.0f, if (edge.alongX) thickness else 16f))
    }

    private fun drawPost(player: Player, corner: GuideChunk, y: Double): BlockDisplay {
        return player.world.spawn(postLocation(player.world, corner, y), BlockDisplay::class.java) {
            configure(it)
            it.block = view(player).gridColor.appearance.material.createBlockData()
            it.teleportDuration = 0
            positionPost(it, corner, y)
            it.isGlowing = true
            it.glowColorOverride = view(player).gridColor.appearance.glow
        }.also { player.showEntity(ARC.instance, it) }
    }

    private fun positionPost(display: BlockDisplay, corner: GuideChunk, y: Double) {
        display.teleportDuration = 0
        display.teleport(postLocation(display.world, corner, y))
        display.setTransformationMatrix(Matrix4f().translation(
            -0.025f, 0f, -0.025f,
        ).scale(0.05f, 8.0f, 0.05f))
    }

    private fun postLocation(world: org.bukkit.World, corner: GuideChunk, y: Double): Location =
        Location(world, corner.x * 16.0, y, corner.z * 16.0)

    private fun configure(display: Display) {
        display.isPersistent = false
        display.isVisibleByDefault = false
        display.setGravity(false)
        display.brightness = Display.Brightness(15, 15)
        display.viewRange = 2.5f
    }

    fun view(player: Player): ClaimGuideView = views[player.uniqueId] ?: ClaimGuideView()

    fun adjustView(
        player: Player,
        gridSteps: Int,
        labelSteps: Int,
        radiusSteps: Int,
        cycleSnap: Boolean,
        cycleColor: Boolean,
        togglePosts: Boolean,
        reset: Boolean,
    ) {
        val before = view(player)
        val after = if (reset) ClaimGuideView() else before.adjust(
            grid = gridSteps,
            label = labelSteps,
            radius = radiusSteps,
            cycleSnap = cycleSnap,
            cycleColor = cycleColor,
            togglePosts = togglePosts,
        )
        views[player.uniqueId] = after
        sessions[player.uniqueId]?.let { session ->
            session.borderY = Double.NaN
            if (before.gridColor != after.gridColor || before.showPosts != after.showPosts) clearGrid(session)
        }
        update(player)
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
        clearGrid(session)
        session.label?.remove()
        session.label = null
    }

    private fun clearGrid(session: Session) {
        session.borders.values.forEach(Entity::remove)
        session.borders.clear()
        session.posts.values.forEach(Entity::remove)
        session.posts.clear()
        session.walls.values.forEach(Entity::remove)
        session.walls.clear()
    }

    fun hasHologram(player: Player): Boolean = sessions[player.uniqueId]?.label?.isValid == true

    private fun clear(player: Player) {
        particles?.holding(player.uniqueId, false)
        sessions.remove(player.uniqueId)?.let {
            clearVisuals(it)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun sneak(event: org.bukkit.event.player.PlayerToggleSneakEvent) {
        if (event.isSneaking) sessions[event.player.uniqueId]?.let { freezeClaimGuideDisplay(it.label) }
    }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        clear(event.player)
        views.remove(event.player.uniqueId)
        failedViewers.remove(event.player.uniqueId)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun teleported(event: PlayerTeleportEvent) {
        clear(event.player)
        tasks.runLater(1L) { if (event.player.isOnline) update(event.player) }
    }
    @EventHandler fun worldChanged(event: PlayerChangedWorldEvent) = clear(event.player)
    @EventHandler fun died(event: PlayerDeathEvent) = clear(event.entity)

    override fun close() {
        tasks.close()
        particles?.close()
        sessions.values.forEach(::clearVisuals)
        sessions.clear()
        views.clear()
        failedViewers.clear()
    }
}

data class ClaimGuideGridAppearance(val material: Material, val glow: Color)

enum class ClaimGuideGridColor(val appearance: ClaimGuideGridAppearance) {
    ICE(ClaimGuideGridAppearance(Material.LIGHT_BLUE_CONCRETE, Color.fromRGB(120, 210, 255))),
    WHITE(ClaimGuideGridAppearance(Material.WHITE_CONCRETE, Color.WHITE)),
    PURPLE(ClaimGuideGridAppearance(Material.PURPLE_CONCRETE, Color.fromRGB(205, 135, 255))),
    GOLD(ClaimGuideGridAppearance(Material.YELLOW_CONCRETE, Color.fromRGB(255, 205, 75))),
    GRAY(ClaimGuideGridAppearance(Material.LIGHT_GRAY_CONCRETE, Color.fromRGB(165, 190, 205)));

    fun next(): ClaimGuideGridColor = entries[(ordinal + 1) % entries.size]
}

data class ClaimGuideView(
    val gridSteps: Int = 0,
    val labelSteps: Int = 0,
    val gridRadius: Int = 2,
    val snapBlocks: Int = 2,
    val gridColor: ClaimGuideGridColor = ClaimGuideGridColor.ICE,
    val showPosts: Boolean = false,
) {
    val gridOffset: Double get() = gridSteps.toDouble()
    val labelOffset: Double get() = labelSteps * 0.5

    fun adjust(
        grid: Int,
        label: Int,
        radius: Int = 0,
        cycleSnap: Boolean = false,
        cycleColor: Boolean = false,
        togglePosts: Boolean = false,
    ): ClaimGuideView =
        copy(
            gridSteps = (gridSteps + grid).coerceIn(-12, 12),
            labelSteps = (labelSteps + label).coerceIn(-20, 20),
            gridRadius = (gridRadius + radius).coerceIn(1, 5),
            snapBlocks = if (cycleSnap) when (snapBlocks) { 0 -> 1; 1 -> 2; 2 -> 3; else -> 0 } else snapBlocks,
            gridColor = if (cycleColor) gridColor.next() else gridColor,
            showPosts = if (togglePosts) !showPosts else showPosts,
        )
}
