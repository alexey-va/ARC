package ru.arc.landsui

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.land.LandArea
import me.angeschossen.lands.api.player.Selection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
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
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.joml.Matrix4f
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.gui.ArcMenus
import ru.arc.lands.currentLands
import ru.arc.onboarding.claimGuideButtonHit
import ru.arc.onboarding.claimGuideAnchor
import ru.arc.onboarding.freezeClaimGuideDisplay
import ru.arc.onboarding.followClaimGuideDisplay
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.onboarding.claimGuideButtonLocation
import ru.arc.onboarding.claimGuideLabelLocation
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.util.Logging.error
import java.util.UUID

/** Personal selection preview. Lands alone performs region creation and permission enforcement. */
internal class RegionTool(private val settings: LandsUiSettings, private val gateway: LandsUiGateway) : Listener, AutoCloseable {
    private val lands = LandsIntegration.of(ARC.instance)
    private val tasks = LifecycleTaskScope()
    private val mini = MiniMessage.miniMessage()
    private val sessions = mutableMapOf<UUID, Session>()
    private var tick = 0L
    private val failedViewers = mutableSetOf<UUID>()
    private val nativeSelections = mutableMapOf<UUID, () -> Unit>()
    private class Session(val world: UUID, val landId: String) {
        var first: RegionPoint? = null
        var second: RegionPoint? = null
        var revision = 0
        var anchor: Location? = null
        var label: TextDisplay? = null
        var button: TextDisplay? = null
        val lines = mutableListOf<Entity>()
        var geometry: Any? = null
        var y = Double.NaN
        var clickAfter = 0L
        var submitting = false
        fun box(): RegionBox? = first?.let { a -> second?.let { RegionBox.between(a, it) } }
    }

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        tasks.runTimer(1, 1) {
            tick++
            Bukkit.getOnlinePlayers().forEach { player ->
                try {
                    if (player.uniqueId in failedViewers) return@forEach
                    if (tick % 5 == 0L) update(player)
                    sessions[player.uniqueId]?.let { session ->
                        session.anchor = claimGuideAnchor(session.anchor, player.eyeLocation, player.isSneaking)
                        val eye = session.anchor!!
                        followClaimGuideDisplay(session.label, claimGuideLabelLocation(eye))
                        followClaimGuideDisplay(session.button, claimGuideButtonLocation(eye))
                    }
                } catch (failure: Exception) {
                    clear(player)
                    failedViewers += player.uniqueId
                    error("Region tool preview failed for {}; disabled until reconnect", player.name, failure)
                }
            }
        }
    }

    fun give(player: Player, landId: String) {
        if (!gateway.select(player, landId)) return player.sendMessage(text("land-gone"))
        val inventory = player.inventory
        val existing = (0..35).firstOrNull { RegionToolItem.matches(inventory.getItem(it)) }
        val slot = existing ?: inventory.firstEmpty().takeIf { it in 0..35 }
        if (slot == null) return player.sendMessage(text("region-inventory-full"))
        if (existing == null) inventory.setItem(slot, RegionToolItem.create(settings))
        // Equip without discarding the previous held item.
        val held = inventory.heldItemSlot
        if (slot != held) {
            val previous = inventory.getItem(held)
            inventory.setItem(held, inventory.getItem(slot))
            inventory.setItem(slot, previous)
        }
        clear(player)
        update(player)
        player.sendMessage(text("region-equipped"))
    }

    private fun selected(player: Player): Land? {
        val lp = lands.getLandPlayer(player.uniqueId) ?: return null
        val land = lp.getEditLand(false)?.takeIf { it.exists() } ?: return null
        return land.takeIf { lp.currentLands().any { member -> member.ulid == land.ulid } }
    }

    private fun update(player: Player) {
        if (!RegionToolItem.matches(player.inventory.itemInMainHand)) {
            sessions[player.uniqueId]?.let(::hide)
            return
        }
        if (player.isDead || player.gameMode == GameMode.SPECTATOR) { clear(player); return }
        val land = selected(player)
        if (land == null || lands.getWorld(player.world) == null) {
            clear(player)
            if (tick % 20 == 0L) player.sendActionBar(text("region-select-land"))
            return
        }
        var session = sessions[player.uniqueId]
        if (session?.world != player.world.uid || session.landId != land.ulid.toString()) {
            clear(player)
            session = Session(player.world.uid, land.ulid.toString()).also { sessions[player.uniqueId] = it }
        }
        val eye = session.anchor ?: player.eyeLocation.also { session.anchor = it }
        val hit = player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER)?.hitBlock
        val aim = hit?.let { RegionPoint(it.x, it.z) }
        val preview = session.box() ?: session.first?.let { first -> aim?.let { RegionBox.between(first, it) } }
        val valid = preview == null || insideLand(land, player, preview)
        val viewer = RegionPoint(player.location.blockX, player.location.blockZ)
        val existing = land.allAreas.asSequence().filterIsInstance<LandArea>()
            .filter { !it.isDefault && it.isSetup && it.world == player.world }
            .mapNotNull { area -> area.boundingBox?.let { box -> RegionBox(box.min.x, box.min.z, box.max.x, box.max.z) } }
            .flatMap { regionToolLines(it, viewer).asSequence() }.take(64).toList()
        val pending = preview?.let { regionToolFrame(it, viewer) }.orEmpty()
        val geometry = listOf(existing, pending, valid)
        if (session.geometry != geometry) {
            session.lines.forEach(Entity::remove)
            session.lines.clear()
            existing.forEach { draw(player, session, RegionFrameEdge(it.x, it.z, 0.0, it.length,
                if (it.alongX) RegionFrameAxis.X else RegionFrameAxis.Z), Material.LIGHT_BLUE_CONCRETE, 0.05f) }
            pending.forEach { draw(player, session, it, if (valid) Material.LIME_CONCRETE else Material.RED_CONCRETE, 0.08f) }
            session.geometry = geometry
            session.y = player.eyeLocation.y
        }
        if (session.y != player.eyeLocation.y) {
            session.lines.forEach { it.teleport(it.location.apply { y = player.eyeLocation.y }) }
            session.y = player.eyeLocation.y
        }
        val area = (hit?.let { land.getArea(it.location) } ?: land.getArea(player.location))?.takeUnless { it.isDefault }
        val key = when {
            session.submitting -> "region-working"
            !valid -> "region-outside"
            session.second != null -> "region-ready"
            session.first != null -> "region-second"
            area != null -> "region-current"
            else -> "region-first"
        }
        val label = session.label?.takeIf { it.isValid } ?: display(player, claimGuideLabelLocation(eye), 1.30f, 230).also { session.label = it }
        label.text(text(key, "land" to short(land.name), "area" to short(area?.name.orEmpty()),
            "width" to (preview?.width ?: 0).toString(), "depth" to (preview?.depth ?: 0).toString()))
        val button = session.button?.takeIf { it.isValid } ?: display(player, claimGuideButtonLocation(eye), 1.10f, 160).also { session.button = it }
        button.text(text(if (session.box() != null && valid) "region-create-button" else "region-list-button"))
    }

    private fun insideLand(land: Land, player: Player, box: RegionBox): Boolean =
        // A filled rectangle cannot fit if it spans more chunks than the entire land owns.
        box.chunkCount <= land.chunksAmount && box.chunks().all { (x, z) ->
            lands.getLandByUnloadedChunk(player.world, x, z)?.ulid == land.ulid
        }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun sneak(event: org.bukkit.event.player.PlayerToggleSneakEvent) {
        if (event.isSneaking) sessions[event.player.uniqueId]?.let {
            freezeClaimGuideDisplay(it.label)
            freezeClaimGuideDisplay(it.button)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun interact(event: PlayerInteractEvent) {
        val player = event.player
        if (!RegionToolItem.matches(player.inventory.itemInMainHand)) return
        if (event.action == Action.PHYSICAL) return
        event.isCancelled = true // This tool selects corners; never breaks blocks or uses the offhand.
        if (event.hand != EquipmentSlot.HAND) return
        val land = selected(player) ?: return player.sendMessage(text("region-select-land"))
        val session = sessions[player.uniqueId] ?: return
        if (session.submitting || session.world != player.world.uid || session.landId != land.ulid.toString()) return
        val right = event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK
        if (player.isSneaking) {
            val hitButton = session.button?.takeIf { it.isValid }
                ?.let { claimGuideButtonHit(player.eyeLocation, it.location) } == true
            if (hitButton) {
                if (tick >= session.clickAfter) {
                    session.clickAfter = tick + 10
                    ArcMenus.beginDialogFlow(player)
                    val box = session.box()
                    if (box != null && insideLand(land, player, box)) openCreate(player, session, land, box)
                    else openRegions(player, land.ulid.toString())
                }
            } else if (right) {
                session.first = null; session.second = null; session.revision++
                update(player)
            }
            return
        }
        val block = event.clickedBlock ?: return
        if (lands.getLandByUnloadedChunk(player.world, block.x shr 4, block.z shr 4)?.ulid != land.ulid) {
            session.label?.text(text("region-outside", "land" to short(land.name)))
            return
        }
        if (!land.defaultArea.hasRoleFlag(player, Flags.AREA_ASSIGN, Material.STONE, false)) {
            player.sendMessage(text("region-no-permission")); return
        }
        if (!right || session.first == null) {
            session.first = RegionPoint(block.x, block.z)
            session.second = null
        } else session.second = RegionPoint(block.x, block.z)
        session.revision++
        update(player)
    }

    private fun openCreate(player: Player, session: Session, land: Land, box: RegionBox) {
        val revision = session.revision
        val input = PaperDialogInputId.of("region_name")
        show(player, PaperDialogScreen(
            id = "lands.region-create",
            title = text("region-create-title"),
            body = listOf(PaperDialogBody(text("region-create-body", "land" to land.name,
                "width" to box.width.toString(), "depth" to box.depth.toString()), width = 420)),
            inputs = listOf(PaperDialogTextInput(input, text("region-name-input"), maxLength = 24)),
            buttons = listOf(PaperDialogButton(
                id = PaperDialogActionId.of("create_region"), label = text("region-confirm"), width = 220,
                closeDialogBeforeAction = true,
                onClick = { context ->
                    val name = runCatching { regionToolName(context.text(input).orEmpty()) }.getOrNull()
                    if (name == null) {
                        player.sendMessage(text("region-invalid-name"))
                        openCreate(player, session, land, box)
                    } else create(player, session, revision, name, box)
                },
            )),
            exitButton = action("cancel_region", "close-label") {}, columns = 1,
        ))
    }

    private fun create(player: Player, session: Session, revision: Int, name: String, box: RegionBox) {
        val land = selected(player)
        if (sessions[player.uniqueId] !== session || session.revision != revision || session.submitting ||
            session.world != player.world.uid || land == null || land.ulid.toString() != session.landId || !insideLand(land, player, box)) {
            player.sendMessage(text("region-stale")); return
        }
        if (land.getArea(name) != null) { player.sendMessage(text("region-name-used")); return }
        if (!land.defaultArea.hasRoleFlag(player, Flags.AREA_ASSIGN, Material.STONE, false)) {
            player.sendMessage(text("region-no-permission")); return
        }
        if (!player.hasPermission("lands.command.area.assign") || !player.hasPermission("lands.command.area.create")) {
            player.sendMessage(text("region-no-permission")); return
        }
        if (player.uniqueId in nativeSelections) { player.sendMessage(text("region-working")); return }
        val lp = lands.getLandPlayer(player.uniqueId) ?: return
        val previous = lp.selection
        val selection = previous ?: Selection.of(lp, false, false, false)
        val oldFirst = previous?.pos1?.toLocation()
        val oldSecond = previous?.pos2?.toLocation()
        val first = Location(player.world, box.minX.toDouble(), player.world.minHeight.toDouble(), box.minZ.toDouble())
        val second = Location(player.world, box.maxX.toDouble(), (player.world.maxHeight - 1).toDouble(), box.maxZ.toDouble())
        selection.setPos1(first)
        selection.setPos2(second)
        // Restore only our unchanged selection; never overwrite a later selection made by the player.
        nativeSelections[player.uniqueId] = {
            if (lp.selection === selection && selection.pos1?.toLocation() == first && selection.pos2?.toLocation() == second) {
                if (previous == null) selection.disable()
                else { selection.setPos1(oldFirst); selection.setPos2(oldSecond) }
            }
        }
        session.submitting = true
        try {
            if (player.performCommand("lands area $name assign")) verifyCreated(player, session, land, name, box, 0)
            else {
                finishSelection(player.uniqueId)
                session.submitting = false
                player.sendMessage(text("region-native-failed"))
            }
        } catch (failure: Exception) {
            finishSelection(player.uniqueId)
            session.submitting = false
            throw failure
        }
    }

    private fun finishSelection(playerId: UUID) { nativeSelections.remove(playerId)?.invoke() }

    private fun verifyCreated(player: Player, session: Session, land: Land, name: String, box: RegionBox, attempt: Int) {
        tasks.runLater(5) {
            val area = land.getArea(name) as? LandArea
            val bounds = area?.takeIf { it.isSetup && it.world?.uid == session.world }?.boundingBox
            val created = bounds != null && bounds.min.x == box.minX && bounds.min.z == box.minZ &&
                bounds.max.x == box.maxX && bounds.max.z == box.maxZ
            if (created || attempt >= 39) {
                finishSelection(player.uniqueId)
                session.submitting = false
                if (created) {
                    session.first = null; session.second = null; session.revision++
                    if (player.isOnline) player.sendMessage(text("region-created", "area" to name, "land" to land.name))
                    if (player.isOnline && sessions[player.uniqueId] === session) update(player)
                } else {
                    // The native command has no completion API. Require a fresh selection after an unknown outcome.
                    session.first = null; session.second = null; session.revision++
                    if (player.isOnline) player.sendMessage(text("region-native-pending"))
                }
            } else verifyCreated(player, session, land, name, box, attempt + 1)
        }
    }

    private fun openRegions(player: Player, landId: String, page: Int = 0) {
        val land = selected(player)?.takeIf { it.ulid.toString() == landId } ?: return
        val areas = land.allAreas.filterIsInstance<LandArea>().filter { !it.isDefault }.sortedBy { it.name }
        val buttons = areas.drop(page * 8).take(8).mapIndexed { index, area ->
            PaperDialogButton(PaperDialogActionId.of("region_$index"), text("region-entry", "area" to area.name),
                width = 220, closeDialogBeforeAction = true, onClick = {
                    val name = runCatching { regionToolName(area.name) }.getOrNull()
                    gateway.selectAndExecute(player, landId, if (name == null) "lands menu" else "lands area $name menu")
                })
        }.toMutableList()
        if (page > 0) buttons += action("previous_regions", "region-previous", close = false) { openRegions(player, landId, page - 1) }
        if (areas.size > (page + 1) * 8) buttons += action("next_regions", "region-next", close = false) { openRegions(player, landId, page + 1) }
        show(player, PaperDialogScreen(id = "lands.regions", title = text("region-list-title", "land" to land.name),
            body = listOf(PaperDialogBody(text(if (areas.isEmpty()) "region-list-empty" else "region-list-body"))),
            buttons = buttons, exitButton = action("region_close", "close-label") {}, columns = 2))
    }

    private fun display(player: Player, at: Location, scale: Float, width: Int): TextDisplay =
        player.world.spawn(at, TextDisplay::class.java) {
            configure(it)
            it.billboard = Display.Billboard.CENTER
            it.isSeeThrough = true
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(255, 15, 23, 30)
            it.lineWidth = width
            it.teleportDuration = 2
            it.setTransformationMatrix(Matrix4f().scaling(scale))
        }.also { player.showEntity(ARC.instance, it) }

    private fun draw(player: Player, session: Session, line: RegionFrameEdge, material: Material, thickness: Float) {
        // Keep the entity in the viewer's already-loaded chunk; translate just its display geometry.
        val origin = player.location.apply { y = player.eyeLocation.y; yaw = 0f; pitch = 0f }
        session.lines += player.world.spawn(origin, BlockDisplay::class.java) {
            configure(it)
            it.block = material.createBlockData()
            it.isGlowing = true
            it.glowColorOverride = when (material) {
                Material.LIME_CONCRETE -> Color.LIME
                Material.RED_CONCRETE -> Color.RED
                else -> Color.AQUA
            }
            it.setTransformationMatrix(Matrix4f().translation((line.x - origin.x).toFloat(),
                line.yOffset.toFloat() - thickness / 2, (line.z - origin.z).toFloat())
                .scale(if (line.axis == RegionFrameAxis.X) line.length.toFloat() else thickness,
                    if (line.axis == RegionFrameAxis.Y) line.length.toFloat() else thickness,
                    if (line.axis == RegionFrameAxis.Z) line.length.toFloat() else thickness))
        }.also { player.showEntity(ARC.instance, it) }
    }

    private fun configure(display: Display) {
        display.isPersistent = false; display.isVisibleByDefault = false; display.setGravity(false)
        display.brightness = Display.Brightness(15, 15); display.viewRange = 1.5f
    }
    private fun text(key: String, vararg values: Pair<String, String>): Component =
        mini.deserialize(settings.text(key), *values.map { Placeholder.component(it.first, Component.text(it.second)) }.toTypedArray())
            .decoration(TextDecoration.ITALIC, false)
    private fun short(name: String) = if (name.length > 24) name.take(23) + "…" else name
    private fun action(id: String, key: String, close: Boolean = true, run: () -> Unit) = PaperDialogButton(PaperDialogActionId.of(id), text(key), width = 220,
        closeDialogBeforeAction = close, onClick = { run() })
    private fun show(player: Player, screen: PaperDialogScreen) = ArcMenus.openDialog(player, screen,
        closeButton = action("close", "close-label") {})
    private fun hide(session: Session) {
        session.label?.remove(); session.label = null
        session.button?.remove(); session.button = null
        session.lines.forEach(Entity::remove); session.lines.clear()
        session.geometry = null; session.anchor = null
    }
    private fun clear(player: Player) { sessions.remove(player.uniqueId)?.let(::hide) }
    @EventHandler fun quit(event: PlayerQuitEvent) { clear(event.player); failedViewers.remove(event.player.uniqueId) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun teleported(event: PlayerTeleportEvent) {
        sessions[event.player.uniqueId]?.let(::hide)
        tasks.runLater(1L) { if (event.player.isOnline) update(event.player) }
    }
    @EventHandler fun world(event: PlayerChangedWorldEvent) = clear(event.player)
    @EventHandler fun death(event: PlayerDeathEvent) = clear(event.entity)
    override fun close() {
        tasks.close(); HandlerList.unregisterAll(this)
        Bukkit.getOnlinePlayers().forEach(::clear)
        sessions.clear()
        nativeSelections.keys.toList().forEach(::finishSelection)
        failedViewers.clear()
    }
}
