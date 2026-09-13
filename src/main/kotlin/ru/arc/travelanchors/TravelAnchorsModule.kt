package ru.arc.travelanchors

import com.jeff_media.customblockdata.CustomBlockData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.ARC
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.ops.OpsItemHandlers
import ru.arc.util.ItemStackDslBuilder
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.itemStack
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

private val ANCHOR_BLOCK_KEY = NamespacedKey("arc", "travel_anchor")
private val ANCHOR_INDEX_KEY = NamespacedKey("arc", "travel_anchor_index")
private val ANCHOR_ITEM_KEY = NamespacedKey("arc", "travel_anchor_item")
private val STAFF_ITEM_KEY = NamespacedKey("arc", "travel_anchor_staff")
private const val USE_PERMISSION = "arc.travel-anchors.use"
private const val PLACE_PERMISSION = "arc.travel-anchors.place"
private const val BREAK_PERMISSION = "arc.travel-anchors.break"
private const val TELEPORT_COOLDOWN_MILLIS = 500L
private val RIGHT_CLICK_ACTIONS = setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)

internal data class AimCandidate<T>(val target: T, val distanceSquared: Double, val dot: Double)

internal fun <T> chooseTravelAnchorTarget(
    candidates: Iterable<AimCandidate<T>>,
    maxDistanceSquared: Double,
    minimumDot: Double,
): AimCandidate<T>? =
    candidates
        .asSequence()
        .filter { it.distanceSquared <= maxDistanceSquared && it.dot >= minimumDot }
        .maxWithOrNull(compareBy<AimCandidate<T>> { it.dot }.thenBy { -it.distanceSquared })

internal fun travelAnchorScale(
    dot: Double,
    minimumVisibleDot: Double,
    minimumScale: Float,
    maximumScale: Float,
): Float {
    val progress = ((dot - minimumVisibleDot) / (1.0 - minimumVisibleDot)).coerceIn(0.0, 1.0)
    return minimumScale + (maximumScale - minimumScale) * progress.toFloat()
}

internal fun travelAnchorTargetMessage(hasAnchorBelow: Boolean, staffHeld: Boolean): String? =
    when {
        hasAnchorBelow -> "target-anchor"
        staffHeld -> "target-staff"
        else -> null
    }

internal fun travelAnchorDisplayDistance(actualDistance: Double, proxyDistance: Double): Double =
    min(actualDistance, proxyDistance)

private data class TravelAnchorPosition(val worldId: UUID, val x: Int, val y: Int, val z: Int) {
    val chunkX: Int get() = x shr 4
    val chunkZ: Int get() = z shr 4

    fun block(): Block? = Bukkit.getWorld(worldId)?.takeIf { it.isChunkLoaded(chunkX, chunkZ) }?.getBlockAt(x, y, z)

    companion object {
        fun of(block: Block) = TravelAnchorPosition(block.world.uid, block.x, block.y, block.z)

        fun of(worldId: UUID, x: Int, y: Int, z: Int) = TravelAnchorPosition(worldId, x, y, z)
    }
}

private data class TravelAnchorSettings(
    val enabled: Boolean,
    val worlds: Set<String>,
    val allowedPlayers: Set<String>,
    val range: Double,
    val maximumTargets: Int,
    val visibleDot: Double,
    val selectionDot: Double,
    val minimumScale: Float,
    val maximumScale: Float,
    val proxyDistance: Double,
    val updateTicks: Long,
    val anchorMaterial: Material,
    val displayMaterial: Material,
    val staffMaterial: Material,
    val anchorModelData: Int,
    val staffModelData: Int,
    private val source: Config,
) {
    fun allowsWorld(name: String): Boolean = worlds.isEmpty() || name in worlds

    fun allowsPlayer(player: Player): Boolean =
        "*" in allowedPlayers ||
            player.name.lowercase(Locale.ROOT) in allowedPlayers ||
            player.uniqueId.toString().lowercase(Locale.ROOT) in allowedPlayers

    fun anchorItem(): ItemStack =
        itemStack(anchorMaterial) {
            configuredItem("anchor", anchorModelData)
        }.withMarker(ANCHOR_ITEM_KEY)

    fun staffItem(): ItemStack =
        itemStack(staffMaterial) {
            configuredItem("staff", staffModelData)
            glowing()
        }.withMarker(STAFF_ITEM_KEY)

    fun message(key: String, vararg replacements: Pair<String, String>): Component {
        var text = source.string("messages.$key", "")
        replacements.forEach { (placeholder, value) -> text = text.replace(placeholder, value) }
        return TextUtil.mm(text, true)
    }

    private fun ItemStackDslBuilder.configuredItem(key: String, modelData: Int) {
        display(source.string("items.$key.name", ""))
        lore(source.stringList("items.$key.lore"))
        if (modelData > 0) modelData(modelData)
        hideAll()
    }
}

