package ru.arc.travelanchors

import com.jeff_media.customblockdata.CustomBlockData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
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
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
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
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Matrix4f
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
import ru.arc.gui.ArcMenus
import ru.arc.ops.OpsItemHandlers
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.util.ItemStackDslBuilder
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.itemStack
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

private val ANCHOR_BLOCK_KEY = NamespacedKey("arc", "travel_anchor")
private val ANCHOR_INDEX_KEY = NamespacedKey("arc", "travel_anchor_index")
private val ANCHOR_NAMES_KEY = NamespacedKey("arc", "travel_anchor_names")
private val ANCHOR_OWNERS_KEY = NamespacedKey("arc", "travel_anchor_owners")
private val ANCHOR_ACCESS_KEY = NamespacedKey("arc", "travel_anchor_access")
private val ANCHOR_NAME_KEY = NamespacedKey("arc", "travel_anchor_name")
private val ANCHOR_OWNER_KEY = NamespacedKey("arc", "travel_anchor_owner")
private val ANCHOR_ITEM_KEY = NamespacedKey("arc", "travel_anchor_item")
private val STAFF_ITEM_KEY = NamespacedKey("arc", "travel_anchor_staff")
private val ITEM_OWNER_KEY = NamespacedKey("arc", "travel_anchor_item_owner")
private val NAME_INPUT = PaperDialogInputId.of("anchor_name")
private val ACCESS_INPUT = PaperDialogInputId.of("anchor_access")
private const val TELEPORT_COOLDOWN_MILLIS = 500L
private const val MAX_ANCHOR_NAME_LENGTH = 32
private const val MAX_ACCESS_INPUT_LENGTH = 256
private const val MAX_SHARED_PLAYERS = 16
private const val PROXY_DEPTH = 0.03f
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

internal fun travelAnchorLabelScale(distance: Double, minimumScale: Float, maximumScale: Float): Float =
    (distance / 8.0).toFloat().coerceIn(minimumScale, maximumScale)

internal fun travelAnchorDisplayCenter(eye: Location, target: Location, distance: Double): Location =
    eye.clone().add(target.toVector().subtract(eye.toVector()).normalize().multiply(distance)).apply {
        yaw = 0f
        pitch = 0f
    }

internal data class TravelAnchorDisplayShape(val cameraFacing: Boolean, val depth: Float)

internal fun travelAnchorDisplayShape(
    actualDistance: Double,
    proxyDistance: Double,
    scale: Float,
): TravelAnchorDisplayShape =
    if (actualDistance > proxyDistance) TravelAnchorDisplayShape(cameraFacing = true, depth = PROXY_DEPTH)
    else TravelAnchorDisplayShape(cameraFacing = false, depth = scale)

internal enum class TravelAnchorInteraction { IGNORE, RENAME, TELEPORT }

internal fun travelAnchorInteraction(clickedAnchor: Boolean, staffHeld: Boolean): TravelAnchorInteraction =
    when {
        staffHeld -> TravelAnchorInteraction.TELEPORT
        clickedAnchor -> TravelAnchorInteraction.RENAME
        else -> TravelAnchorInteraction.IGNORE
    }

internal fun normalizeTravelAnchorName(raw: String): String? {
    val name = raw.trim()
    return name.takeIf {
        it.isNotEmpty() &&
            it.codePointCount(0, it.length) <= MAX_ANCHOR_NAME_LENGTH &&
            it.none(Character::isISOControl)
    }
}

internal fun normalizeTravelAnchorAccessNames(raw: String): List<String>? {
    if (raw.isBlank()) return emptyList()
    val names = raw.split(',', '\n', ' ', '\t').filter(String::isNotBlank).distinctBy { it.lowercase() }
    return names.takeIf { it.size <= MAX_SHARED_PLAYERS && it.all { name -> name.matches(Regex("[A-Za-z0-9_]{3,16}")) } }
}

internal data class TravelAnchorNameEntry(val x: Int, val y: Int, val z: Int, val name: String)

internal data class TravelAnchorOwnerEntry(val x: Int, val y: Int, val z: Int, val owner: String)

internal data class TravelAnchorAccessEntry(val owner: String, val players: Set<String>)

internal fun travelAnchorIdentityAllows(
    owner: String?,
    playerName: String,
    playerId: UUID,
    legacyName: (UUID) -> String? = { null },
): Boolean {
    if (owner == null || owner.equals(playerName, ignoreCase = true)) return true
    val legacyId = runCatching { UUID.fromString(owner) }.getOrNull() ?: return false
    return legacyId == playerId || legacyName(legacyId)?.equals(playerName, ignoreCase = true) == true
}

