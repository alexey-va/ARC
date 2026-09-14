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
import org.bukkit.event.player.PlayerMoveEvent
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
import ru.arc.common.ServerLocation
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.gui.ArcMenus
import ru.arc.hooks.HookRegistry
import ru.arc.network.NetworkPlayerName
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
import java.util.concurrent.CompletableFuture
import kotlin.math.cos
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val ANCHOR_BLOCK_KEY = NamespacedKey("arc", "travel_anchor")
private val ANCHOR_INDEX_KEY = NamespacedKey("arc", "travel_anchor_index")
private val ANCHOR_NAMES_KEY = NamespacedKey("arc", "travel_anchor_names")
private val ANCHOR_OWNERS_KEY = NamespacedKey("arc", "travel_anchor_owners")
private val ANCHOR_ACCESS_KEY = NamespacedKey("arc", "travel_anchor_access")
private val ANCHOR_PUBLICS_KEY = NamespacedKey("arc", "travel_anchor_publics")
private val ANCHOR_SHARED_KEY = NamespacedKey("arc", "travel_anchor_shared")
private val ANCHOR_SHAREDS_KEY = NamespacedKey("arc", "travel_anchor_shared_anchors")
private val ANCHOR_NAME_KEY = NamespacedKey("arc", "travel_anchor_name")
private val ANCHOR_OWNER_KEY = NamespacedKey("arc", "travel_anchor_owner")
private val ANCHOR_PUBLIC_KEY = NamespacedKey("arc", "travel_anchor_public")
private val ANCHOR_ITEM_KEY = NamespacedKey("arc", "travel_anchor_item")
private val SHARED_ANCHOR_ITEM_KEY = NamespacedKey("arc", "travel_anchor_shared_item")
private val STAFF_ITEM_KEY = NamespacedKey("arc", "travel_anchor_staff")
private val ITEM_OWNER_KEY = NamespacedKey("arc", "travel_anchor_item_owner")
private val NAME_INPUT = PaperDialogInputId.of("anchor_name")
private val ACCESS_INPUT = PaperDialogInputId.of("anchor_access")
private const val TELEPORT_COOLDOWN_MILLIS = 500L
private const val MAX_ANCHOR_NAME_LENGTH = 32
private const val MAX_ACCESS_INPUT_LENGTH = 1024
private const val MAX_GIVE_AMOUNT = 4096
private const val NETWORK_PAGE_SIZE = 10
private const val ADMIN_PERMISSION = "arc.travelanchors.admin"
private const val PROXY_DEPTH = 0.03f
private val RIGHT_CLICK_ACTIONS = setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)

internal data class AimCandidate<T>(
    val target: T,
    val distanceSquared: Double,
    val dot: Double,
    val minimumSelectionDot: Double? = null,
)

internal fun <T> chooseTravelAnchorTarget(
    candidates: Iterable<AimCandidate<T>>,
    maxDistanceSquared: Double,
    minimumDot: Double,
): AimCandidate<T>? =
    candidates
        .asSequence()
        .filter { it.distanceSquared <= maxDistanceSquared && it.dot >= (it.minimumSelectionDot ?: minimumDot) }
        .maxWithOrNull(compareBy<AimCandidate<T>> { it.dot }.thenBy { -it.distanceSquared })

internal fun travelAnchorScale(
    dot: Double,
    minimumVisibleDot: Double,
    minimumScale: Float,
    maximumScale: Float,
    distance: Double = Double.POSITIVE_INFINITY,
    nearbyMaximumScale: Float = maximumScale,
    nearbyDistance: Double = 0.0,
    fullScaleDistance: Double = 0.0,
): Float {
    val aimProgress = ((dot - minimumVisibleDot) / (1.0 - minimumVisibleDot)).coerceIn(0.0, 1.0)
    val distanceProgress = if (fullScaleDistance > nearbyDistance) {
        ((distance - nearbyDistance) / (fullScaleDistance - nearbyDistance)).coerceIn(0.0, 1.0)
    } else {
        1.0
    }
    val effectiveMaximum = nearbyMaximumScale + (maximumScale - nearbyMaximumScale) * distanceProgress.toFloat()
    return minimumScale + (effectiveMaximum - minimumScale) * aimProgress.toFloat()
}

internal fun travelAnchorExpandedSelectionDot(
    baseMinimumDot: Double,
    displayDistance: Double,
    scale: Float,
): Double {
    val baseAngle = acos(baseMinimumDot.coerceIn(-1.0, 1.0))
    val halfDiagonal = scale.toDouble() / sqrt(2.0)
    val apparentRadius = atan2(halfDiagonal, displayDistance.coerceAtLeast(0.01))
    return cos((baseAngle + apparentRadius).coerceAtMost(Math.PI))
}

internal fun travelAnchorAdminAllows(isAdmin: Boolean, ordinaryAccess: Boolean): Boolean = isAdmin || ordinaryAccess

internal fun travelAnchorAccessDecision(shared: Boolean, isAdmin: Boolean, ordinaryAccess: Boolean): Boolean =
    shared || travelAnchorAdminAllows(isAdmin, ordinaryAccess)

internal fun travelAnchorEditDecision(shared: Boolean, isAdmin: Boolean, ownerMatches: Boolean): Boolean =
    if (shared) isAdmin else travelAnchorAdminAllows(isAdmin, ownerMatches)

internal fun parseTravelAnchorGiveAmount(raw: String?): Int? =
    (raw ?: "1").toIntOrNull()?.takeIf { it in 1..MAX_GIVE_AMOUNT }

internal fun travelAnchorElevatorTargetY(sourceY: Int, candidateYs: Iterable<Int>, upward: Boolean): Int? =
    candidateYs
        .filter { if (upward) it > sourceY else it < sourceY }
        .minByOrNull { kotlin.math.abs(it - sourceY) }

internal fun travelAnchorTargetMessage(hasAnchorBelow: Boolean, staffHeld: Boolean): String? =
    when {
        hasAnchorBelow -> "target-anchor"
        staffHeld -> "target-staff"
        else -> null
    }

internal fun <T> travelAnchorSneakTarget(aimed: T?, elevatorDown: T?): T? = aimed ?: elevatorDown