private fun ItemStack.withMarker(key: NamespacedKey): ItemStack = apply {
    editMeta { it.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte()) }
}

private fun ItemStack?.hasMarker(key: NamespacedKey): Boolean =
    this?.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BYTE) == true

private object TravelAnchorConfig {
    fun load(dataPath: Path): TravelAnchorSettings {
        val source = ConfigManager.ofModule(dataPath, "teleport-anchors.yml")
        source.mergeMissingFromBundled("modules/teleport-anchors.yml")
        val visibleAngle = source.real("targeting.visible-angle-degrees", 70.0).coerceIn(10.0, 89.0)
        val selectionAngle = source.real("targeting.selection-angle-degrees", 10.0).coerceIn(1.0, visibleAngle)
        return TravelAnchorSettings(
            enabled = source.bool("enabled", false),
            worlds = source.stringList("worlds").toSet(),
            allowedPlayers = source.stringList("allowed-players").mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) },
            range = source.real("targeting.range", 1024.0).coerceIn(8.0, 4096.0),
            maximumTargets = source.integer("targeting.maximum-targets", 24).coerceIn(1, 64),
            visibleDot = cos(Math.toRadians(visibleAngle)),
            selectionDot = cos(Math.toRadians(selectionAngle)),
            minimumScale = source.real("visual.minimum-scale", 1.04).toFloat().coerceIn(1.01f, 6.0f),
            maximumScale = source.real("visual.maximum-scale", 3.0).toFloat().coerceIn(1.01f, 6.0f),
            proxyDistance = source.real("visual.proxy-distance", 48.0).coerceIn(16.0, 96.0),
            updateTicks = source.long("visual.update-ticks", 4L).coerceIn(1L, 20L),
            anchorMaterial = source.material("items.anchor.material", Material.LODESTONE, requireBlock = true),
            displayMaterial = source.material("visual.block-material", Material.LODESTONE, requireBlock = true),
            staffMaterial = source.material("items.staff.material", Material.BLAZE_ROD, requireBlock = false),
            anchorModelData = source.integer("items.anchor.custom-model-data", 0).coerceAtLeast(0),
            staffModelData = source.integer("items.staff.custom-model-data", 0).coerceAtLeast(0),
            source = source,
        ).let { settings ->
            if (settings.maximumScale >= settings.minimumScale) settings
            else settings.copy(maximumScale = settings.minimumScale)
        }
    }

    private fun Config.material(path: String, fallback: Material, requireBlock: Boolean): Material {
        val raw = string(path, fallback.name)
        val material = Material.matchMaterial(raw)?.takeIf { it.isItem && (!requireBlock || it.isBlock) }
        if (material != null) return material
        warn("TRAVEL_ANCHORS phase=CONFIG reason=invalid-material path={} value={} fallback={}", path, raw, fallback)
        return fallback
    }
}

object TravelAnchorsModule : PluginModule, Listener {
    override val name = "TravelAnchors"
    override val priority = 84

    private var settings: TravelAnchorSettings? = null
    private var renderTask: ScheduledTask? = null
    private val anchors = linkedSetOf<TravelAnchorPosition>()
    private val displays = mutableMapOf<UUID, MutableMap<TravelAnchorPosition, BlockDisplay>>()
    private val selectedTargets = mutableMapOf<UUID, TravelAnchorPosition>()
    private val cooldowns = mutableMapOf<UUID, Long>()
    private val pendingTeleports = mutableSetOf<UUID>()