internal fun travelAnchorAccessAllows(
    owner: String?,
    playerName: String,
    playerId: UUID,
    sharedPlayers: Set<String>,
    legacyName: (UUID) -> String? = { null },
): Boolean =
    travelAnchorIdentityAllows(owner, playerName, playerId, legacyName) ||
        sharedPlayers.any { travelAnchorIdentityAllows(it, playerName, playerId, legacyName) }

internal fun encodeTravelAnchorNames(entries: Iterable<TravelAnchorNameEntry>): String =
    entries
        .sortedWith(compareBy(TravelAnchorNameEntry::x, TravelAnchorNameEntry::y, TravelAnchorNameEntry::z))
        .joinToString("\n") { entry ->
            val name = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(entry.name.toByteArray(StandardCharsets.UTF_8))
            "${entry.x},${entry.y},${entry.z}|$name"
        }

internal fun decodeTravelAnchorNames(encoded: String): List<TravelAnchorNameEntry> =
    encoded.lineSequence().mapNotNull { line ->
        runCatching {
            val (coordinates, encodedName) = line.split('|', limit = 2).takeIf { it.size == 2 } ?: return@runCatching null
            val parts = coordinates.split(',').takeIf { it.size == 3 } ?: return@runCatching null
            val name = normalizeTravelAnchorName(
                String(Base64.getUrlDecoder().decode(encodedName), StandardCharsets.UTF_8),
            ) ?: return@runCatching null
            TravelAnchorNameEntry(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), name)
        }.getOrNull()
    }.toList()

internal fun encodeTravelAnchorOwners(entries: Iterable<TravelAnchorOwnerEntry>): String =
    entries
        .sortedWith(compareBy(TravelAnchorOwnerEntry::x, TravelAnchorOwnerEntry::y, TravelAnchorOwnerEntry::z))
        .joinToString("\n") { entry -> "${entry.x},${entry.y},${entry.z}|${entry.owner}" }

internal fun decodeTravelAnchorOwners(encoded: String): List<TravelAnchorOwnerEntry> =
    encoded.lineSequence().mapNotNull { line ->
        runCatching {
            val (coordinates, owner) = line.split('|', limit = 2).takeIf { it.size == 2 } ?: return@runCatching null
            val parts = coordinates.split(',').takeIf { it.size == 3 } ?: return@runCatching null
            TravelAnchorOwnerEntry(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), owner)
        }.getOrNull()
    }.toList()

internal fun encodeTravelAnchorAccess(entries: Iterable<TravelAnchorAccessEntry>): String =
    entries
        .filter { it.players.isNotEmpty() }
        .sortedBy { it.owner.lowercase() }
        .joinToString("\n") { entry ->
            "${entry.owner}|${entry.players.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(",")}"
        }

internal fun decodeTravelAnchorAccess(encoded: String): List<TravelAnchorAccessEntry> =
    encoded.lineSequence().mapNotNull { line ->
        runCatching {
            val (owner, players) = line.split('|', limit = 2).takeIf { it.size == 2 } ?: return@runCatching null
            TravelAnchorAccessEntry(
                owner,
                players.split(',').filter(String::isNotBlank).toCollection(linkedSetOf()),
            )
        }.getOrNull()
    }.toList()

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
    val range: Double,
    val maximumTargets: Int,
    val visibleDot: Double,
    val selectionDot: Double,
    val minimumScale: Float,
    val maximumScale: Float,
    val labelMinimumScale: Float,
    val labelMaximumScale: Float,
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

    fun anchorItem(ownerName: String): ItemStack =
        itemStack(anchorMaterial) {
            configuredItem("anchor", anchorModelData)
        }.withMarker(ANCHOR_ITEM_KEY).withOwner(ownerName)

    fun staffItem(ownerName: String): ItemStack =
        itemStack(staffMaterial) {
            configuredItem("staff", staffModelData)
            glowing()
        }.withMarker(STAFF_ITEM_KEY).withOwner(ownerName)

    fun message(key: String, vararg replacements: Pair<String, String>): Component {
        var text = source.string("messages.$key", "")
        replacements.forEach { (placeholder, value) -> text = text.replace(placeholder, value) }
        return TextUtil.mm(text, true)
    }

    fun targetMessage(key: String, name: String, distance: String): Component =
        TextUtil.mm(source.string("messages.$key", "").replace("%distance%", distance), true)
            .replaceText { it.matchLiteral("%name%").replacement(Component.text(name)) }

    fun namingText(key: String, vararg replacements: Pair<String, String>): Component {
        var text = source.string("naming.dialog.$key", "")
        replacements.forEach { (placeholder, value) -> text = text.replace(placeholder, value) }
        return TextUtil.mm(text, true).decoration(TextDecoration.ITALIC, false)
    }

    fun defaultAnchorName(): String = source.string("naming.default-name", "Путевой якорь")

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