internal fun travelAnchorDenialMessage(featureAvailable: Boolean, ownerAllowed: Boolean): String? =
    when {
        !featureAvailable -> "wrong-world"
        !ownerAllowed -> "no-permission"
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
    return names.takeIf { it.all { name -> name.matches(Regex("[A-Za-z0-9_]{3,16}")) } }
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

internal fun normalizeTravelAnchorOwner(owner: String, legacyName: (UUID) -> String?): String {
    val legacyId = runCatching { UUID.fromString(owner) }.getOrNull() ?: return owner
    return legacyName(legacyId)?.takeIf(String::isNotBlank) ?: owner
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
    val worlds: Set<String>,
    val range: Double,
    val maximumTargets: Int,
    val visibleDot: Double,
    val selectionDot: Double,
    val minimumScale: Float,
    val maximumScale: Float,
    val nearbyMaximumScale: Float,
    val nearbyDistance: Double,
    val fullScaleDistance: Double,
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
            configuredItem("anchor", anchorModelData, ownerName)
        }.withMarker(ANCHOR_ITEM_KEY).withOwner(ownerName)

    fun sharedAnchorItem(): ItemStack =
        itemStack(anchorMaterial) {
            configuredItem("anchor", anchorModelData, source.string("items.anchor.shared-owner", "Общий"))
        }.withMarker(ANCHOR_ITEM_KEY).withMarker(SHARED_ANCHOR_ITEM_KEY)

    fun staffItem(ownerName: String): ItemStack =
        itemStack(staffMaterial) {
            configuredItem("staff", staffModelData)
            glowing()
        }.withMarker(STAFF_ITEM_KEY).withOwner(ownerName)

    fun message(key: String, vararg replacements: Pair<String, String>): Component {
        var text = TextUtil.mm(source.string("messages.$key", ""), true)
        replacements.forEach { (placeholder, value) ->
            text = text.replaceText { it.matchLiteral(placeholder).replacement(Component.text(value)) }
        }
        return text
    }

    fun targetMessage(key: String, name: String, distance: String): Component =
        TextUtil.mm(source.string("messages.$key", "").replace("%distance%", distance), true)
            .replaceText { it.matchLiteral("%name%").replacement(Component.text(name)) }

    fun namingText(key: String, vararg replacements: Pair<String, String>): Component {
        var text = TextUtil.mm(source.string("naming.dialog.$key", ""), true)
        replacements.forEach { (placeholder, value) ->
            text = text.replaceText { it.matchLiteral(placeholder).replacement(Component.text(value)) }
        }
        return text.decoration(TextDecoration.ITALIC, false)
    }

    fun namingTextSafe(key: String, vararg replacements: Pair<String, Component>): Component {
        var text = TextUtil.mm(source.string("naming.dialog.$key", ""), true)
        replacements.forEach { (placeholder, value) ->
            text = text.replaceText { it.matchLiteral(placeholder).replacement(value) }
        }
        return text.decoration(TextDecoration.ITALIC, false)
    }

    fun defaultAnchorName(): String = source.string("naming.default-name", "Путевой якорь")

    private fun ItemStackDslBuilder.configuredItem(key: String, modelData: Int, ownerName: String? = null) {
        display(source.string("items.$key.name", ""))
        loreComponents(source.stringList("items.$key.lore").map { line ->
            TextUtil.mm(line, true).replaceText {
                it.matchLiteral("%owner%").replacement(Component.text(ownerName.orEmpty()))
            }
        })
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
            worlds = source.stringList("worlds").toSet(),
            range = source.real("targeting.range", 1024.0).coerceIn(8.0, 4096.0),
            maximumTargets = source.integer("targeting.maximum-targets", 24).coerceIn(1, 64),
            visibleDot = cos(Math.toRadians(visibleAngle)),
            selectionDot = cos(Math.toRadians(selectionAngle)),
            minimumScale = source.real("visual.minimum-scale", 1.04).toFloat().coerceIn(1.01f, 6.0f),
            maximumScale = source.real("visual.maximum-scale", 3.0).toFloat().coerceIn(1.01f, 6.0f),
            nearbyMaximumScale = source.real("visual.nearby-maximum-scale", 1.5).toFloat().coerceIn(1.01f, 6.0f),
            nearbyDistance = source.real("visual.nearby-distance", 6.0).coerceIn(0.0, 4096.0),
            fullScaleDistance = source.real("visual.full-scale-distance", 12.0).coerceIn(1.0, 4096.0),
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
                nearbyMaximumScale = settings.nearbyMaximumScale.coerceIn(settings.minimumScale, settings.maximumScale),
                fullScaleDistance = settings.fullScaleDistance.coerceAtLeast(settings.nearbyDistance + 1.0),
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
    private var networkStore: TravelAnchorNetworkStore? = null
    private val anchors = linkedSetOf<TravelAnchorPosition>()
    private val displays = mutableMapOf<UUID, MutableMap<TravelAnchorPosition, BlockDisplay>>()
    private val labels = mutableMapOf<UUID, TextDisplay>()
    private val anchorNames = mutableMapOf<TravelAnchorPosition, String>()
    private val anchorOwners = mutableMapOf<TravelAnchorPosition, String>()
    private val publicAnchors = mutableSetOf<TravelAnchorPosition>()
    private val sharedAnchors = mutableSetOf<TravelAnchorPosition>()
    private val sharedAccess = mutableMapOf<String, MutableSet<String>>()
    private val networkSnapshots = mutableMapOf<String, TravelAnchorNetworkSnapshot>()
    private val pendingAccessGrants = mutableSetOf<Pair<String, String>>()
    private val selectedTargets = mutableMapOf<UUID, TravelAnchorPosition>()
    private val cooldowns = mutableMapOf<UUID, Long>()
    private val pendingTeleports = mutableSetOf<UUID>()

    val isEnabled: Boolean get() = settings != null

    override fun init() {
        shutdown()
        val next = TravelAnchorConfig.load(ARC.instance.dataPath)
        settings = next
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        removeOrphanDisplays()
        Bukkit.getWorlds().filter { next.allowsWorld(it.name) }.forEach(::loadAnchorIndex)
        Bukkit.getWorlds().flatMap { it.loadedChunks.toList() }.forEach(::loadAnchors)
        ARC.redisManager?.let { redis ->
            val localServer = ARC.serverName?.takeIf(String::isNotBlank) ?: run {
                warn("TRAVEL_ANCHORS phase=REDIS reason=missing-local-server-id network catalog is local-only")
                return@let
            }
            networkStore = TravelAnchorNetworkStore(
                redis = redis,
                localServer = localServer,
                runMain = { task ->
                    if (Bukkit.isPrimaryThread()) task.run() else Tasks.scheduler.runSync(task)
                },
                onSnapshot = { snapshot -> networkSnapshots[snapshot.server.lowercase()] = snapshot },
                onAccess = { owner, players ->
                    val key = owner.lowercase()
                    if (players.isEmpty()) sharedAccess.remove(key)
                    else sharedAccess[key] = players.toMutableSet()
                    persistSharedAccess()
                },
            ).also { store ->
                store.start()
                sharedAccess.forEach { (owner, players) ->
                    val networkOwner = networkPlayerName(owner) ?: return@forEach
                    players.mapNotNull(::networkPlayerName).forEach { player -> store.grantAccess(networkOwner, player) }
                }
            }
            publishNetworkSnapshot()
        } ?: warn("TRAVEL_ANCHORS phase=REDIS reason=unavailable network catalog is local-only")
        renderTask = repeating(next.updateTicks.ticks, delay = 1.ticks) { refreshPlayers() }
        info("Travel anchors enabled: anchors={}, range={}, maximum-targets={}", anchors.size, next.range, next.maximumTargets)
    }

    override fun reload() = init()

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        renderTask?.cancel()
        renderTask = null
        networkStore?.close()
        networkStore = null
        displays.values.flatMap { it.values }.forEach { if (it.isValid) it.remove() }
        displays.clear()
        labels.values.forEach { if (it.isValid) it.remove() }
        labels.clear()
        anchorNames.clear()
        anchorOwners.clear()
        publicAnchors.clear()
        sharedAnchors.clear()
        sharedAccess.clear()
        networkSnapshots.clear()
        pendingAccessGrants.clear()
        selectedTargets.clear()
        anchors.clear()
        cooldowns.clear()
        pendingTeleports.clear()
        settings = null
    }

    fun giveAnchors(player: Player, amount: Int): Int {
        val current = settings ?: return 0
        return giveBoundItems(player, amount) { current.anchorItem(player.name) }
    }

    fun giveSharedAnchors(player: Player, amount: Int): Int {
        val current = settings ?: return 0
        return giveBoundItems(player, amount, current::sharedAnchorItem)
    }

    fun giveStaffs(player: Player, amount: Int): Int {
        val current = settings ?: return 0
        return giveBoundItems(player, amount) { current.staffItem(player.name) }
    }

    private fun giveBoundItems(player: Player, amount: Int, factory: () -> ItemStack): Int {
        var remaining = amount
        val stacks = buildList {
            while (remaining > 0) {
                val stack = factory()
                stack.amount = min(remaining, stack.maxStackSize)
                add(stack)
                remaining -= stack.amount
            }
        }
        return OpsItemHandlers.giveStacks(player, stacks, dropOverflow = true)
    }

    fun message(key: String, vararg replacements: Pair<String, String>): Component =
        settings?.message(key, *replacements) ?: Component.empty()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun guardPlace(event: BlockPlaceEvent) {
        if (!event.itemInHand.hasMarker(ANCHOR_ITEM_KEY)) return
        val shared = event.itemInHand.hasMarker(SHARED_ANCHOR_ITEM_KEY)
        val denial = travelAnchorDenialMessage(
            isFeatureAvailable(event.player),
            shared || itemOwnerAllows(event.itemInHand, event.player),
        )
        if (denial != null) {
            event.isCancelled = true
            sendDenial(event.player, denial, "%owner%" to displayName(event.itemInHand.owner() ?: "не указан"))
            return
        }
        if (!shared && !event.itemInHand.owner().equals(event.player.name, ignoreCase = true)) {
            event.itemInHand.withOwner(event.player.name)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!event.itemInHand.hasMarker(ANCHOR_ITEM_KEY)) return
        val blockData = CustomBlockData(event.blockPlaced, ARC.instance)
        val shared = event.itemInHand.hasMarker(SHARED_ANCHOR_ITEM_KEY)
        val owner = event.itemInHand.owner() ?: event.player.name
        blockData.set(ANCHOR_BLOCK_KEY, PersistentDataType.BYTE, 1.toByte())
        val position = TravelAnchorPosition.of(event.blockPlaced)
        if (shared) {
            blockData.set(ANCHOR_SHARED_KEY, PersistentDataType.BYTE, 1.toByte())
            blockData.remove(ANCHOR_OWNER_KEY)
            sharedAnchors += position
            anchorOwners.remove(position)
        } else {
            blockData.set(ANCHOR_OWNER_KEY, PersistentDataType.STRING, owner)
            anchorOwners[position] = owner
        }
        if (anchors.add(position)) persistAnchorIndex(event.blockPlaced.world.uid)
        persistAnchorOwners(event.blockPlaced.world.uid)
        persistAnchorShared(event.blockPlaced.world.uid)
        publishNetworkSnapshot()
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
        sendDenial(event.player, "no-permission", "%owner%" to displayName(anchorOwners[TravelAnchorPosition.of(event.block)] ?: "не указан"))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!isAnchor(event.block)) return
        val position = TravelAnchorPosition.of(event.block)
        val owner = anchorOwners[position] ?: event.player.name
        val shared = position in sharedAnchors
        event.isDropItems = false
        removeAnchor(event.block)
        if (event.player.gameMode != GameMode.CREATIVE) {
            (if (shared) settings?.sharedAnchorItem() else settings?.anchorItem(owner))
                ?.let { event.block.world.dropItemNaturally(event.block.location.add(0.5, 0.5, 0.5), it) }
        }
        event.player.sendActionBar(settings?.message("removed") ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val clickedAnchor = event.clickedBlock?.takeIf(::isAnchor)
        if (clickedAnchor != null) {
            val clickedPosition = TravelAnchorPosition.of(clickedAnchor)
            if (clickedPosition in publicAnchors && !canAccess(event.player, clickedPosition)) {
                event.isCancelled = true
                grantPublicAccess(event.player, clickedPosition)
                return
            }
        }
        if (event.action !in RIGHT_CLICK_ACTIONS) return
        val held = event.item ?: event.player.inventory.itemInMainHand
        val staffHeld = held.hasMarker(STAFF_ITEM_KEY)
        if (staffHeld && itemOwnerAllows(held, event.player) && !held.owner().equals(event.player.name, ignoreCase = true)) {
            held.withOwner(event.player.name)
        }
        when (travelAnchorInteraction(clickedAnchor != null, staffHeld)) {
            TravelAnchorInteraction.IGNORE -> return
            TravelAnchorInteraction.RENAME -> {
                event.isCancelled = true
                val position = TravelAnchorPosition.of(checkNotNull(clickedAnchor))
                val denial = travelAnchorDenialMessage(
                    isFeatureAvailable(event.player),
                    canAccess(event.player, position),
                )
                if (denial != null) {
                    sendDenial(
                        event.player,
                        denial,
                        "%owner%" to displayName(anchorOwners[position] ?: "не указан"),
                    )
                    return
                }
                ArcMenus.beginDialogFlow(event.player)
                if (ownsAnchor(event.player, position)) {
                    claimAnchor(checkNotNull(clickedAnchor), event.player)
                    openAnchorMenu(event.player, position)
                } else {
                    openNetworkDialog(
                        event.player,
                        anchorOwners[position] ?: event.player.name,
                        returnTo = null,
                    )
                }
                return
            }
            TravelAnchorInteraction.TELEPORT -> Unit
        }
        event.isCancelled = true
        val player = event.player
        val denial = travelAnchorDenialMessage(isFeatureAvailable(player), itemOwnerAllows(held, player))
        if (denial != null) {
            sendDenial(player, denial, "%owner%" to displayName(held.owner() ?: "не указан"))
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
        val aimed = selectedTargets[player.uniqueId]
            ?.takeIf { it != source && it.worldId == player.world.uid }
            ?: selectTarget(player, source)
        val target = travelAnchorSneakTarget(aimed, findVerticalTarget(player, source, upward = false)) ?: return
        teleport(player, target)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        if (to.y <= event.from.y + 0.01 || event.player.velocity.y <= 0.0) return
        val player = event.player
        if (!isFeatureAvailable(player)) return
        val source = anchorBelow(player, event.from) ?: return
        findVerticalTarget(player, source, upward = true)?.let { teleport(player, it) }
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
        Bukkit.getOnlinePlayers().forEach(::refreshPlayer)
    }

    private fun refreshPlayer(player: Player) {
        val current = settings ?: return
        if (!isFeatureAvailable(player)) {
            clearDisplays(player)
            return
        }
        grantPublicAccessAtFeet(player)
        val source = anchorBelow(player)
        val staff = player.inventory.itemInMainHand
        val staffHeld = staff.hasMarker(STAFF_ITEM_KEY) && itemOwnerAllows(staff, player)
        if (source == null && !staffHeld) {
            clearDisplays(player)
            return
        }
        val candidates = visibleCandidates(player, source)
        val selected = chooseTravelAnchorTarget(candidates, current.range * current.range, current.selectionDot)?.target
        render(player, candidates, selected)
        if (selected != null) {
            selectedTargets[player.uniqueId] = selected
            val distance = candidates.first { it.target == selected }.distanceSquared.let(Math::sqrt).roundToInt().toString()
            val message = travelAnchorTargetMessage(source != null, staffHeld) ?: return
            val targetName = anchorNames[selected] ?: current.defaultAnchorName()
            player.sendActionBar(current.targetMessage(message, targetName, distance))
        } else {
            selectedTargets.remove(player.uniqueId)
            clearLabel(player)
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
                val dot = direction.dot(delta.normalize())
                val actualDistance = sqrt(distanceSquared)
                val scale = travelAnchorScale(
                    dot,
                    current.visibleDot,
                    current.minimumScale,
                    current.maximumScale,
                    actualDistance,
                    current.nearbyMaximumScale,
                    current.nearbyDistance,
                    current.fullScaleDistance,
                )
                val displayDistance = travelAnchorDisplayDistance(actualDistance, current.proxyDistance)
                AimCandidate(
                    position,
                    distanceSquared,
                    dot,
                    travelAnchorExpandedSelectionDot(current.selectionDot, displayDistance, scale),
                )
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
            val scale = travelAnchorScale(
                candidate.dot,
                current.visibleDot,
                current.minimumScale,
                current.maximumScale,
                actualDistance,
                current.nearbyMaximumScale,
                current.nearbyDistance,
                current.fullScaleDistance,
            )
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

    private fun openAnchorMenu(player: Player, position: TravelAnchorPosition) {
        val current = settings ?: return
        val block = editableAnchor(player, position) ?: return
        val shared = position in sharedAnchors
        val owner = anchorOwners[position] ?: player.name
        val world = block.world.name
        val state = current.namingText(
            when {
                shared -> "shared-state"
                position in publicAnchors -> "public-state"
                else -> "private-state"
            },
        )
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "travel-anchors.settings",
                title = current.namingText("title"),
                body = listOf(PaperDialogBody(current.namingTextSafe(
                    "overview",
                    "%name%" to Component.text(anchorNames[position] ?: current.defaultAnchorName()),
                    "%owner%" to Component.text(if (shared) "Общий" else displayName(owner)),
                    "%server%" to Component.text(ARC.serverName.orEmpty()),
                    "%world%" to Component.text(world),
                    "%coords%" to Component.text("${position.x}, ${position.y}, ${position.z}"),
                    "%state%" to state,
                ), width = 420)),
                buttons = buildList {
                    add(anchorDialogButton("edit_name", current.namingText("name-button")) { openNameDialog(it.player, position) })
                    if (!shared) {
                        add(anchorDialogButton("edit_access", current.namingText("access-button")) { openAccessDialog(it.player, position) })
                        add(anchorDialogButton("edit_public", current.namingText("public-button")) { openPublicDialog(it.player, position) })
                    }
                    add(anchorDialogButton("view_network", current.namingText("network-button")) { openNetworkDialog(it.player, owner, position) })
                },
                exitButton = anchorDialogButton("close_anchor_menu", current.namingText("close"), close = true) {},
                columns = 2,
            ),
        )
    }

    private fun openNameDialog(player: Player, position: TravelAnchorPosition, invalid: Boolean = false, initial: String? = null) {
        val current = settings ?: return
        if (editableAnchor(player, position) == null) return
        val value = initial ?: anchorNames[position].orEmpty()
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "travel-anchors.name",
            title = current.namingText("name-title"),
            body = listOf(PaperDialogBody(current.namingText("name-body"), 380)) +
                if (invalid) listOf(PaperDialogBody(current.namingText("invalid", "%limit%" to MAX_ANCHOR_NAME_LENGTH.toString()), 380)) else emptyList(),
            inputs = listOf(PaperDialogTextInput(NAME_INPUT, current.namingText("input"), value, 380, MAX_ANCHOR_NAME_LENGTH)),
            buttons = listOf(anchorDialogButton("save_anchor_name", current.namingText("save")) { context ->
                val name = normalizeTravelAnchorName(context.text(NAME_INPUT).orEmpty())
                if (name == null) {
                    openNameDialog(context.player, position, invalid = true, initial = context.text(NAME_INPUT).orEmpty())
                    return@anchorDialogButton
                }
                val block = editableAnchor(context.player, position) ?: return@anchorDialogButton
                setAnchorName(block, name)
                context.player.sendActionBar(current.message("renamed"))
                openAnchorMenu(context.player, position)
            }),
            exitButton = anchorDialogButton("back_from_anchor_name", current.namingText("back")) { openAnchorMenu(it.player, position) },
        ))
    }

    private fun openAccessDialog(player: Player, position: TravelAnchorPosition, invalid: Boolean = false, initial: String? = null) {
        val current = settings ?: return
        if (editableAnchor(player, position) == null) return
        val owner = anchorOwners[position] ?: player.name
        val value = initial ?: accessFor(owner).map(::displayName).sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(", ")
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "travel-anchors.access",
            title = current.namingText("access-title"),
            body = listOf(PaperDialogBody(current.namingText("access-body"), 420)) +
                if (invalid) {
                    listOf(PaperDialogBody(current.namingTextSafe(
                        "access-invalid",
                        "%players%" to Component.text(value.take(128)),
                    ), 420))
                } else {
                    emptyList()
                },
            inputs = listOf(PaperDialogTextInput(ACCESS_INPUT, current.namingText("access"), value, 420, MAX_ACCESS_INPUT_LENGTH)),
            buttons = listOf(anchorDialogButton("save_anchor_access", current.namingText("save")) { context ->
                val raw = context.text(ACCESS_INPUT).orEmpty()
                val players = normalizeTravelAnchorAccessNames(raw)
                if (players == null) {
                    openAccessDialog(context.player, position, invalid = true, initial = raw)
                    return@anchorDialogButton
                }
                if (editableAnchor(context.player, position) == null) return@anchorDialogButton
                val editor = context.player
                setSharedAccess(owner, players.mapTo(linkedSetOf()) { it.lowercase() }).whenComplete { synced, failure ->
                    if (!ARC.instance.isEnabled || settings !== current) return@whenComplete
                    Tasks.scheduler.runSync(Runnable {
                        if (!ARC.instance.isEnabled || settings !== current || !editor.isOnline) return@Runnable
                        if (failure != null) {
                            warn(
                                "TRAVEL_ANCHORS phase=REDIS reason=access-save-failed owner={} editor={}",
                                owner,
                                editor.name,
                                failure,
                            )
                            editor.sendActionBar(current.message("network-unavailable"))
                        } else {
                            editor.sendActionBar(current.message(if (synced) "access-saved" else "access-saved-local"))
                        }
                        openAnchorMenu(editor, position)
                    })
                }
            }),
            exitButton = anchorDialogButton("back_from_anchor_access", current.namingText("back")) { openAnchorMenu(it.player, position) },
        ))
    }

    private fun openPublicDialog(player: Player, position: TravelAnchorPosition) {
        val current = settings ?: return
        if (editableAnchor(player, position) == null) return
        val isPublic = position in publicAnchors
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "travel-anchors.public",
            title = current.namingText("public-title"),
            body = listOf(PaperDialogBody(current.namingText(if (isPublic) "public-body-on" else "public-body-off"), 420)),
            buttons = listOf(anchorDialogButton(
                "toggle_anchor_public",
                current.namingText(if (isPublic) "make-private" else "make-public"),
            ) { context ->
                val block = editableAnchor(context.player, position) ?: return@anchorDialogButton
                setAnchorPublic(block, !isPublic)
                context.player.sendActionBar(current.message(if (isPublic) "made-private" else "made-public"))
                openPublicDialog(context.player, position)
            }),
            exitButton = anchorDialogButton("back_from_anchor_public", current.namingText("back")) { openAnchorMenu(it.player, position) },
        ))
    }

    private fun openNetworkDialog(player: Player, owner: String, returnTo: TravelAnchorPosition?, page: Int = 0) {
        val current = settings ?: return
        val entries = networkEntriesFor(owner)
        val pageCount = maxOf(1, (entries.size + NETWORK_PAGE_SIZE - 1) / NETWORK_PAGE_SIZE)
        val currentPage = page.coerceIn(0, pageCount - 1)
        val pageEntries = entries.drop(currentPage * NETWORK_PAGE_SIZE).take(NETWORK_PAGE_SIZE)
        val buttons = pageEntries.mapIndexed { index, entry ->
            val label = Component.text(entry.name.ifBlank(current::defaultAnchorName))
                .color(TextColor.color(0xFFD166))
                .append(Component.text(" · ${entry.server}/${entry.world}").color(TextColor.color(0xE8DFD2)))
            anchorDialogButton("network_anchor_${currentPage}_$index", label, close = true) { teleportNetwork(it.player, entry) }
        } + buildList {
            if (currentPage > 0) {
                add(anchorDialogButton("network_previous", current.namingText("previous-page")) {
                    openNetworkDialog(it.player, owner, returnTo, currentPage - 1)
                })
            }
            if (currentPage + 1 < pageCount) {
                add(anchorDialogButton("network_next", current.namingText("next-page")) {
                    openNetworkDialog(it.player, owner, returnTo, currentPage + 1)
                })
            }
        }
        ArcMenus.openDialog(player, PaperDialogScreen(
            id = "travel-anchors.network",
            title = current.namingText("network-title"),
            body = listOf(PaperDialogBody(current.namingText(
                if (entries.isEmpty()) "network-empty" else "network-body",
                "%owner%" to displayName(owner),
                "%count%" to entries.size.toString(),
                "%page%" to (currentPage + 1).toString(),
                "%pages%" to pageCount.toString(),
            ), 500)),
            buttons = buttons,
            exitButton = returnTo?.let { position ->
                anchorDialogButton("back_from_anchor_network", current.namingText("back")) { openAnchorMenu(it.player, position) }
            } ?: anchorDialogButton("close_anchor_network", current.namingText("close"), close = true) {},
            columns = 2,
        ))
    }

    private fun anchorDialogButton(
        id: String,
        label: Component,
        close: Boolean = false,
        action: (ru.arc.paper.menu.PaperDialogClickContext) -> Unit,
    ) = PaperDialogButton(PaperDialogActionId.of(id), label, width = 210, closeDialogBeforeAction = close, onClick = action)

    private fun editableAnchor(player: Player, position: TravelAnchorPosition): Block? {
        val current = settings ?: return null
        if (!isFeatureAvailable(player)) {
            ArcMenus.closeDialog(player)
            sendDenial(player, "wrong-world")
            return null
        }
        val block = position.block()?.takeIf(::isAnchor)
        if (block == null) {
            ArcMenus.closeDialog(player)
            player.sendActionBar(current.message("unavailable"))
            return null
        }
        if (!ownsAnchor(player, position)) {
            ArcMenus.closeDialog(player)
            sendDenial(player, "no-permission", "%owner%" to displayName(anchorOwners[position] ?: "не указан"))
            return null
        }
        claimAnchor(block, player)
        return block
    }

    private fun setAnchorName(block: Block, name: String) {
        CustomBlockData(block, ARC.instance).set(ANCHOR_NAME_KEY, PersistentDataType.STRING, name)
        anchorNames[TravelAnchorPosition.of(block)] = name
        persistAnchorNames(block.world.uid)
        publishNetworkSnapshot()
    }

    private fun setAnchorPublic(block: Block, enabled: Boolean) {
        val position = TravelAnchorPosition.of(block)
        val data = CustomBlockData(block, ARC.instance)
        if (enabled) {
            data.set(ANCHOR_PUBLIC_KEY, PersistentDataType.BYTE, 1.toByte())
            publicAnchors += position
        } else {
            data.remove(ANCHOR_PUBLIC_KEY)
            publicAnchors -= position
        }
        persistAnchorPublics(block.world.uid)
        publishNetworkSnapshot()
    }

    private fun networkEntriesFor(owner: String): List<TravelAnchorNetworkEntry> {
        val server = ARC.serverName.orEmpty()
        val snapshots = networkSnapshots.toMutableMap().apply {
            put(server.lowercase(), localNetworkSnapshot())
        }
        return snapshots.values.asSequence()
            .flatMap { it.anchors.asSequence() }
            .filter { it.shared || it.owner.equals(owner, ignoreCase = true) }
            .sortedWith(compareBy(TravelAnchorNetworkEntry::server, TravelAnchorNetworkEntry::world, TravelAnchorNetworkEntry::name))
            .toList()
    }

    private fun teleportNetwork(player: Player, entry: TravelAnchorNetworkEntry) {
        val current = settings ?: return
        if (!networkEntryAllows(player, entry)) {
            sendDenial(player, "no-permission", "%owner%" to displayName(entry.owner))
            return
        }
        if (entry.server.equals(ARC.serverName, ignoreCase = true)) {
            val world = Bukkit.getWorld(entry.world)
            val position = world?.let { TravelAnchorPosition.of(it.uid, entry.x, entry.y, entry.z) }
            if (position == null || position !in anchors) {
                player.sendActionBar(current.message("unavailable"))
                return
            }
            teleport(player, position)
            return
        }
        if (player.isInsideVehicle) {
            player.sendActionBar(current.message("leave-vehicle"))
            return
        }
        val now = System.currentTimeMillis()
        if (now < cooldowns.getOrDefault(player.uniqueId, 0L)) return
        val destination = ServerLocation(
            server = entry.server,
            world = entry.world,
            x = entry.x + 0.5,
            y = entry.y + 1.0,
            z = entry.z + 0.5,
            yaw = player.location.yaw,
            pitch = player.location.pitch,
        )
        if (HookRegistry.huskHomesHook?.teleport(player, destination) != true) {
            player.sendActionBar(current.message("cross-server-unavailable"))
            return
        }
        cooldowns[player.uniqueId] = now + TELEPORT_COOLDOWN_MILLIS
        playEffectsSafely("cross-server-departure") { playDepartureEffects(player.location) }
        player.sendActionBar(current.message("cross-server-started", "%server%" to entry.server))
    }

    private fun teleport(player: Player, position: TravelAnchorPosition) {
        val current = settings ?: return
        if (!canAccess(player, position)) {
            sendDenial(player, "no-permission", "%owner%" to displayName(anchorOwners[position] ?: "не указан"))
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
        playEffectsSafely("departure") { playDepartureEffects(from) }
        if (!player.teleport(destination, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            cooldowns.remove(player.uniqueId)
            player.sendActionBar(current.message("blocked"))
            return
        }
        refreshAfterTeleport(player)
        playEffectsSafely("arrival") { playArrivalEffects(player, destination) }
    }

    private fun refreshAfterTeleport(player: Player) {
        refreshPlayer(player)
        Tasks.scheduler.runLater(2, Runnable {
            if (player.isOnline && settings != null) refreshPlayer(player)
        })
    }

    private inline fun playEffectsSafely(phase: String, effects: () -> Unit) {
        runCatching(effects).onFailure {
            warn("TRAVEL_ANCHORS phase=EFFECTS reason=render-failed stage={}", phase, it)
        }
    }

    private fun playDepartureEffects(location: Location) {
        val center = location.clone().add(0.0, 1.0, 0.0)
        location.world.spawnParticle(Particle.PORTAL, center, 48, 0.35, 0.75, 0.35, 0.22)
        location.world.spawnParticle(Particle.REVERSE_PORTAL, center, 24, 0.25, 0.55, 0.25, 0.08)
        location.world.spawnParticle(Particle.END_ROD, center, 12, 0.22, 0.6, 0.22, 0.04)
        location.world.playSound(location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 0.7f, 1.35f)
    }

    private fun playArrivalEffects(player: Player, location: Location) {
        val center = location.clone().add(0.0, 0.8, 0.0)
        location.world.spawnParticle(Particle.PORTAL, center, 64, 0.4, 0.7, 0.4, 0.16)
        location.world.spawnParticle(Particle.END_ROD, center, 18, 0.28, 0.65, 0.28, 0.035)
        location.world.spawnParticle(Particle.FLASH, center, 1, Color.WHITE)
        player.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.9f, 1.15f)
        player.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.55f, 1.8f)
    }

    private fun anchorBelow(player: Player, location: Location = player.location): TravelAnchorPosition? =
        rawAnchorBelow(location)?.takeIf { canAccess(player, it) }

    private fun rawAnchorBelow(location: Location): TravelAnchorPosition? =
        listOf(0.08, 0.4).firstNotNullOfOrNull { offset ->
            val block = location.clone().subtract(0.0, offset, 0.0).block
            TravelAnchorPosition.of(block).takeIf { it in anchors && isAnchor(block) }
        }

    private fun findVerticalTarget(player: Player, source: TravelAnchorPosition, upward: Boolean): TravelAnchorPosition? =
        anchors.asSequence()
            .filter { it.worldId == source.worldId && it.x == source.x && it.z == source.z }
            .filter { canAccess(player, it) }
            .toList()
            .let { candidates ->
                val targetY = travelAnchorElevatorTargetY(source.y, candidates.map(TravelAnchorPosition::y), upward)
                    ?: return@let null
                candidates.firstOrNull { it.y == targetY }
            }

    private fun grantPublicAccessAtFeet(player: Player) {
        val position = rawAnchorBelow(player.location) ?: return
        if (position in publicAnchors && !canAccess(player, position)) grantPublicAccess(player, position)
    }

    private fun grantPublicAccess(player: Player, position: TravelAnchorPosition) {
        val current = settings ?: return
        val owner = anchorOwners[position] ?: return
        if (identityAllows(owner, player) || player.hasPermission(ADMIN_PERMISSION)) return
        val pendingKey = owner.lowercase() to player.name.lowercase()
        if (!pendingAccessGrants.add(pendingKey)) return
        val store = networkStore
        if (store == null) {
            accessForMutable(owner).add(player.name.lowercase())
            pendingAccessGrants.remove(pendingKey)
            persistSharedAccess()
            player.sendActionBar(current.message("public-access-local", "%owner%" to displayName(owner)))
            return
        }
        val networkOwner = networkPlayerName(owner)
        if (networkOwner == null) {
            pendingAccessGrants.remove(pendingKey)
            warn(
                "TRAVEL_ANCHORS phase=REDIS reason=invalid-anchor-owner owner={} player={} world={} x={} y={} z={}",
                owner,
                player.name,
                player.world.name,
                position.x,
                position.y,
                position.z,
            )
            player.sendActionBar(current.message("network-unavailable"))
            return
        }
        store.grantAccess(networkOwner, player.name).whenComplete { players, failure ->
            if (!ARC.instance.isEnabled || settings !== current) return@whenComplete
            Tasks.scheduler.runSync(Runnable {
                pendingAccessGrants.remove(pendingKey)
                if (!ARC.instance.isEnabled || settings !== current) return@Runnable
                if (failure != null) {
                    warn("TRAVEL_ANCHORS phase=REDIS reason=public-access-grant-failed owner={} player={}", owner, player.name, failure)
                    if (player.isOnline) player.sendActionBar(current.message("network-unavailable"))
                    return@Runnable
                }
                setSharedAccessLocal(owner, players)
                if (player.isOnline) player.sendActionBar(current.message("public-access-granted", "%owner%" to displayName(owner)))
            })
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
            val data = CustomBlockData(block, ARC.instance)
            val storedOwner = data.get(ANCHOR_OWNER_KEY, PersistentDataType.STRING)
            val owner = storedOwner?.takeIf(String::isNotBlank)?.let(::normalizeOwnerIdentity)
            if (owner != null && anchorOwners.put(position, owner) != owner) changed = true
            if (owner != null && owner != storedOwner) {
                data.set(ANCHOR_OWNER_KEY, PersistentDataType.STRING, owner)
                changed = true
            }
            if (CustomBlockData(block, ARC.instance).has(ANCHOR_PUBLIC_KEY, PersistentDataType.BYTE)) {
                changed = publicAnchors.add(position) || changed
            }
            if (data.has(ANCHOR_SHARED_KEY, PersistentDataType.BYTE)) {
                changed = sharedAnchors.add(position) || changed
                changed = (anchorOwners.remove(position) != null) || changed
            }
        }
        if (changed) {
            persistAnchorIndex(chunk.world.uid)
            persistAnchorNames(chunk.world.uid)
            persistAnchorOwners(chunk.world.uid)
            persistAnchorPublics(chunk.world.uid)
            persistAnchorShared(chunk.world.uid)
            publishNetworkSnapshot()
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
        var ownersChanged = false
        decodeTravelAnchorOwners(
            world.persistentDataContainer.get(ANCHOR_OWNERS_KEY, PersistentDataType.STRING).orEmpty(),
        ).forEach { entry ->
            val position = TravelAnchorPosition.of(world.uid, entry.x, entry.y, entry.z)
            if (position in anchors) {
                val owner = normalizeOwnerIdentity(entry.owner)
                anchorOwners[position] = owner
                ownersChanged = ownersChanged || owner != entry.owner
            }
        }
        decodeTravelAnchorAccess(
            world.persistentDataContainer.get(ANCHOR_ACCESS_KEY, PersistentDataType.STRING).orEmpty(),
        ).forEach { entry ->
            val owner = normalizeOwnerIdentity(entry.owner)
            accessForMutable(owner).addAll(entry.players.map(::normalizeOwnerIdentity).map(String::lowercase))
        }
        val publicCoordinates = world.persistentDataContainer
            .get(ANCHOR_PUBLICS_KEY, PersistentDataType.INTEGER_ARRAY)
            ?: IntArray(0)
        for (index in 0 until publicCoordinates.size - 2 step 3) {
            val position = TravelAnchorPosition.of(
                world.uid,
                publicCoordinates[index],
                publicCoordinates[index + 1],
                publicCoordinates[index + 2],
            )
            if (position in anchors) publicAnchors += position
        }
        val sharedCoordinates = world.persistentDataContainer
            .get(ANCHOR_SHAREDS_KEY, PersistentDataType.INTEGER_ARRAY)
            ?: IntArray(0)
        for (index in 0 until sharedCoordinates.size - 2 step 3) {
            val position = TravelAnchorPosition.of(
                world.uid,
                sharedCoordinates[index],
                sharedCoordinates[index + 1],
                sharedCoordinates[index + 2],
            )
            if (position in anchors) {
                sharedAnchors += position
                ownersChanged = (anchorOwners.remove(position) != null) || ownersChanged
            }
        }
        if (ownersChanged) persistAnchorOwners(world.uid)
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

    private fun persistAnchorPublics(worldId: UUID) {
        val world = Bukkit.getWorld(worldId) ?: return
        val positions = publicAnchors.filter { it.worldId == worldId }
        val coordinates = IntArray(positions.size * 3)
        positions.forEachIndexed { index, position ->
            coordinates[index * 3] = position.x
            coordinates[index * 3 + 1] = position.y
            coordinates[index * 3 + 2] = position.z
        }
        if (coordinates.isEmpty()) world.persistentDataContainer.remove(ANCHOR_PUBLICS_KEY)
        else world.persistentDataContainer.set(ANCHOR_PUBLICS_KEY, PersistentDataType.INTEGER_ARRAY, coordinates)
    }

    private fun persistAnchorShared(worldId: UUID) {
        val world = Bukkit.getWorld(worldId) ?: return
        val positions = sharedAnchors.filter { it.worldId == worldId }
        val coordinates = IntArray(positions.size * 3)
        positions.forEachIndexed { index, position ->
            coordinates[index * 3] = position.x
            coordinates[index * 3 + 1] = position.y
            coordinates[index * 3 + 2] = position.z
        }
        if (coordinates.isEmpty()) world.persistentDataContainer.remove(ANCHOR_SHAREDS_KEY)
        else world.persistentDataContainer.set(ANCHOR_SHAREDS_KEY, PersistentDataType.INTEGER_ARRAY, coordinates)
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

    private fun setSharedAccess(owner: String, players: Set<String>): CompletableFuture<Boolean> {
        setSharedAccessLocal(owner, players)
        val store = networkStore ?: return CompletableFuture.completedFuture(false)
        val networkOwner = networkPlayerName(owner)
            ?: return CompletableFuture.failedFuture(IllegalArgumentException("Anchor owner is not a valid player name: $owner"))
        val networkPlayers = players.mapNotNullTo(linkedSetOf(), ::networkPlayerName)
        if (networkPlayers.size != players.size) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Anchor access list contains an invalid player name"))
        }
        return store.replaceAccess(networkOwner, networkPlayers).thenApply { true }
    }

    private fun setSharedAccessLocal(owner: String, players: Set<String>) {
        val key = owner.lowercase()
        if (players.isEmpty()) sharedAccess.remove(key)
        else sharedAccess[key] = players.mapTo(linkedSetOf()) { it.lowercase() }
        persistSharedAccess()
    }

    private fun accessFor(owner: String): Set<String> = sharedAccess[owner.lowercase()].orEmpty()

    private fun accessForMutable(owner: String): MutableSet<String> =
        sharedAccess.getOrPut(owner.lowercase(), ::linkedSetOf)

    private fun localNetworkSnapshot(): TravelAnchorNetworkSnapshot {
        val server = ARC.serverName.orEmpty().lowercase()
        val current = settings
        return TravelAnchorNetworkSnapshot(
            server = server,
            anchors = anchors.mapNotNull { position ->
                val world = Bukkit.getWorld(position.worldId) ?: return@mapNotNull null
                val shared = position in sharedAnchors
                val owner = if (shared) "" else anchorOwners[position]?.let(::networkPlayerName) ?: return@mapNotNull null
                TravelAnchorNetworkEntry(
                    server = server,
                    world = world.name,
                    x = position.x,
                    y = position.y,
                    z = position.z,
                    owner = owner,
                    name = anchorNames[position] ?: current?.defaultAnchorName().orEmpty(),
                    public = position in publicAnchors,
                    shared = shared,
                )
            },
        )
    }

    private fun publishNetworkSnapshot() {
        val store = networkStore ?: return
        val snapshot = localNetworkSnapshot()
        networkSnapshots[snapshot.server.lowercase()] = snapshot
        store.publish(snapshot)
    }

    private fun removeAnchor(block: Block) {
        CustomBlockData(block, ARC.instance).apply {
            remove(ANCHOR_BLOCK_KEY)
            remove(ANCHOR_NAME_KEY)
            remove(ANCHOR_OWNER_KEY)
            remove(ANCHOR_PUBLIC_KEY)
            remove(ANCHOR_SHARED_KEY)
        }
        removeAnchor(TravelAnchorPosition.of(block))
    }

    private fun removeAnchor(position: TravelAnchorPosition) {
        if (!anchors.remove(position)) return
        anchorNames.remove(position)
        anchorOwners.remove(position)
        publicAnchors.remove(position)
        sharedAnchors.remove(position)
        displays.values.forEach { it.remove(position)?.remove() }
        displays.entries.removeIf { it.value.isEmpty() }
        selectedTargets.entries.removeIf { it.value == position }
        persistAnchorIndex(position.worldId)
        persistAnchorNames(position.worldId)
        persistAnchorOwners(position.worldId)
        persistAnchorPublics(position.worldId)
        persistAnchorShared(position.worldId)
        publishNetworkSnapshot()
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
        settings?.allowsWorld(player.world.name) == true

    private fun sendDenial(player: Player, messageKey: String, vararg replacements: Pair<String, String>) {
        player.sendActionBar(
            settings?.message(messageKey, "%world%" to player.world.name, *replacements) ?: Component.empty(),
        )
    }

    private fun itemOwnerAllows(item: ItemStack?, player: Player): Boolean =
        identityAllows(item.owner(), player)

    private fun canAccess(player: Player, position: TravelAnchorPosition): Boolean =
        travelAnchorAccessDecision(
            position in sharedAnchors,
            player.hasPermission(ADMIN_PERMISSION),
            ordinaryAccess(player, anchorOwners[position]),
        )

    private fun ownsAnchor(player: Player, position: TravelAnchorPosition): Boolean =
        travelAnchorEditDecision(
            position in sharedAnchors,
            player.hasPermission(ADMIN_PERMISSION),
            identityAllows(anchorOwners[position], player),
        )

    private fun networkEntryAllows(player: Player, entry: TravelAnchorNetworkEntry): Boolean =
        travelAnchorAccessDecision(entry.shared, player.hasPermission(ADMIN_PERMISSION), ordinaryAccess(player, entry.owner))

    private fun ordinaryAccess(player: Player, owner: String?): Boolean =
        travelAnchorAccessAllows(
            owner,
            player.name,
            player.uniqueId,
            owner?.let(::accessFor).orEmpty(),
            ::legacyPlayerName,
        )

    private fun identityAllows(owner: String?, player: Player): Boolean =
        travelAnchorIdentityAllows(owner, player.name, player.uniqueId, ::legacyPlayerName)

    private fun legacyPlayerName(playerId: UUID): String? = Bukkit.getOfflinePlayer(playerId).name

    private fun displayName(identity: String): String =
        runCatching { UUID.fromString(identity) }.getOrNull()?.let(::legacyPlayerName) ?: identity

    private fun normalizeOwnerIdentity(identity: String): String =
        normalizeTravelAnchorOwner(identity, ::legacyPlayerName)

    private fun networkPlayerName(identity: String): String? =
        NetworkPlayerName.parseOrNull(normalizeOwnerIdentity(identity))?.value

    private fun claimAnchor(block: Block, player: Player) {
        val position = TravelAnchorPosition.of(block)
        if (position in sharedAnchors) return
        val previous = anchorOwners[position]
        if (previous != null && !identityAllows(previous, player)) return
        if (previous.equals(player.name, ignoreCase = true)) return
        CustomBlockData(block, ARC.instance).set(ANCHOR_OWNER_KEY, PersistentDataType.STRING, player.name)
        anchorOwners[position] = player.name
        previous?.let { sharedAccess.remove(it.lowercase()) }?.let { inherited ->
            accessForMutable(player.name).addAll(inherited)
        }
        persistAnchorOwners(block.world.uid)
        persistSharedAccess()
        publishNetworkSnapshot()
    }

    private val VISIBLE_COLOR = Color.fromRGB(0x92, 0xBE, 0xD8)
    private val SELECTED_COLOR = Color.fromRGB(0xFF, 0xD1, 0x66)
}

object TravelAnchorSubCommand : SubCommand {
    override val configKey = "anchor"
    override val defaultName = "anchor"
    override val defaultPermission = ADMIN_PERMISSION
    override val defaultDescription = "Управление выдачей путевых якорей"
    override val defaultUsage = "/arc anchor <give|public|staff> <игрок> [количество]"

    override fun isAvailable(): Boolean = TravelAnchorsModule.isEnabled

    override fun execute(sender: org.bukkit.command.CommandSender, args: Array<String>): Boolean {
        val request = resolveTravelAnchorCommand((sender as? Player)?.name, args.toList())
        if (request == null) {
            if (args.size >= 3 && parseTravelAnchorGiveAmount(args[2]) == null) {
                sender.sendMessage(TravelAnchorsModule.message("invalid-amount"))
            }
            sendUsage(sender)
            return true
        }
        val player = getOnlinePlayer(sender, request.playerName) ?: return true
        when (request.kind) {
            TravelAnchorGiveKind.PERSONAL -> {
                TravelAnchorsModule.giveAnchors(player, request.amount)
                sender.sendMessage(TravelAnchorsModule.message("anchor-given", "%player%" to player.name, "%amount%" to request.amount.toString()))
            }
            TravelAnchorGiveKind.PUBLIC -> {
                TravelAnchorsModule.giveSharedAnchors(player, request.amount)
                sender.sendMessage(TravelAnchorsModule.message("shared-anchor-given", "%player%" to player.name, "%amount%" to request.amount.toString()))
            }
            TravelAnchorGiveKind.STAFF -> {
                TravelAnchorsModule.giveStaffs(player, request.amount)
                sender.sendMessage(TravelAnchorsModule.message("staff-given", "%player%" to player.name, "%amount%" to request.amount.toString()))
            }
        }
        return true
    }

    override fun tabComplete(sender: org.bukkit.command.CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("give", "public", "staff").tabComplete(args[0])
            2 -> tabCompletePlayers(args[1])
            3 -> listOf("1", "8", "16", "64").tabComplete(args[2])
            else -> null
        }
}

object GiveTravelAnchorSubCommand : SubCommand {
    override val configKey = "giveanchor"
    override val defaultName = "giveanchor"
    override val defaultPermission = ADMIN_PERMISSION
    override val defaultDescription = "Выдать путевые якоря"
    override val defaultUsage = "/arc giveanchor <игрок> [количество]"
    override fun isAvailable(): Boolean = TravelAnchorsModule.isEnabled

    override fun execute(sender: org.bukkit.command.CommandSender, args: Array<String>): Boolean {
        val request = resolveTravelAnchorGiveRequest((sender as? Player)?.name, args.toList())
        if (request == null) {
            if (args.getOrNull(1) != null && parseTravelAnchorGiveAmount(args[1]) == null) {
                sender.sendMessage(TravelAnchorsModule.message("invalid-amount"))
            }
            sendUsage(sender)
            return true
        }
        val player = getOnlinePlayer(sender, request.playerName) ?: return true
        TravelAnchorsModule.giveAnchors(player, request.amount)
        sender.sendMessage(TravelAnchorsModule.message("anchor-given", "%player%" to player.name, "%amount%" to request.amount.toString()))
        return true
    }

    override fun tabComplete(sender: org.bukkit.command.CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> tabCompletePlayers(args[0])
            2 -> listOf("1", "8", "16", "64").tabComplete(args[1])
            else -> null
        }
}

object GiveTravelStaffSubCommand : SubCommand {
    override val configKey = "givestaff"
    override val defaultName = "givestaff"
    override val defaultPermission = ADMIN_PERMISSION
    override val defaultDescription = "Выдать жезлы перехода"
    override val defaultUsage = "/arc givestaff <игрок> [количество]"
    override fun isAvailable(): Boolean = TravelAnchorsModule.isEnabled

    override fun execute(sender: org.bukkit.command.CommandSender, args: Array<String>): Boolean {
        val request = resolveTravelAnchorGiveRequest((sender as? Player)?.name, args.toList())
        if (request == null) {
            if (args.getOrNull(1) != null && parseTravelAnchorGiveAmount(args[1]) == null) {
                sender.sendMessage(TravelAnchorsModule.message("invalid-amount"))
            }
            sendUsage(sender)
            return true
        }
        val player = getOnlinePlayer(sender, request.playerName) ?: return true
        TravelAnchorsModule.giveStaffs(player, request.amount)
        sender.sendMessage(TravelAnchorsModule.message("staff-given", "%player%" to player.name, "%amount%" to request.amount.toString()))
        return true
    }

    override fun tabComplete(sender: org.bukkit.command.CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> tabCompletePlayers(args[0])
            2 -> listOf("1", "8", "16", "64").tabComplete(args[1])
            else -> null
        }
}

internal data class TravelAnchorGiveRequest(val playerName: String, val amount: Int)

internal enum class TravelAnchorGiveKind { PERSONAL, PUBLIC, STAFF }

internal data class TravelAnchorCommandRequest(
    val kind: TravelAnchorGiveKind,
    val playerName: String,
    val amount: Int,
)

internal fun resolveTravelAnchorCommand(senderName: String?, args: List<String>): TravelAnchorCommandRequest? {
    val kind = when (args.firstOrNull()?.lowercase()) {
        "give", "personal", "якорь" -> TravelAnchorGiveKind.PERSONAL
        "public", "shared", "общий" -> TravelAnchorGiveKind.PUBLIC
        "staff", "wand", "палка", "жезл" -> TravelAnchorGiveKind.STAFF
        else -> return null
    }
    val give = resolveTravelAnchorGiveRequest(senderName, args.drop(1)) ?: return null
    return TravelAnchorCommandRequest(kind, give.playerName, give.amount)
}

internal fun resolveTravelAnchorGiveRequest(senderName: String?, args: List<String>): TravelAnchorGiveRequest? =
    when (args.size) {
        0 -> senderName?.let { TravelAnchorGiveRequest(it, 1) }
        1 -> TravelAnchorGiveRequest(args[0], 1)
        2 -> parseTravelAnchorGiveAmount(args[1])?.let { TravelAnchorGiveRequest(args[0], it) }
        else -> null
    }