    val isEnabled: Boolean get() = settings?.enabled == true

    override fun init() {
        shutdown()
        val next = TravelAnchorConfig.load(ARC.instance.dataPath)
        settings = next
        if (!next.enabled) {
            info("Travel anchors disabled by configuration")
            return
        }
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        Bukkit.getWorlds().filter { next.allowsWorld(it.name) }.forEach(::loadAnchorIndex)
        Bukkit.getWorlds().flatMap { it.loadedChunks.toList() }.forEach(::loadAnchors)
        renderTask = repeating(next.updateTicks.ticks, delay = 1.ticks) { refreshPlayers() }
        info("Travel anchors enabled: anchors={}, range={}, maximum-targets={}", anchors.size, next.range, next.maximumTargets)
    }

    override fun reload() = init()

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        renderTask?.cancel()
        renderTask = null
        displays.values.flatMap { it.values }.forEach { if (it.isValid) it.remove() }
        displays.clear()
        selectedTargets.clear()
        anchors.clear()
        cooldowns.clear()
        pendingTeleports.clear()
        settings = null
    }

    fun giveKit(player: Player): Int {
        val current = settings ?: return 0
        return OpsItemHandlers.giveStacks(player, listOf(current.anchorItem(), current.staffItem()), dropOverflow = true)
    }

    fun message(key: String, vararg replacements: Pair<String, String>): Component =
        settings?.message(key, *replacements) ?: Component.empty()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun guardPlace(event: BlockPlaceEvent) {
        if (!event.itemInHand.hasMarker(ANCHOR_ITEM_KEY)) return
        if (event.player.hasPermission(PLACE_PERMISSION) && canUse(event.player)) return
        event.isCancelled = true
        event.player.sendActionBar(settings?.message("no-permission") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!event.itemInHand.hasMarker(ANCHOR_ITEM_KEY)) return
        CustomBlockData(event.blockPlaced, ARC.instance).set(ANCHOR_BLOCK_KEY, PersistentDataType.BYTE, 1.toByte())
        if (anchors.add(TravelAnchorPosition.of(event.blockPlaced))) persistAnchorIndex(event.blockPlaced.world.uid)
        event.player.sendActionBar(settings?.message("placed") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun guardBreak(event: BlockBreakEvent) {
        if (!isAnchor(event.block) || event.player.hasPermission(BREAK_PERMISSION)) return
        event.isCancelled = true
        event.player.sendActionBar(settings?.message("no-permission") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!isAnchor(event.block)) return
        event.isDropItems = false
        removeAnchor(event.block)
        if (event.player.gameMode != GameMode.CREATIVE) {
            settings?.anchorItem()?.let { event.block.world.dropItemNaturally(event.block.location.add(0.5, 0.5, 0.5), it) }
        }
        event.player.sendActionBar(settings?.message("removed") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action !in RIGHT_CLICK_ACTIONS) return
        if (!(event.item ?: event.player.inventory.itemInMainHand).hasMarker(STAFF_ITEM_KEY)) return
        event.isCancelled = true
        val player = event.player
        if (!canUse(player)) {
            player.sendActionBar(settings?.message("no-permission") ?: Component.empty())
            return
        }
        val source = anchorBelow(player)
        val target = selectedTargets[player.uniqueId]
            ?.takeIf { it != source && it.worldId == player.world.uid }
            ?: selectTarget(player, source)
        if (target == null) {
            player.sendActionBar(settings?.message("no-target") ?: Component.empty())
            return
        }
        teleport(player, target)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return
        val player = event.player
        if (!canUse(player)) return
        val source = anchorBelow(player) ?: return
        val target = selectedTargets[player.uniqueId]
            ?.takeIf { it != source && it.worldId == player.world.uid }
            ?: selectTarget(player, source)
            ?: return
        teleport(player, target)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clearDisplays(event.player.uniqueId)
        cooldowns.remove(event.player.uniqueId)
        pendingTeleports.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) = loadAnchors(event.chunk)

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf(::isAnchor)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf(::isAnchor)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any(::isAnchor)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any(::isAnchor)) event.isCancelled = true
    }

    private fun refreshPlayers() {
        val current = settings ?: return
        Bukkit.getOnlinePlayers().forEach { player ->
            if (!canUse(player)) {
                clearDisplays(player.uniqueId)
                return@forEach
            }
            val source = anchorBelow(player)
            val staffHeld = player.inventory.itemInMainHand.hasMarker(STAFF_ITEM_KEY)
            if (source == null && !staffHeld) {
                clearDisplays(player.uniqueId)
                return@forEach
            }
            val candidates = visibleCandidates(player, source)
            val selected = chooseTravelAnchorTarget(candidates, current.range * current.range, current.selectionDot)?.target
            render(player, candidates, selected)
            if (selected != null) {
                selectedTargets[player.uniqueId] = selected
                val distance = candidates.first { it.target == selected }.distanceSquared.let(Math::sqrt).roundToInt().toString()
                val message = travelAnchorTargetMessage(source != null, staffHeld) ?: return@forEach
                player.sendActionBar(current.message(message, "%distance%" to distance))
            } else {
                selectedTargets.remove(player.uniqueId)
            }
        }
    }

    private fun visibleCandidates(player: Player, source: TravelAnchorPosition?): List<AimCandidate<TravelAnchorPosition>> {
        val current = settings ?: return emptyList()
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        return anchors
            .asSequence()
            .filter { it != source && it.worldId == player.world.uid }
            .map { position ->
                val delta = org.bukkit.util.Vector(position.x + 0.5, position.y + 0.5, position.z + 0.5)
                    .subtract(eye.toVector())
                val distanceSquared = delta.lengthSquared()
                AimCandidate(position, distanceSquared, direction.dot(delta.normalize()))
            }
            .filter { it.distanceSquared > 0.01 && it.distanceSquared <= current.range * current.range }
            .filter { it.dot >= current.visibleDot }
            .sortedBy(AimCandidate<TravelAnchorPosition>::distanceSquared)
            .take(current.maximumTargets)
            .toList()
    }

    private fun selectTarget(player: Player, source: TravelAnchorPosition?): TravelAnchorPosition? {
        val current = settings ?: return null
        return chooseTravelAnchorTarget(
            visibleCandidates(player, source),
            current.range * current.range,
            current.selectionDot,
        )?.target
    }

    private fun render(
        player: Player,
        candidates: List<AimCandidate<TravelAnchorPosition>>,
        selected: TravelAnchorPosition?,
    ) {
        val current = settings ?: return
        val playerDisplays = displays.getOrPut(player.uniqueId, ::linkedMapOf)
        val desired = candidates.mapTo(hashSetOf(), AimCandidate<TravelAnchorPosition>::target)
        playerDisplays.keys.filterNot { it in desired }.toList().forEach { position ->
            playerDisplays.remove(position)?.remove()
        }
        candidates.forEach { candidate ->
            val location = displayLocation(player, candidate)
            val display = playerDisplays[candidate.target]?.takeIf { it.isValid } ?: spawnDisplay(player, location).also {
                playerDisplays[candidate.target] = it
            }
            display.teleport(location)
            val scale = travelAnchorScale(candidate.dot, current.visibleDot, current.minimumScale, current.maximumScale)
            val offset = (1f - scale) / 2f
            display.transformation = Transformation(
                Vector3f(offset, offset, offset),
                AxisAngle4f(),
                Vector3f(scale, scale, scale),
                AxisAngle4f(),
            )
            display.glowColorOverride = if (candidate.target == selected) SELECTED_COLOR else VISIBLE_COLOR
        }
        if (playerDisplays.isEmpty()) displays.remove(player.uniqueId)
    }

    private fun displayLocation(player: Player, candidate: AimCandidate<TravelAnchorPosition>): Location {
        val eye = player.eyeLocation
        val target = Location(player.world, candidate.target.x + 0.5, candidate.target.y + 0.5, candidate.target.z + 0.5)
        val distance = travelAnchorDisplayDistance(Math.sqrt(candidate.distanceSquared), checkNotNull(settings).proxyDistance)
        val center = eye.clone().add(target.toVector().subtract(eye.toVector()).normalize().multiply(distance))
        return center.subtract(0.5, 0.5, 0.5)
    }

    private fun spawnDisplay(player: Player, location: Location): BlockDisplay {
        val current = checkNotNull(settings)
        val display = location.world.spawn(location, BlockDisplay::class.java) {
            it.block = current.displayMaterial.createBlockData()
            it.isPersistent = false
            it.isVisibleByDefault = false
            it.isInvulnerable = true
            it.setGravity(false)
            it.isGlowing = true
            it.glowColorOverride = VISIBLE_COLOR
            it.interpolationDelay = 0
            it.interpolationDuration = current.updateTicks.toInt()
            it.viewRange = 2f
            it.displayWidth = current.maximumScale + 0.5f
            it.displayHeight = current.maximumScale + 0.5f
            it.addScoreboardTag("arc_travel_anchor_preview")
        }
        player.showEntity(ARC.instance, display)
        return display
    }

    private fun teleport(player: Player, position: TravelAnchorPosition) {
        val current = settings ?: return
        val now = System.currentTimeMillis()
        if (now < cooldowns.getOrDefault(player.uniqueId, 0L) || player.uniqueId in pendingTeleports) return
        if (player.isInsideVehicle) {
            player.sendActionBar(current.message("leave-vehicle"))
            return
        }
        val world = Bukkit.getWorld(position.worldId)
        if (world == null) {
            player.sendActionBar(current.message("unavailable"))
            return
        }
        if (!world.isChunkLoaded(position.chunkX, position.chunkZ)) {
            pendingTeleports += player.uniqueId
            world.getChunkAtAsync(position.chunkX, position.chunkZ, false).whenComplete { chunk, failure ->
                if (!ARC.instance.isEnabled || settings !== current) return@whenComplete
                Bukkit.getScheduler().runTask(ARC.instance, Runnable {
                    pendingTeleports.remove(player.uniqueId)
                    if (!player.isOnline || !ARC.instance.isEnabled) return@Runnable
                    if (failure != null || chunk == null) {
                        warn(
                            "TRAVEL_ANCHORS phase=TELEPORT reason=chunk-load-failed player={} world={} chunk={},{}",
                            player.name,
                            world.name,
                            position.chunkX,
                            position.chunkZ,
                            failure,
                        )
                        player.sendActionBar(current.message("unavailable"))
                        return@Runnable
                    }
                    teleport(player, position)
                })
            }
            return
        }
        val block = position.block()?.takeIf(::isAnchor) ?: run {
            removeAnchor(position)
            player.sendActionBar(current.message("unavailable"))
            return
        }
        if (!isSafeDestination(block)) {
            player.sendActionBar(current.message("unsafe"))
            return
        }
        cooldowns[player.uniqueId] = now + TELEPORT_COOLDOWN_MILLIS
        val from = player.location.clone()
        val destination = block.location.add(0.5, 1.0, 0.5).apply {
            yaw = from.yaw
            pitch = from.pitch
        }
        if (!player.teleport(destination, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            cooldowns.remove(player.uniqueId)
            player.sendActionBar(current.message("blocked"))
            return
        }
        clearDisplays(player.uniqueId)
        from.world.spawnParticle(Particle.PORTAL, from.clone().add(0.0, 1.0, 0.0), 24, 0.3, 0.6, 0.3, 0.1)
        block.world.spawnParticle(Particle.PORTAL, destination.clone().add(0.0, 0.8, 0.0), 32, 0.35, 0.6, 0.35, 0.1)
        player.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8f, 1.15f)
    }

    private fun anchorBelow(player: Player, location: org.bukkit.Location = player.location): TravelAnchorPosition? {
        return listOf(0.08, 0.4).firstNotNullOfOrNull { offset ->
            val block = location.clone().subtract(0.0, offset, 0.0).block
            TravelAnchorPosition.of(block).takeIf { it in anchors && isAnchor(block) }
        }
    }

    private fun isSafeDestination(anchor: Block): Boolean =
        anchor.getRelative(0, 1, 0).let { it.isPassable && !it.isLiquid } &&
            anchor.getRelative(0, 2, 0).let { it.isPassable && !it.isLiquid }

    private fun isAnchor(block: Block): Boolean =
        CustomBlockData(block, ARC.instance).has(ANCHOR_BLOCK_KEY, PersistentDataType.BYTE)

    private fun loadAnchors(chunk: org.bukkit.Chunk) {
        if (settings?.allowsWorld(chunk.world.name) != true) return
        val discovered = CustomBlockData.getBlocksWithCustomData(ARC.instance, chunk)
            .filter(::isAnchor)
            .map(TravelAnchorPosition::of)
        if (anchors.addAll(discovered)) persistAnchorIndex(chunk.world.uid)
    }

    private fun loadAnchorIndex(world: org.bukkit.World) {
        val coordinates = world.persistentDataContainer
            .get(ANCHOR_INDEX_KEY, PersistentDataType.INTEGER_ARRAY)
            ?: return
        if (coordinates.size % 3 != 0) {
            warn("TRAVEL_ANCHORS phase=INDEX reason=invalid-coordinate-count world={} count={}", world.name, coordinates.size)
        }
        for (index in 0 until coordinates.size - 2 step 3) {
            anchors += TravelAnchorPosition.of(world.uid, coordinates[index], coordinates[index + 1], coordinates[index + 2])
        }
    }

    private fun persistAnchorIndex(worldId: UUID) {
        val world = Bukkit.getWorld(worldId) ?: return
        val positions = anchors.filter { it.worldId == worldId }
        val coordinates = IntArray(positions.size * 3)
        positions.forEachIndexed { index, position ->
            coordinates[index * 3] = position.x
            coordinates[index * 3 + 1] = position.y
            coordinates[index * 3 + 2] = position.z
        }
        world.persistentDataContainer.set(ANCHOR_INDEX_KEY, PersistentDataType.INTEGER_ARRAY, coordinates)
    }

    private fun removeAnchor(block: Block) {
        CustomBlockData(block, ARC.instance).remove(ANCHOR_BLOCK_KEY)
        removeAnchor(TravelAnchorPosition.of(block))
    }

    private fun removeAnchor(position: TravelAnchorPosition) {
        if (!anchors.remove(position)) return
        displays.values.forEach { it.remove(position)?.remove() }
        displays.entries.removeIf { it.value.isEmpty() }
        selectedTargets.entries.removeIf { it.value == position }
        persistAnchorIndex(position.worldId)
    }

    private fun clearDisplays(playerId: UUID) {
        displays.remove(playerId)?.values?.forEach { if (it.isValid) it.remove() }
        selectedTargets.remove(playerId)
    }

    private fun canUse(player: Player): Boolean =
        settings?.let { it.enabled && it.allowsPlayer(player) && it.allowsWorld(player.world.name) } == true &&
            player.hasPermission(USE_PERMISSION)

    private val VISIBLE_COLOR = Color.fromRGB(0x92, 0xBE, 0xD8)
    private val SELECTED_COLOR = Color.fromRGB(0xFF, 0xD1, 0x66)
}

object TravelAnchorsSubCommand : SubCommand {
    override val configKey = "anchors"
    override val defaultName = "anchors"
    override val defaultPermission = "arc.travel-anchors.admin"
    override val defaultDescription = "Выдать набор путевых якорей"
    override val defaultUsage = "/arc anchors give [игрок]"

    override fun isAvailable(): Boolean = TravelAnchorsModule.isEnabled

    override fun execute(sender: org.bukkit.command.CommandSender, args: Array<String>): Boolean {
        if (args.firstOrNull()?.equals("give", ignoreCase = true) != true) {
            sendUsage(sender)
            return true
        }
        val player = if (args.size >= 2) getOnlinePlayer(sender, args[1]) else sender as? Player
        if (player == null) {
            sendUsage(sender)
            return true
        }
        TravelAnchorsModule.giveKit(player)
        sender.sendMessage(TravelAnchorsModule.message("kit-given", "%player%" to player.name))
        return true
    }

    override fun tabComplete(sender: org.bukkit.command.CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("give").tabComplete(args[0])
            2 -> tabCompletePlayers(args[1])
            else -> null
        }
}