private fun ItemStack.withOwner(ownerName: String): ItemStack = apply {
    editMeta { it.persistentDataContainer.set(ITEM_OWNER_KEY, PersistentDataType.STRING, ownerName) }
}

private fun ItemStack?.hasMarker(key: NamespacedKey): Boolean =
    this?.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BYTE) == true

private fun ItemStack?.owner(): String? =
    this?.itemMeta?.persistentDataContainer?.get(ITEM_OWNER_KEY, PersistentDataType.STRING)

private object TravelAnchorConfig {
    fun load(dataPath: Path): TravelAnchorSettings {
        val source = ConfigManager.ofModule(dataPath, "teleport-anchors.yml")
        source.mergeMissingFromBundled("modules/teleport-anchors.yml")
        val visibleAngle = source.real("targeting.visible-angle-degrees", 70.0).coerceIn(10.0, 89.0)
        val selectionAngle = source.real("targeting.selection-angle-degrees", 10.0).coerceIn(1.0, visibleAngle)
        return TravelAnchorSettings(
            enabled = source.bool("enabled", false),
            worlds = source.stringList("worlds").toSet(),
            range = source.real("targeting.range", 1024.0).coerceIn(8.0, 4096.0),
            maximumTargets = source.integer("targeting.maximum-targets", 24).coerceIn(1, 64),
            visibleDot = cos(Math.toRadians(visibleAngle)),
            selectionDot = cos(Math.toRadians(selectionAngle)),
            minimumScale = source.real("visual.minimum-scale", 1.04).toFloat().coerceIn(1.01f, 6.0f),
            maximumScale = source.real("visual.maximum-scale", 3.0).toFloat().coerceIn(1.01f, 6.0f),
            labelMinimumScale = source.real("visual.label-minimum-scale", 1.8).toFloat().coerceIn(0.5f, 8.0f),
            labelMaximumScale = source.real("visual.label-maximum-scale", 6.0).toFloat().coerceIn(0.5f, 12.0f),
            proxyDistance = source.real("visual.proxy-distance", 48.0).coerceIn(16.0, 96.0),
            updateTicks = source.long("visual.update-ticks", 1L).coerceIn(1L, 20L),
            anchorMaterial = source.material("items.anchor.material", Material.LODESTONE, requireBlock = true),
            displayMaterial = source.material("visual.block-material", Material.LODESTONE, requireBlock = true),
            staffMaterial = source.material("items.staff.material", Material.BLAZE_ROD, requireBlock = false),
            anchorModelData = source.integer("items.anchor.custom-model-data", 0).coerceAtLeast(0),
            staffModelData = source.integer("items.staff.custom-model-data", 0).coerceAtLeast(0),
            source = source,
        ).let { settings ->
            settings.copy(
                maximumScale = settings.maximumScale.coerceAtLeast(settings.minimumScale),
                labelMaximumScale = settings.labelMaximumScale.coerceAtLeast(settings.labelMinimumScale),
            )
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
    private val labels = mutableMapOf<UUID, TextDisplay>()
    private val anchorNames = mutableMapOf<TravelAnchorPosition, String>()
    private val anchorOwners = mutableMapOf<TravelAnchorPosition, String>()
    private val sharedAccess = mutableMapOf<String, MutableSet<String>>()
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
        removeOrphanDisplays()
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
        labels.values.forEach { if (it.isValid) it.remove() }
        labels.clear()
        anchorNames.clear()
        anchorOwners.clear()
        sharedAccess.clear()
        selectedTargets.clear()
        anchors.clear()
        cooldowns.clear()
        pendingTeleports.clear()
        settings = null
    }

    fun giveKit(player: Player): Int {
        val current = settings ?: return 0
        return OpsItemHandlers.giveStacks(
            player,
            listOf(current.anchorItem(player.name), current.staffItem(player.name)),
            dropOverflow = true,
        )
    }

    fun message(key: String, vararg replacements: Pair<String, String>): Component =
        settings?.message(key, *replacements) ?: Component.empty()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun guardPlace(event: BlockPlaceEvent) {
        if (!event.itemInHand.hasMarker(ANCHOR_ITEM_KEY)) return
        if (isFeatureAvailable(event.player) && itemOwnerAllows(event.itemInHand, event.player)) {
            if (!event.itemInHand.owner().equals(event.player.name, ignoreCase = true)) {
                event.itemInHand.withOwner(event.player.name)
            }
            return
        }
        event.isCancelled = true
        event.player.sendActionBar(settings?.message("no-permission") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!event.itemInHand.hasMarker(ANCHOR_ITEM_KEY)) return
        val blockData = CustomBlockData(event.blockPlaced, ARC.instance)
        val owner = event.itemInHand.owner() ?: event.player.name
        blockData.set(ANCHOR_BLOCK_KEY, PersistentDataType.BYTE, 1.toByte())
        blockData.set(ANCHOR_OWNER_KEY, PersistentDataType.STRING, owner)
        val position = TravelAnchorPosition.of(event.blockPlaced)
        anchorOwners[position] = owner
        if (anchors.add(position)) persistAnchorIndex(event.blockPlaced.world.uid)
        persistAnchorOwners(event.blockPlaced.world.uid)
        event.player.sendActionBar(settings?.message("placed") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun guardBreak(event: BlockBreakEvent) {
        if (!isAnchor(event.block)) return
        if (ownsAnchor(event.player, TravelAnchorPosition.of(event.block))) {
            claimAnchor(event.block, event.player)
            return
        }
        event.isCancelled = true
        event.player.sendActionBar(settings?.message("no-permission") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!isAnchor(event.block)) return
        val owner = anchorOwners[TravelAnchorPosition.of(event.block)] ?: event.player.name
        event.isDropItems = false
        removeAnchor(event.block)
        if (event.player.gameMode != GameMode.CREATIVE) {
            settings?.anchorItem(owner)?.let { event.block.world.dropItemNaturally(event.block.location.add(0.5, 0.5, 0.5), it) }
        }
        event.player.sendActionBar(settings?.message("removed") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action !in RIGHT_CLICK_ACTIONS) return
        val held = event.item ?: event.player.inventory.itemInMainHand
        val clickedAnchor = event.clickedBlock?.takeIf(::isAnchor)
        val staffHeld = held.hasMarker(STAFF_ITEM_KEY)
        if (staffHeld && itemOwnerAllows(held, event.player) && !held.owner().equals(event.player.name, ignoreCase = true)) {
            held.withOwner(event.player.name)
        }
        when (travelAnchorInteraction(clickedAnchor != null, staffHeld)) {
            TravelAnchorInteraction.IGNORE -> return
            TravelAnchorInteraction.RENAME -> {
                event.isCancelled = true
                val position = TravelAnchorPosition.of(checkNotNull(clickedAnchor))
                if (!isFeatureAvailable(event.player) || !ownsAnchor(event.player, position)) {
                    event.player.sendActionBar(settings?.message("no-permission") ?: Component.empty())
                    return
                }
                claimAnchor(checkNotNull(clickedAnchor), event.player)
                ArcMenus.beginDialogFlow(event.player)
                openNameDialog(event.player, checkNotNull(clickedAnchor))
                return
            }
            TravelAnchorInteraction.TELEPORT -> Unit
        }
        event.isCancelled = true
        val player = event.player
        if (!isFeatureAvailable(player) || !itemOwnerAllows(held, player)) {
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
        if (!isFeatureAvailable(player)) return
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
    fun onHeldItemChange(event: PlayerItemHeldEvent) {
        val player = event.player
        val next = player.inventory.getItem(event.newSlot)
        if (anchorBelow(player) == null && (!next.hasMarker(STAFF_ITEM_KEY) || !itemOwnerAllows(next, player))) {
            clearDisplays(player)
        }
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
            if (!isFeatureAvailable(player)) {
                clearDisplays(player)
                return@forEach
            }
            val source = anchorBelow(player)
            val staff = player.inventory.itemInMainHand
            val staffHeld = staff.hasMarker(STAFF_ITEM_KEY) && itemOwnerAllows(staff, player)
            if (source == null && !staffHeld) {
                clearDisplays(player)
                return@forEach
            }
            val candidates = visibleCandidates(player, source)
            val selected = chooseTravelAnchorTarget(candidates, current.range * current.range, current.selectionDot)?.target
            render(player, candidates, selected)
            if (selected != null) {
                selectedTargets[player.uniqueId] = selected
                val distance = candidates.first { it.target == selected }.distanceSquared.let(Math::sqrt).roundToInt().toString()
                val message = travelAnchorTargetMessage(source != null, staffHeld) ?: return@forEach
                val targetName = anchorNames[selected] ?: current.defaultAnchorName()
                player.sendActionBar(current.targetMessage(message, targetName, distance))
            } else {
                selectedTargets.remove(player.uniqueId)
                clearLabel(player)
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
            .filter { canAccess(player, it) }
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
            playerDisplays.remove(position)?.let {
                player.hideEntity(ARC.instance, it)
                it.remove()
            }
        }
        var selectedLocation: Location? = null
        var selectedScale = 1f
        candidates.forEach { candidate ->
            val actualDistance = Math.sqrt(candidate.distanceSquared)
            val location = displayLocation(player, candidate)
            val display = playerDisplays[candidate.target]?.takeIf { it.isValid } ?: spawnDisplay(player, location).also {
                playerDisplays[candidate.target] = it
            }
            display.teleport(location)
            val scale = travelAnchorScale(candidate.dot, current.visibleDot, current.minimumScale, current.maximumScale)
            val shape = travelAnchorDisplayShape(actualDistance, current.proxyDistance, scale)
            display.billboard = if (shape.cameraFacing) Display.Billboard.CENTER else Display.Billboard.FIXED
            display.transformation = Transformation(
                Vector3f(-scale / 2f, -scale / 2f, -shape.depth / 2f),
                AxisAngle4f(),
                Vector3f(scale, scale, shape.depth),
                AxisAngle4f(),
            )
            display.glowColorOverride = if (candidate.target == selected) SELECTED_COLOR else VISIBLE_COLOR
            if (candidate.target == selected) {
                selectedLocation = location
                selectedScale = scale
            }
        }
        if (selected != null && selectedLocation != null) {
            renderLabel(player, selected, checkNotNull(selectedLocation), selectedScale)
        } else clearLabel(player)
        if (playerDisplays.isEmpty()) displays.remove(player.uniqueId)
    }

    private fun displayLocation(player: Player, candidate: AimCandidate<TravelAnchorPosition>): Location {
        val eye = player.eyeLocation
        val target = Location(player.world, candidate.target.x + 0.5, candidate.target.y + 0.5, candidate.target.z + 0.5)
        val distance = travelAnchorDisplayDistance(Math.sqrt(candidate.distanceSquared), checkNotNull(settings).proxyDistance)
        return travelAnchorDisplayCenter(eye, target, distance)
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
            it.interpolationDuration = 1
            it.teleportDuration = 1
            it.viewRange = 2f
            it.displayWidth = current.maximumScale + 0.5f
            it.displayHeight = current.maximumScale + 0.5f
            it.addScoreboardTag("arc_travel_anchor_preview")
        }
        player.showEntity(ARC.instance, display)
        return display
    }

    private fun renderLabel(player: Player, position: TravelAnchorPosition, marker: Location, scale: Float) {
        val current = checkNotNull(settings)
        val location = marker.clone().add(0.0, scale / 2.0 + 0.55, 0.0)
        val label = labels[player.uniqueId]?.takeIf { it.isValid } ?: player.world.spawn(location, TextDisplay::class.java) {
            it.isPersistent = false
            it.isVisibleByDefault = false
            it.isInvulnerable = true
            it.setGravity(false)
            it.billboard = Display.Billboard.CENTER
            it.isSeeThrough = true
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(0, 0, 0, 0)
            it.brightness = Display.Brightness(15, 15)
            it.alignment = TextDisplay.TextAlignment.CENTER
            it.lineWidth = 220
            it.teleportDuration = 1
            it.viewRange = 2f
            it.addScoreboardTag("arc_travel_anchor_label")
        }.also {
            labels[player.uniqueId] = it
            player.showEntity(ARC.instance, it)
        }
        label.teleport(location)
        val labelScale = travelAnchorLabelScale(marker.distance(player.eyeLocation), current.labelMinimumScale, current.labelMaximumScale)
        label.setTransformationMatrix(Matrix4f().scaling(labelScale))
        val text = Component.text(anchorNames[position] ?: current.defaultAnchorName())
            .color(TextColor.color(0xFFD166))
            .decoration(TextDecoration.ITALIC, false)
        if (label.text() != text) label.text(text)
    }

    private fun clearLabel(playerId: UUID) {
        labels.remove(playerId)?.let { if (it.isValid) it.remove() }
    }

    private fun clearLabel(player: Player) {
        labels.remove(player.uniqueId)?.let {
            player.hideEntity(ARC.instance, it)
            if (it.isValid) it.remove()
        }
    }

    private fun openNameDialog(
        player: Player,
        block: Block,
        invalidName: Boolean = false,
        invalidPlayers: List<String> = emptyList(),
        initialName: String? = null,
        initialAccess: String? = null,
    ) {
        val current = settings ?: return
        val position = TravelAnchorPosition.of(block)
        val nameValue = initialName ?: anchorNames[position].orEmpty()
        val owner = anchorOwners[position] ?: player.name
        val accessValue = initialAccess ?: sharedAccess[owner].orEmpty().map(::displayName)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .joinToString(", ")
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "travel-anchors.rename",
                title = current.namingText("title"),
                body = listOf(PaperDialogBody(current.namingText("body"), width = 360)) +
                    (if (invalidName) listOf(PaperDialogBody(current.namingText("invalid", "%limit%" to MAX_ANCHOR_NAME_LENGTH.toString()), width = 360)) else emptyList()) +
                    (if (invalidPlayers.isNotEmpty()) listOf(PaperDialogBody(current.namingText("access-invalid", "%players%" to invalidPlayers.joinToString(", ")), width = 360)) else emptyList()),
                inputs = listOf(
                    PaperDialogTextInput(NAME_INPUT, current.namingText("input"), nameValue, 360, MAX_ANCHOR_NAME_LENGTH),
                    PaperDialogTextInput(ACCESS_INPUT, current.namingText("access"), accessValue, 360, MAX_ACCESS_INPUT_LENGTH),
                ),
                buttons = listOf(PaperDialogButton(
                    id = PaperDialogActionId.of("save_anchor_settings"),
                    label = current.namingText("save"),
                    width = 240,
                    closeDialogBeforeAction = false,
                    onClick = { context ->
                        val name = normalizeTravelAnchorName(context.text(NAME_INPUT).orEmpty())
                        if (name == null) {
                            openNameDialog(
                                context.player,
                                block,
                                invalidName = true,
                                initialName = context.text(NAME_INPUT).orEmpty(),
                                initialAccess = context.text(ACCESS_INPUT).orEmpty(),
                            )
                            return@PaperDialogButton
                        }
                        val accessText = context.text(ACCESS_INPUT).orEmpty()
                        val accessNames = normalizeTravelAnchorAccessNames(accessText)
                        val invalidAccess = if (accessNames == null) listOf(accessText) else emptyList()
                        if (invalidAccess.isNotEmpty()) {
                            openNameDialog(
                                context.player,
                                block,
                                invalidPlayers = invalidAccess,
                                initialName = context.text(NAME_INPUT).orEmpty(),
                                initialAccess = accessText,
                            )
                            return@PaperDialogButton
                        }
                        val liveBlock = position.block()?.takeIf(::isAnchor)
                        if (!isFeatureAvailable(context.player) || liveBlock == null || !ownsAnchor(context.player, position)) {
                            ArcMenus.closeDialog(context.player)
                            context.player.sendActionBar(current.message("unavailable"))
                            return@PaperDialogButton
                        }
                        claimAnchor(liveBlock, context.player)
                        val ownerName = context.player.name
                        sharedAccess[ownerName] = accessNames.orEmpty().mapTo(linkedSetOf()) { it.lowercase() }
                        if (sharedAccess[ownerName].isNullOrEmpty()) sharedAccess.remove(ownerName)
                        persistSharedAccess()
                        setAnchorName(liveBlock, name)
                        ArcMenus.closeDialog(context.player)
                        context.player.sendActionBar(current.message("renamed"))
                    },
                )),
                columns = 1,
            ),
            closeButton = PaperDialogButton(
                id = PaperDialogActionId.of("close_anchor_name"),
                label = current.namingText("close"),
                width = 200,
                closeDialogBeforeAction = true,
                onClick = {},
            ),
        )
    }

    private fun setAnchorName(block: Block, name: String) {
        CustomBlockData(block, ARC.instance).set(ANCHOR_NAME_KEY, PersistentDataType.STRING, name)
        anchorNames[TravelAnchorPosition.of(block)] = name
        persistAnchorNames(block.world.uid)
    }

    private fun teleport(player: Player, position: TravelAnchorPosition) {
        val current = settings ?: return
        if (!canAccess(player, position)) {
            player.sendActionBar(current.message("no-permission"))
            return
        }
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
        if (ownsAnchor(player, position)) claimAnchor(block, player)
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
        clearDisplays(player)
        from.world.spawnParticle(Particle.PORTAL, from.clone().add(0.0, 1.0, 0.0), 24, 0.3, 0.6, 0.3, 0.1)
        block.world.spawnParticle(Particle.PORTAL, destination.clone().add(0.0, 0.8, 0.0), 32, 0.35, 0.6, 0.35, 0.1)
        player.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8f, 1.15f)
    }

    private fun anchorBelow(player: Player, location: org.bukkit.Location = player.location): TravelAnchorPosition? {
        return listOf(0.08, 0.4).firstNotNullOfOrNull { offset ->
            val block = location.clone().subtract(0.0, offset, 0.0).block
            TravelAnchorPosition.of(block).takeIf { it in anchors && isAnchor(block) && canAccess(player, it) }
        }
    }

    private fun isSafeDestination(anchor: Block): Boolean =
        anchor.getRelative(0, 1, 0).let { it.isPassable && !it.isLiquid } &&
            anchor.getRelative(0, 2, 0).let { it.isPassable && !it.isLiquid }

    private fun isAnchor(block: Block): Boolean =
        CustomBlockData(block, ARC.instance).has(ANCHOR_BLOCK_KEY, PersistentDataType.BYTE)

    private fun loadAnchors(chunk: org.bukkit.Chunk) {
        if (settings?.allowsWorld(chunk.world.name) != true) return
        var changed = false
        CustomBlockData.getBlocksWithCustomData(ARC.instance, chunk).filter(::isAnchor).forEach { block ->
            val position = TravelAnchorPosition.of(block)
            changed = anchors.add(position) || changed
            val name = CustomBlockData(block, ARC.instance).get(ANCHOR_NAME_KEY, PersistentDataType.STRING)
                ?.let(::normalizeTravelAnchorName)
            if (name != null && anchorNames.put(position, name) != name) changed = true
            val owner = CustomBlockData(block, ARC.instance).get(ANCHOR_OWNER_KEY, PersistentDataType.STRING)
            if (!owner.isNullOrBlank() && anchorOwners.put(position, owner) != owner) changed = true
        }
        if (changed) {
            persistAnchorIndex(chunk.world.uid)
            persistAnchorNames(chunk.world.uid)
            persistAnchorOwners(chunk.world.uid)
        }
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
        decodeTravelAnchorNames(
            world.persistentDataContainer.get(ANCHOR_NAMES_KEY, PersistentDataType.STRING).orEmpty(),
        ).forEach { entry ->
            val position = TravelAnchorPosition.of(world.uid, entry.x, entry.y, entry.z)
            if (position in anchors) anchorNames[position] = entry.name
        }
        decodeTravelAnchorOwners(
            world.persistentDataContainer.get(ANCHOR_OWNERS_KEY, PersistentDataType.STRING).orEmpty(),
        ).forEach { entry ->
            val position = TravelAnchorPosition.of(world.uid, entry.x, entry.y, entry.z)
            if (position in anchors) anchorOwners[position] = entry.owner
        }
        decodeTravelAnchorAccess(
            world.persistentDataContainer.get(ANCHOR_ACCESS_KEY, PersistentDataType.STRING).orEmpty(),
        ).forEach { entry -> sharedAccess.getOrPut(entry.owner, ::linkedSetOf).addAll(entry.players) }
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

    private fun persistAnchorNames(worldId: UUID) {
        val world = Bukkit.getWorld(worldId) ?: return
        val encoded = encodeTravelAnchorNames(anchorNames.mapNotNull { (position, name) ->
            position.takeIf { it.worldId == worldId }?.let { TravelAnchorNameEntry(it.x, it.y, it.z, name) }
        })
        if (encoded.isEmpty()) world.persistentDataContainer.remove(ANCHOR_NAMES_KEY)
        else world.persistentDataContainer.set(ANCHOR_NAMES_KEY, PersistentDataType.STRING, encoded)
    }

    private fun persistAnchorOwners(worldId: UUID) {
        val world = Bukkit.getWorld(worldId) ?: return
        val encoded = encodeTravelAnchorOwners(anchorOwners.mapNotNull { (position, owner) ->
            position.takeIf { it.worldId == worldId }?.let { TravelAnchorOwnerEntry(it.x, it.y, it.z, owner) }
        })
        if (encoded.isEmpty()) world.persistentDataContainer.remove(ANCHOR_OWNERS_KEY)
        else world.persistentDataContainer.set(ANCHOR_OWNERS_KEY, PersistentDataType.STRING, encoded)
    }

    private fun persistSharedAccess() {
        val encoded = encodeTravelAnchorAccess(sharedAccess.map { (owner, players) ->
            TravelAnchorAccessEntry(owner, players)
        })
        Bukkit.getWorlds().filter { settings?.allowsWorld(it.name) == true }.forEach { world ->
            if (encoded.isEmpty()) world.persistentDataContainer.remove(ANCHOR_ACCESS_KEY)
            else world.persistentDataContainer.set(ANCHOR_ACCESS_KEY, PersistentDataType.STRING, encoded)
        }
    }

    private fun removeAnchor(block: Block) {
        CustomBlockData(block, ARC.instance).apply {
            remove(ANCHOR_BLOCK_KEY)
            remove(ANCHOR_NAME_KEY)
            remove(ANCHOR_OWNER_KEY)
        }
        removeAnchor(TravelAnchorPosition.of(block))
    }

    private fun removeAnchor(position: TravelAnchorPosition) {
        if (!anchors.remove(position)) return
        anchorNames.remove(position)
        anchorOwners.remove(position)
        displays.values.forEach { it.remove(position)?.remove() }
        displays.entries.removeIf { it.value.isEmpty() }
        selectedTargets.entries.removeIf { it.value == position }
        persistAnchorIndex(position.worldId)
        persistAnchorNames(position.worldId)
        persistAnchorOwners(position.worldId)
    }

    private fun clearDisplays(playerId: UUID) {
        displays.remove(playerId)?.values?.forEach { if (it.isValid) it.remove() }
        clearLabel(playerId)
        selectedTargets.remove(playerId)
    }

    private fun clearDisplays(player: Player) {
        displays.remove(player.uniqueId)?.values?.forEach {
            player.hideEntity(ARC.instance, it)
            if (it.isValid) it.remove()
        }
        clearLabel(player)
        selectedTargets.remove(player.uniqueId)
    }

    private fun removeOrphanDisplays() {
        Bukkit.getWorlds().forEach { world ->
            world.getEntitiesByClass(BlockDisplay::class.java)
                .filter { "arc_travel_anchor_preview" in it.scoreboardTags }
                .forEach(BlockDisplay::remove)
            world.getEntitiesByClass(TextDisplay::class.java)
                .filter { "arc_travel_anchor_label" in it.scoreboardTags }
                .forEach(TextDisplay::remove)
        }
    }

    private fun isFeatureAvailable(player: Player): Boolean =
        settings?.let { it.enabled && it.allowsWorld(player.world.name) } == true

    private fun itemOwnerAllows(item: ItemStack?, player: Player): Boolean =
        identityAllows(item.owner(), player)

    private fun canAccess(player: Player, position: TravelAnchorPosition): Boolean =
        anchorOwners[position].let { owner ->
            travelAnchorAccessAllows(
                owner,
                player.name,
                player.uniqueId,
                owner?.let(sharedAccess::get).orEmpty(),
                ::legacyPlayerName,
            )
        }

    private fun ownsAnchor(player: Player, position: TravelAnchorPosition): Boolean =
        identityAllows(anchorOwners[position], player)

    private fun identityAllows(owner: String?, player: Player): Boolean =
        travelAnchorIdentityAllows(owner, player.name, player.uniqueId, ::legacyPlayerName)

    private fun legacyPlayerName(playerId: UUID): String? = Bukkit.getOfflinePlayer(playerId).name

    private fun displayName(identity: String): String =
        runCatching { UUID.fromString(identity) }.getOrNull()?.let(::legacyPlayerName) ?: identity

    private fun claimAnchor(block: Block, player: Player) {
        val position = TravelAnchorPosition.of(block)
        val previous = anchorOwners[position]
        if (previous != null && !identityAllows(previous, player)) return
        if (previous.equals(player.name, ignoreCase = true)) return
        CustomBlockData(block, ARC.instance).set(ANCHOR_OWNER_KEY, PersistentDataType.STRING, player.name)
        anchorOwners[position] = player.name
        previous?.let(sharedAccess::remove)?.let { inherited ->
            sharedAccess.getOrPut(player.name, ::linkedSetOf).addAll(inherited)
        }
        persistAnchorOwners(block.world.uid)
        persistSharedAccess()
    }

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
