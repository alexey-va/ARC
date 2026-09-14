package ru.arc.origin

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import dev.lone.itemsadder.api.CustomStack
import de.tr7zw.changeme.nbtapi.NBT
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Stairs
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.bukkit.util.BoundingBox
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.core.modules.EconomyModule
import ru.arc.hooks.HookRegistry
import ru.arc.npc.CitizensNpcRouteController
import ru.arc.npc.NpcRouteCell
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteEvent
import ru.arc.npc.NpcRouteProfile
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.worldcontent.BreweryTableDialogs
import ru.arc.worldcontent.ItemsAdderFurnitureRuntime
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Owns the reliable player-facing restaurant path in Origin.
 *
 * ARC is the single owner of both restaurant scenes: guest seating and dialogue,
 * waiter motion, native dialogs, payment, edible displays and recovery.
 */
object OriginDiningModule : PluginModule, Listener {
    override val name = "OriginDining"
    override val priority = 26

    private var service: OriginDiningService? = null
    private var citizensListener: OriginDiningCitizensListener? = null
    private var packetListener: OriginDiningPacketListener? = null

    override fun init() {
        OriginDiningLayout.load(ARC.instance.dataPath)
        val current = OriginDiningService()
        service = current
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            citizensListener = OriginDiningCitizensListener(current).also {
                Bukkit.getPluginManager().registerEvents(it, ARC.instance)
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            packetListener = OriginDiningPacketListener(current).also {
                PacketEvents.getAPI().eventManager.registerListener(it)
            }
        }
        current.start()
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        citizensListener?.let(HandlerList::unregisterAll)
        citizensListener = null
        packetListener?.let { PacketEvents.getAPI().eventManager.unregisterListener(it) }
        packetListener = null
        service?.close()
        service = null
    }

    /** Null means that the native service is not active in this runtime. */
    internal fun canOpen(player: Player, menu: BreweryTableDialogs.Menu): Boolean? =
        service?.canOpen(player, menu)

    /** False preserves the legacy command bridge on test and development runtimes. */
    internal fun order(
        player: Player,
        menu: BreweryTableDialogs.Menu,
        dish: BreweryTableDialogs.Dish,
    ): Boolean = service?.order(player, menu, dish) ?: false

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        // INTERACT_AT has its own Bukkit handler list. Leave it to the dedicated
        // listener below so one client packet can never seat the player twice.
        if (event is PlayerInteractAtEntityEvent) return
        if (event.hand != EquipmentSlot.HAND) return
        service?.interact(event.player, event.rightClicked, "entity")?.let { handled ->
            if (handled) event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        service?.interact(event.player, event.rightClicked, "entity-at")?.let { handled ->
            if (handled) event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        service?.interactBlock(event.player, block)?.let { handled ->
            // The server's chair handler owns the actual mount. ARC only
            // registers restaurant service and must not cancel or replace it.
            if (handled) event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        service?.quit(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        service?.dismounted(player, event.dismounted)
    }

}

private class OriginDiningPacketListener(
    private val service: OriginDiningService,
) : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType != PacketType.Play.Client.INTERACT_ENTITY) return
        val packet = WrapperPlayClientInteractEntity(event)
        if (packet.action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) return
        val player = event.getPlayer<Player>()
        service.interactMountedWaiter(player, packet.entityId)
    }
}

private class OriginDiningCitizensListener(
    private val service: OriginDiningService,
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onNpcClick(event: NPCRightClickEvent) {
        if (service.interactWaiter(event.clicker, event.npc.id)) {
            event.isCancelled = true
            event.setDelayedCancellation(true)
        }
    }
}

internal data class OriginDiningPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
) {
    fun inWorld(world: org.bukkit.World): Location = Location(world, x, y, z, yaw, 0f)
}

private data class OriginDiningBlockBounds(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
)

private data class OriginDiningAutoSeatingZone(
    val id: String,
    val guestIds: List<Int>,
    val minimumFreeSeats: Int,
    val loadChunks: Boolean,
    val bounds: OriginDiningBlockBounds,
    val excludedBlocks: Set<Triple<Int, Int, Int>>,
)

internal data class OriginDiningFurnitureSeatProfile(
    val namespacedIds: Set<String>,
    val yawOffset: Float,
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val requireTable: Boolean,
)

internal data class OriginDiningSeat(
    val id: String,
    val menu: BreweryTableDialogs.Menu,
    val seat: OriginDiningPoint,
    val dish: OriginDiningPoint,
    val waiterStop: OriginDiningPoint,
    val waiterId: Int,
    val clickedBlock: Triple<Int, Int, Int>,
    val deliveryTarget: OriginDiningDeliveryTarget = OriginDiningDeliveryTarget.TABLE,
    val dynamic: Boolean = false,
)

internal enum class OriginDiningDeliveryTarget {
    TABLE,
    INVENTORY,
}

internal object OriginDiningLayout {
    const val WORLD = "rc_origin_spawn"
    private const val DEFAULT_FORWARD_BLOCKS = 1.0
    private const val DEFAULT_VERTICAL_OFFSET_BLOCKS = 0.0

    private data class SeatTemplate(
        val id: String,
        val menu: BreweryTableDialogs.Menu,
    )

    private val templates =
        listOf(
            SeatTemplate("brewery_south_west", BreweryTableDialogs.Menu.FOOD),
            SeatTemplate("brewery_south_east", BreweryTableDialogs.Menu.FOOD),
            SeatTemplate("brewery_fire", BreweryTableDialogs.Menu.DRINKS),
            SeatTemplate("brewery_west", BreweryTableDialogs.Menu.COURTYARD),
            SeatTemplate("restaurant_a", BreweryTableDialogs.Menu.RESTAURANT),
            SeatTemplate("restaurant_b", BreweryTableDialogs.Menu.RESTAURANT),
        )

    var seats: List<OriginDiningSeat> = emptyList()
        private set
    var mealHitboxSize = 1.8f
        private set
    var mealHitboxYOffset = -0.15
        private set
    var waiterHitboxWidth = 1.4f
        private set
    var waiterHitboxHeight = 2.2f
        private set
    var displayViewRange = 2f
        private set
    var displayWidth = 4f
        private set
    var displayHeight = 4f
        private set
    var dynamicTableHeight = 1.1
        private set
    var dynamicWaiterSideOffset = 1.5
        private set
    var dynamicDishOffsetX = 0.0
        private set
    var dynamicDishOffsetY = 0.0
        private set
    var dynamicDishOffsetZ = 0.0
        private set
    var deliveryFallbackTicks = 0L
        private set
    var servicePauseTicks = 0L
        private set
    var nextDeliveryTicks = 0L
        private set
    var waiterPollTicks = 0L
        private set
    var waiterMaxPolls = 0
        private set
    var deliveryRouteMaxPolls = 0
        private set
    var ambientRouteMaxPolls = 0
        private set
    var waiterReturnReleaseTicks = 0L
        private set
    var waiterProgressEveryPolls = 0
        private set
    var waiterReadyMargin = 0.0
        private set
    var waiterPlayerRange = 0.0
        private set
    var navigatorDistanceMargin = 0.0
        private set
    var navigatorPathDistanceMargin = 0.0
        private set
    var navigatorGridMaxVisited = 0
        private set
    var navigatorGridPollTicks = 0L
        private set
    var navigatorGridStallPolls = 0
        private set
    var navigatorGridSnapRadius = 0
        private set
    var navigatorGridOffFloorTolerance = 0.0
        private set
    var guestMarkerLift = 0.0
        private set
    var guestEntityLift = 0.0
        private set
    var seatConfirmDelayTicks = 0L
        private set
    var seatConfirmMaxPolls = 0
        private set
    var seatConfirmHorizontalRadius = 0.0
        private set
    var theftCooldownMillis = 0L
        private set
    var sessionTtlMillis = 0L
        private set
    var dialogAuthorizationMillis = 0L
        private set
    var sessionRadius = 0.0
        private set
    var ambientDialogueMillis = 0L
        private set
    var ambientRetryMillis = 0L
        private set
    var guestReconcileMillis = 0L
        private set
    var ambientWaiterRestMillis = 0L
        private set
    private var scales = defaultScales()
    private var surfaceLifts = defaultSurfaceLifts()
    private var waiterHomes = emptyMap<Int, OriginDiningPoint>()
    private var furnitureSeatProfiles: List<OriginDiningFurnitureSeatProfile> = emptyList()
    private var npcRouteProfiles: List<NpcRouteProfile> = emptyList()

    fun load(dataPath: java.nio.file.Path) {
        val source = ConfigManager.ofModule(dataPath, "origin-dining.yml")
        source.mergeMissingFromBundled("modules/origin-dining.yml")
        val forward = source.real("placement.forward-blocks", DEFAULT_FORWARD_BLOCKS).coerceIn(0.25, 2.5)
        val vertical = source.real("placement.vertical-offset-blocks", DEFAULT_VERTICAL_OFFSET_BLOCKS).coerceIn(-1.5, 1.5)
        scales = defaultScales().mapValues { (dish, fallback) ->
            source.real("display.dishes.$dish.scale", fallback.toDouble()).toFloat().coerceIn(0.1f, 4f)
        }
        surfaceLifts = defaultSurfaceLifts().mapValues { (dish, fallback) ->
            source.real("display.dishes.$dish.surface-lift", fallback.toDouble()).toFloat().coerceIn(-1f, 2f)
        }
        mealHitboxSize = source.real("interaction.meal-hitbox-size", 1.8).toFloat().coerceIn(0.5f, 3f)
        mealHitboxYOffset = source.real("interaction.meal-hitbox-y-offset", -0.15).coerceIn(-1.0, 1.0)
        waiterHitboxWidth = source.real("interaction.waiter-hitbox-width", 1.4).toFloat().coerceIn(0.6f, 2.5f)
        waiterHitboxHeight = source.real("interaction.waiter-hitbox-height", 2.2).toFloat().coerceIn(1.2f, 3.5f)
        displayViewRange = source.real("display.view-range", 2.0).toFloat().coerceIn(0.5f, 16f)
        displayWidth = source.real("display.culling-width", 4.0).toFloat().coerceIn(0.5f, 16f)
        displayHeight = source.real("display.culling-height", 4.0).toFloat().coerceIn(0.5f, 16f)
        dynamicTableHeight = source.real("dynamic-seats.table-height-above-seat", 1.1).coerceIn(0.4, 2.0)
        dynamicWaiterSideOffset = source.real("dynamic-seats.waiter-side-offset", 0.0).coerceIn(0.0, 3.0)
        dynamicDishOffsetX = source.real("dynamic-seats.dish-offset-x", 0.0).coerceIn(-2.0, 2.0)
        dynamicDishOffsetY = source.real("dynamic-seats.dish-offset-y", 0.0).coerceIn(-2.0, 2.0)
        dynamicDishOffsetZ = source.real("dynamic-seats.dish-offset-z", 0.0).coerceIn(-2.0, 2.0)
        furnitureSeatProfiles = source.stringList("dynamic-seats.furniture-profile-ids").map { profileId ->
            val root = "dynamic-seats.furniture-profiles.$profileId"
            OriginDiningFurnitureSeatProfile(
                namespacedIds = source.stringList("$root.ids").toSet(),
                yawOffset = source.real("$root.yaw-offset", 180.0).toFloat(),
                offsetX = source.real("$root.offset-x", 0.0).coerceIn(-2.0, 2.0),
                offsetY = source.real("$root.offset-y", 0.0).coerceIn(-2.0, 2.0),
                offsetZ = source.real("$root.offset-z", 0.0).coerceIn(-2.0, 2.0),
                requireTable = source.boolean("$root.require-table", true),
            )
        }
        deliveryFallbackTicks = source.integer("timing.delivery-fallback-ticks").toLong().coerceIn(1L, 400L)
        servicePauseTicks = source.integer("timing.service-pause-ticks").toLong().coerceIn(1L, 200L)
        nextDeliveryTicks = source.integer("timing.next-delivery-ticks").toLong().coerceIn(1L, 200L)
        waiterPollTicks = source.integer("timing.waiter-poll-ticks").toLong().coerceIn(1L, 100L)
        waiterMaxPolls = source.integer("timing.waiter-max-polls").coerceIn(2, 200)
        deliveryRouteMaxPolls = source.integer("timing.delivery-route-max-polls").coerceIn(2, 300)
        ambientRouteMaxPolls = source.integer("timing.ambient-route-max-polls").coerceIn(2, 300)
        waiterReturnReleaseTicks = source.integer("timing.waiter-return-release-ticks").toLong().coerceIn(1L, 1_200L)
        waiterProgressEveryPolls = source.integer("timing.progress-log-every-polls").coerceIn(1, 100)
        theftCooldownMillis = source.integer("timing.theft-cooldown-seconds").toLong().coerceIn(0L, 3_600L) * 1_000L
        sessionTtlMillis = source.integer("timing.session-ttl-seconds").toLong().coerceIn(30L, 3_600L) * 1_000L
        dialogAuthorizationMillis = source.integer("timing.dialog-authorization-seconds").toLong().coerceIn(5L, 600L) * 1_000L
        ambientDialogueMillis = source.integer("timing.ambient-dialogue-seconds").toLong().coerceIn(5L, 600L) * 1_000L
        ambientRetryMillis = source.integer("timing.ambient-retry-seconds").toLong().coerceIn(1L, 120L) * 1_000L
        guestReconcileMillis = source.integer("timing.guest-reconcile-seconds").toLong().coerceIn(1L, 120L) * 1_000L
        ambientWaiterRestMillis = source.integer("timing.ambient-waiter-rest-seconds", 7).toLong().coerceIn(0L, 60L) * 1_000L
        waiterReadyMargin = source.real("navigation.waiter-ready-margin").coerceIn(0.5, 4.0)
        waiterPlayerRange = source.real("navigation.waiter-player-range").coerceIn(1.0, 6.0)
        navigatorDistanceMargin = source.real("navigation.distance-margin", 0.35).coerceIn(0.1, 2.0)
        navigatorPathDistanceMargin = source.real("navigation.path-distance-margin", 0.35).coerceIn(0.1, 2.0)
        navigatorGridMaxVisited = source.integer("navigation.grid-max-visited", 1_024).coerceIn(64, 4_096)
        navigatorGridPollTicks = source.integer("navigation.grid-poll-ticks", 2).toLong().coerceIn(1L, 10L)
        navigatorGridStallPolls = source.integer("navigation.grid-stall-polls", 20).coerceIn(5, 100)
        navigatorGridSnapRadius = source.integer("navigation.grid-snap-radius", 3).coerceIn(1, 6)
        navigatorGridOffFloorTolerance = source.real("navigation.grid-off-floor-tolerance", 0.45).coerceIn(0.1, 1.0)
        npcRouteProfiles = source.stringList("navigation.route-profile-ids").map { profileId ->
            val root = "navigation.route-profiles.$profileId"
            val minimum = configBlock(source.string("$root.min-block"))
            val maximum = configBlock(source.string("$root.max-block"))
            NpcRouteProfile(
                id = profileId,
                floorY = source.integer("$root.floor-y"),
                bounds = NpcRouteBounds(
                    minOf(minimum.first, maximum.first),
                    maxOf(minimum.first, maximum.first),
                    minOf(minimum.third, maximum.third),
                    maxOf(minimum.third, maximum.third),
                ),
                forbidden = source.stringList("$root.forbidden-areas").map(::parseRouteBounds),
                preferred = source.stringList("$root.preferred-areas").map(::parseRouteBounds),
                maxVisited = source.integer("$root.max-visited", navigatorGridMaxVisited).coerceIn(64, 4_096),
                snapRadius = source.integer("$root.snap-radius", navigatorGridSnapRadius).coerceIn(1, 6),
                pollTicks = source.integer("$root.poll-ticks", navigatorGridPollTicks.toInt()).toLong().coerceIn(1L, 10L),
                stallPolls = source.integer("$root.stall-polls", navigatorGridStallPolls).coerceIn(5, 100),
                offFloorTolerance = source.real("$root.off-floor-tolerance", navigatorGridOffFloorTolerance).coerceIn(0.1, 1.0),
                distanceMargin = navigatorDistanceMargin,
                pathDistanceMargin = navigatorPathDistanceMargin,
                speedModifier = source.real("$root.speed-modifier", 0.72).toFloat().coerceIn(0.1f, 2f),
            )
        }
        sessionRadius = source.real("navigation.session-radius").coerceIn(2.0, 24.0)
        guestMarkerLift = source.real("seating.guest-marker-lift").coerceIn(0.0, 2.0)
        guestEntityLift = source.real("seating.guest-entity-lift").coerceIn(-1.0, 2.0)
        seatConfirmDelayTicks = source.integer("seating.confirm-delay-ticks", 2).toLong().coerceIn(1L, 20L)
        seatConfirmMaxPolls = source.integer("seating.confirm-max-polls", 3).coerceIn(1, 10)
        seatConfirmHorizontalRadius = source.real("seating.confirm-horizontal-radius", 1.0).coerceIn(0.25, 2.0)
        seats = buildSeats(source, forward, vertical)
        waiterHomes = waiterIds.associateWith { id -> configPoint(source, "scene.waiters.$id.home") }
        OriginDiningAmbientLayout.load(source)
        info(
            "ORIGIN_DINING phase=CONFIG_LOADED forward_blocks={} vertical_offset={} transform=GROUND meal_hitbox={} seats={}",
            forward,
            vertical,
            mealHitboxSize,
            seats.size,
        )
    }

    private fun buildSeats(
        source: ru.arc.config.Config,
        forward: Double = DEFAULT_FORWARD_BLOCKS,
        vertical: Double = DEFAULT_VERTICAL_OFFSET_BLOCKS,
    ): List<OriginDiningSeat> =
        templates.map { template ->
            val prefix = "placement.tables.${template.id}"
            val seat = configPoint(source, "$prefix.seat")
            val height = source.real("$prefix.height-above-seat", 1.1)
            val offsetX = source.real("$prefix.offset-x", 0.0)
            val offsetY = source.real("$prefix.offset-y", 0.0)
            val offsetZ = source.real("$prefix.offset-z", 0.0)
            val block = configBlock(source.string("$prefix.clicked-block"))
            OriginDiningSeat(
                template.id,
                template.menu,
                seat,
                centeredDishPoint(seat, forward, height + vertical + offsetY, offsetX, offsetZ),
                configPoint(source, "$prefix.waiter-stop"),
                source.integer("$prefix.waiter-id"),
                block,
            )
        }

    internal fun configPoint(source: ru.arc.config.Config, path: String): OriginDiningPoint {
        val values = source.string(path).split(',').map(String::trim)
        require(values.size in 3..4) { "$path must be x,y,z[,yaw]" }
        return OriginDiningPoint(
            values[0].toDouble(),
            values[1].toDouble(),
            values[2].toDouble(),
            values.getOrNull(3)?.toFloat() ?: 0f,
        )
    }

    private fun configBlock(raw: String): Triple<Int, Int, Int> {
        val values = raw.split(',').map(String::trim)
        require(values.size == 3) { "clicked-block must be x,y,z" }
        return Triple(values[0].toInt(), values[1].toInt(), values[2].toInt())
    }

    private fun parseRouteBounds(raw: String): NpcRouteBounds {
        val values = raw.split(',').map(String::trim)
        require(values.size == 4) { "route area must be min-x,min-z,max-x,max-z" }
        val firstX = values[0].toInt()
        val firstZ = values[1].toInt()
        val secondX = values[2].toInt()
        val secondZ = values[3].toInt()
        return NpcRouteBounds(minOf(firstX, secondX), maxOf(firstX, secondX), minOf(firstZ, secondZ), maxOf(firstZ, secondZ))
    }

    fun routeProfile(destination: Location): NpcRouteProfile? {
        val cell = NpcRouteCell(destination.blockX, destination.blockZ)
        return npcRouteProfiles.firstOrNull { it.floorY == destination.blockY && cell in it.bounds }
    }

    internal fun centeredDishPoint(
        seat: OriginDiningPoint,
        forwardBlocks: Double,
        heightAboveSeat: Double,
        offsetX: Double = 0.0,
        offsetZ: Double = 0.0,
    ): OriginDiningPoint {
        val radians = Math.toRadians(seat.yaw.toDouble())
        return OriginDiningPoint(
            x = seat.x - sin(radians) * forwardBlocks + offsetX,
            y = seat.y + heightAboveSeat,
            z = seat.z + cos(radians) * forwardBlocks + offsetZ,
        )
    }

    fun displayLift(dishId: String): Float = surfaceLifts[dishId] ?: 0.12125f

    internal fun legacyFurnitureCleanupIds(): Set<String> = OriginDiningAmbientLayout.legacyFurnitureCleanupIds

    internal fun legacyFurnitureCleanupPoints(): List<OriginDiningPoint> = OriginDiningAmbientLayout.legacyFurnitureCleanupPoints

    internal fun dynamicMenu(location: Location): BreweryTableDialogs.Menu? {
        if (location.world?.name != WORLD) return null
        val dxBrewery = location.x - BreweryTableDialogs.BREWERY_X
        val dyBrewery = location.y - BreweryTableDialogs.BREWERY_Y
        val dzBrewery = location.z - BreweryTableDialogs.BREWERY_Z
        if (dxBrewery * dxBrewery + dyBrewery * dyBrewery + dzBrewery * dzBrewery <= BreweryTableDialogs.BREWERY_RADIUS * BreweryTableDialogs.BREWERY_RADIUS) {
            return BreweryTableDialogs.Menu.COURTYARD
        }
        val dxRestaurant = location.x - BreweryTableDialogs.RESTAURANT_X
        val dyRestaurant = location.y - BreweryTableDialogs.RESTAURANT_Y
        val dzRestaurant = location.z - BreweryTableDialogs.RESTAURANT_Z
        if (dxRestaurant * dxRestaurant + dyRestaurant * dyRestaurant + dzRestaurant * dzRestaurant <= BreweryTableDialogs.RESTAURANT_RADIUS * BreweryTableDialogs.RESTAURANT_RADIUS) {
            return BreweryTableDialogs.Menu.RESTAURANT
        }
        return null
    }

    fun itemId(dishId: String): String? =
        when (dishId) {
            "egg" -> "elitecreatures:restaurant_food_eggbeacon"
            "fish" -> "elitecreatures:restaurant_food_halffish"
            "steak" -> "elitecreatures:restaurant_food_steak"
            "herbal_tea", "berry_kvass", "spiced_mead" -> "elitecreatures:restaurant_drink"
            else -> null
        }

    val waiterIds: Set<Int> get() = seats.map(OriginDiningSeat::waiterId).toSet()

    fun waiterHome(waiterId: Int): OriginDiningPoint? = waiterHomes[waiterId]

    internal fun ambientWaiterAssignments(): Map<String, Int> =
        OriginDiningAmbientLayout.guestTables.associate { it.id to it.waiterId }

    fun waiterServes(seat: OriginDiningSeat, waiterId: Int): Boolean =
        if (seat.id.startsWith("brewery_")) waiterId == 410 || waiterId == 411
        else waiterId == 431 || waiterId == 432

    fun displayScale(dishId: String): Float = scales[dishId] ?: 0.65f

    fun furnitureSeatProfile(namespacedId: String?): OriginDiningFurnitureSeatProfile? =
        namespacedId?.let { id -> furnitureSeatProfiles.firstOrNull { id in it.namespacedIds } }

    internal fun yawToward(face: BlockFace): Float? =
        when (face) {
            BlockFace.NORTH -> 180f
            BlockFace.EAST -> -90f
            BlockFace.SOUTH -> 0f
            BlockFace.WEST -> 90f
            else -> null
        }

    private fun defaultScales() =
        mapOf(
            "egg" to 0.65f,
            "fish" to 0.65f,
            "steak" to 0.65f,
            "herbal_tea" to 0.65f,
            "berry_kvass" to 0.65f,
            "spiced_mead" to 0.65f,
        )

    private fun defaultSurfaceLifts() =
        mapOf(
            "egg" to 0.28375f,
            "fish" to 0.12125f,
            "steak" to 0.28375f,
            "herbal_tea" to 0.24475f,
            "berry_kvass" to 0.24475f,
            "spiced_mead" to 0.24475f,
        )

    internal fun stairFacing(yaw: Float): BlockFace {
        val normalized = ((yaw % 360f) + 360f) % 360f
        return when {
            normalized < 45f || normalized >= 315f -> BlockFace.NORTH
            normalized < 135f -> BlockFace.EAST
            normalized < 225f -> BlockFace.SOUTH
            else -> BlockFace.WEST
        }
    }

    internal fun stairYaw(facing: BlockFace): Float? =
        when (facing) {
            BlockFace.NORTH -> 0f
            BlockFace.EAST -> 90f
            BlockFace.SOUTH -> 180f
            BlockFace.WEST -> -90f
            else -> null
        }

    internal fun selectGuestSeats(
        guests: List<Pair<Int, OriginDiningPoint>>,
        candidates: List<OriginDiningPoint>,
        minimumFreeSeats: Int,
    ): Map<Int, OriginDiningPoint> {
        val remaining = candidates.sortedWith(compareBy(OriginDiningPoint::x, OriginDiningPoint::y, OriginDiningPoint::z)).toMutableList()
        val assignmentCount = minOf(guests.size, (remaining.size - minimumFreeSeats).coerceAtLeast(0))
        return buildMap {
            guests.take(assignmentCount).forEach { (npcId, fallback) ->
                val selected = remaining.minWithOrNull(
                    compareBy<OriginDiningPoint> { candidate ->
                        val dx = candidate.x - fallback.x
                        val dy = candidate.y - fallback.y
                        val dz = candidate.z - fallback.z
                        dx * dx + dy * dy + dz * dz
                    }.thenBy(OriginDiningPoint::x).thenBy(OriginDiningPoint::y).thenBy(OriginDiningPoint::z),
                ) ?: return@forEach
                put(npcId, selected)
                remaining.remove(selected)
            }
        }
    }
}

private enum class OriginDiningPhase {
    SEATED,
    ORDERED,
    SERVED,
}

private data class OriginDiningSession(
    val id: UUID,
    val playerId: UUID,
    val seat: OriginDiningSeat,
    var chairVehicleId: UUID,
    var mounted: Boolean,
    var phase: OriginDiningPhase,
    var touchedAt: Long,
)

private data class OriginDiningMeal(
    val sessionId: UUID,
    val ownerId: UUID,
    val seat: OriginDiningSeat,
    val dish: BreweryTableDialogs.Dish,
    val display: ItemDisplay,
    val hitbox: Interaction,
)

private data class OriginDiningDelivery(
    val sessionId: UUID,
    val dish: BreweryTableDialogs.Dish,
)

private data class OriginDiningWaiterHitbox(
    val sessionId: UUID,
    val waiterId: Int,
    val entity: Interaction,
)

private data class OriginDiningGuestSeat(
    val npcId: Int,
    val seat: OriginDiningPoint,
)

private data class OriginDiningGuestTable(
    val id: String,
    val npcId: Int,
    val waiterId: Int,
    val meal: OriginDiningPoint,
    val waiterStop: OriginDiningPoint,
    val transit: OriginDiningPoint? = null,
)

private data class OriginDiningGuestMeal(
    val table: OriginDiningGuestTable,
    val dish: BreweryTableDialogs.Dish,
    val display: ItemDisplay,
    val hitbox: Interaction,
)

private data class OriginDiningAmbientRoute(
    val token: UUID,
    val table: OriginDiningGuestTable,
    val dish: BreweryTableDialogs.Dish,
    val waypoints: List<Location>,
    var waypoint: Int = 0,
)

private data class OriginDiningDialogue(
    val firstNpcId: Int,
    val secondNpcId: Int,
    val firstLine: String,
    val secondLine: String,
)

private object OriginDiningAmbientLayout {
    private var configuredGuestSeats: List<OriginDiningGuestSeat> = emptyList()
    private var configuredGuestTables: List<OriginDiningGuestTable> = emptyList()
    var guestSeats: List<OriginDiningGuestSeat> = emptyList()
        private set
    var guestTables: List<OriginDiningGuestTable> = emptyList()
        private set
    var dialogue: List<OriginDiningDialogue> = emptyList()
        private set
    var cleanupPoints: List<OriginDiningPoint> = emptyList()
        private set
    var authoredSeatIds: Set<Int> = emptySet()
        private set
    var authoredSeatPoints: List<OriginDiningPoint> = emptyList()
        private set
    var seatLayoutVersion = 0
        private set
    var retiredSeatBlocks: List<Triple<Int, Int, Int>> = emptyList()
        private set
    var autoSeatingZones: List<OriginDiningAutoSeatingZone> = emptyList()
        private set
    var legacyFurnitureCleanupIds: Set<String> = emptySet()
        private set
    var legacyFurnitureCleanupPoints: List<OriginDiningPoint> = emptyList()
        private set
    lateinit var legacyMealHitbox: OriginDiningPoint
        private set
    lateinit var legacyMealDisplay: OriginDiningPoint
        private set
    lateinit var legacyOrderLabel: OriginDiningPoint
        private set
    var cycleSeconds = 0
        private set
    var routesPerCycle = 1
        private set

    fun load(source: ru.arc.config.Config) {
        configuredGuestSeats =
            source.stringList("scene.guest-ids").map { rawId ->
                val id = rawId.toInt()
                OriginDiningGuestSeat(id, OriginDiningLayout.configPoint(source, "scene.guests.$id.seat"))
            }
        guestSeats = configuredGuestSeats
        configuredGuestTables =
            source.stringList("scene.ambient-table-ids").map { id ->
                val root = "scene.ambient-tables.$id"
                val transit = source.string("$root.transit", "").trim()
                OriginDiningGuestTable(
                    id = id,
                    npcId = source.integer("$root.guest-id"),
                    waiterId = source.integer("$root.waiter-id"),
                    meal = OriginDiningLayout.configPoint(source, "$root.meal"),
                    waiterStop = OriginDiningLayout.configPoint(source, "$root.waiter-stop"),
                    transit = transit.takeIf(String::isNotEmpty)?.let { parsePoint(it, "$root.transit") },
                )
            }
        guestTables = configuredGuestTables
        dialogue =
            source.stringList("scene.dialogue-ids").map { id ->
                val root = "scene.dialogues.$id"
                OriginDiningDialogue(
                    source.integer("$root.first-npc-id"),
                    source.integer("$root.second-npc-id"),
                    source.string("$root.first-line"),
                    source.string("$root.second-line"),
                )
            }
        cleanupPoints = source.stringList("scene.cleanup-points").mapIndexed { index, raw -> parsePoint(raw, "scene.cleanup-points[$index]") }
        authoredSeatIds = source.stringList("scene.authored-seat-ids").map(String::toInt).toSet()
        authoredSeatPoints = source.stringList("scene.authored-seat-points").mapIndexed { index, raw ->
            parsePoint(raw, "scene.authored-seat-points[$index]")
        }
        seatLayoutVersion = source.integer("scene.seat-layout-version").coerceAtLeast(0)
        retiredSeatBlocks = source.stringList("scene.retired-seat-blocks").map(::parseBlock)
        legacyFurnitureCleanupIds = source.stringList("scene.legacy-furniture-cleanup.ids").toSet()
        legacyFurnitureCleanupPoints = source.stringList("scene.legacy-furniture-cleanup.points").mapIndexed { index, raw ->
            parsePoint(raw, "scene.legacy-furniture-cleanup.points[$index]")
        }
        autoSeatingZones = source.stringList("scene.auto-seating.zone-ids").map { id ->
            val root = "scene.auto-seating.zones.$id"
            val autoMin = parseBlock(source.string("$root.min-block"))
            val autoMax = parseBlock(source.string("$root.max-block"))
            OriginDiningAutoSeatingZone(
                id = id,
                guestIds = source.stringList("$root.guest-ids").map(String::toInt),
                minimumFreeSeats = source.integer("$root.minimum-free-seats", 1).coerceIn(0, 64),
                loadChunks = source.boolean("$root.load-chunks", true),
                bounds = OriginDiningBlockBounds(
                    minOf(autoMin.first, autoMax.first),
                    minOf(autoMin.second, autoMax.second),
                    minOf(autoMin.third, autoMax.third),
                    maxOf(autoMin.first, autoMax.first),
                    maxOf(autoMin.second, autoMax.second),
                    maxOf(autoMin.third, autoMax.third),
                ),
                excludedBlocks = source.stringList("$root.excluded-blocks").map(::parseBlock).toSet(),
            )
        }
        legacyMealHitbox = OriginDiningLayout.configPoint(source, "scene.legacy-player-table.meal-hitbox")
        legacyMealDisplay = OriginDiningLayout.configPoint(source, "scene.legacy-player-table.meal-display")
        legacyOrderLabel = OriginDiningLayout.configPoint(source, "scene.legacy-player-table.order-label")
        cycleSeconds = source.integer("scene.ambient-cycle-seconds").coerceIn(8, 120)
        routesPerCycle = source.integer("scene.ambient-routes-per-cycle", 4).coerceIn(1, 4)
    }

    fun applyAutoSeating(
        assignments: Map<Int, OriginDiningPoint>,
        tables: Map<Int, OriginDiningGuestTable>,
    ) {
        val dynamicIds = autoSeatingZones.flatMap(OriginDiningAutoSeatingZone::guestIds).toSet()
        guestSeats = configuredGuestSeats.filterNot { it.npcId in dynamicIds } + assignments.map { (npcId, seat) -> OriginDiningGuestSeat(npcId, seat) }
        guestTables = configuredGuestTables.mapNotNull { table ->
            when {
                table.npcId !in dynamicIds -> table
                table.npcId in assignments -> tables[table.npcId] ?: table
                else -> null
            }
        }
    }

    private fun parsePoint(raw: String, path: String): OriginDiningPoint {
        val values = raw.split(',').map(String::trim)
        require(values.size in 3..4) { "$path must be x,y,z[,yaw]" }
        return OriginDiningPoint(values[0].toDouble(), values[1].toDouble(), values[2].toDouble(), values.getOrNull(3)?.toFloat() ?: 0f)
    }

    private fun parseBlock(raw: String): Triple<Int, Int, Int> {
        val values = raw.split(',').map(String::trim)
        require(values.size == 3) { "scene.retired-seat-blocks entries must be x,y,z" }
        return Triple(values[0].toInt(), values[1].toInt(), values[2].toInt())
    }
}

private class OriginDiningService : AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val routeController = CitizensNpcRouteController(::logRouteEvent)
    private val seatsById = OriginDiningLayout.seats.associateBy(OriginDiningSeat::id)
    private val sessions = mutableMapOf<UUID, OriginDiningSession>()
    private val occupants = mutableMapOf<String, UUID>()
    private val meals = mutableMapOf<String, OriginDiningMeal>()
    private val mealEntity = mutableMapOf<UUID, OriginDiningMeal>()
    private val clickAt = mutableMapOf<UUID, Long>()
    private val dialogAuthorizations = mutableMapOf<UUID, Pair<UUID, Long>>()
    private val theftCooldownUntil = mutableMapOf<UUID, Long>()
    private val waiterApproaches = mutableMapOf<Int, UUID>()
    private val waiterReadyFor = mutableMapOf<Int, UUID>()
    private val waiterAssignments = mutableMapOf<Int, UUID>()
    private val deliveryQueues = mutableMapOf<Int, ArrayDeque<OriginDiningDelivery>>()
    private val activeDeliveries = mutableMapOf<Int, UUID>()
    private val waiterHeldItems = mutableMapOf<Int, ItemStack?>()
    private val waiterGlowingFor = mutableMapOf<Int, UUID>()
    private val waiterHitboxes = mutableMapOf<Int, OriginDiningWaiterHitbox>()
    private val waiterHitboxEntity = mutableMapOf<UUID, OriginDiningWaiterHitbox>()
    private val pendingSeatAttempts = mutableMapOf<UUID, UUID>()
    private val guestMarkers = mutableMapOf<Int, ArmorStand>()
    private val guestMeals = mutableMapOf<String, OriginDiningGuestMeal>()
    private val guestMealEntity = mutableMapOf<UUID, OriginDiningGuestMeal>()
    private val ambientRoutes = mutableMapOf<Int, OriginDiningAmbientRoute>()
    private val ambientWaiterAvailableAt = mutableMapOf<Int, Long>()
    private val ambientTableDueAt = mutableMapOf<String, Long>()
    private val speechDisplays = mutableSetOf<TextDisplay>()
    private var ambientCursor = 0
    private var dialogueCursor = 0
    private var nextAmbientAt = 0L
    private var nextDialogueAt = 0L
    private var nextGuestSeatReconcileAt = 0L

    fun start() {
        info(
            "ORIGIN_DINING phase=STARTING world={} seats={} waiter_ids={} route_timeout_ticks={} session_ttl_ms={}",
            OriginDiningLayout.WORLD,
            seatsById.size,
            OriginDiningLayout.waiterIds.sorted().joinToString(","),
            OriginDiningLayout.deliveryRouteMaxPolls * OriginDiningLayout.waiterPollTicks,
            OriginDiningLayout.sessionTtlMillis,
        )
        reconcileAuthoredSeatBlocks()
        resolveAutoGuestSeats()
        cleanupWorldSeats()
        cleanupAmbientLegacy()
        tasks.runLater(20L) {
            reconcileGuestSeats()
            seedGuestMeals()
            resetIdleWaiters("service-start")
        }
        tasks.runTimer(20L, 20L, ::reconcile)
        info(
            "ORIGIN_DINING phase=READY world={} placement=world-stairs ambient_owner=arc guest_seats={} guest_tables={}",
            OriginDiningLayout.WORLD,
            OriginDiningAmbientLayout.guestSeats.size,
            OriginDiningAmbientLayout.guestTables.size,
        )
    }

    private fun reconcileAuthoredSeatBlocks() {
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD) ?: return
        val migrationKey = NamespacedKey(ARC.instance, "origin_dining_seat_layout_version")
        val storedVersion = world.persistentDataContainer.get(migrationKey, PersistentDataType.INTEGER) ?: 0
        val migrate = storedVersion < OriginDiningAmbientLayout.seatLayoutVersion
        if (!migrate) {
            info(
                "ORIGIN_DINING phase=SEAT_BLOCKS_RECONCILED placed=0 cleared=0 skipped=0 migrated=false version={}",
                OriginDiningAmbientLayout.seatLayoutVersion,
            )
            return
        }
        val authoredSeatPoints = (
            OriginDiningAmbientLayout.guestSeats
                .filter { it.npcId in OriginDiningAmbientLayout.authoredSeatIds }
                .map(OriginDiningGuestSeat::seat) + OriginDiningAmbientLayout.authoredSeatPoints
            ).distinctBy { Triple(floor(it.x).toInt(), it.y.toInt(), floor(it.z).toInt()) }
        val targetBlocks = authoredSeatPoints.associateWith { seat ->
            world.getBlockAt(floor(seat.x).toInt(), seat.y.toInt(), floor(seat.z).toInt())
        }
        val blockedTargets = targetBlocks.filterValues {
            !it.type.isAir && it.type != Material.OAK_STAIRS && it.type != Material.BARRIER
        }
        if (blockedTargets.isNotEmpty()) {
            blockedTargets.forEach { (seat, block) ->
                warn("ORIGIN_DINING phase=SEAT_BLOCK_SKIPPED reason=target-occupied target={} material={}", point(seat), block.type)
            }
            warn("ORIGIN_DINING phase=SEAT_BLOCKS_RECONCILED status=blocked targets={}", blockedTargets.size)
            return
        }

        var cleared = 0
        var placed = 0
        var skipped = 0
        OriginDiningAmbientLayout.retiredSeatBlocks.forEach { (x, y, z) ->
            val block = world.getBlockAt(x, y, z)
            if (block.type == Material.OAK_STAIRS && block.blockData is Stairs) {
                block.setType(Material.AIR, false)
                cleared++
            } else if (!block.type.isAir) {
                skipped++
                warn("ORIGIN_DINING phase=SEAT_BLOCK_SKIPPED reason=retired-block-changed target={},{},{} material={}", x, y, z, block.type)
            }
        }
        targetBlocks.forEach { (seat, block) ->
                val desired = Material.OAK_STAIRS.createBlockData() as Stairs
                desired.facing = OriginDiningLayout.stairFacing(seat.yaw)
                desired.half = Bisected.Half.BOTTOM
                desired.shape = Stairs.Shape.STRAIGHT
                desired.isWaterlogged = false
                if (block.blockData.asString != desired.asString) {
                    block.setBlockData(desired, false)
                    placed++
                }
        }
        world.persistentDataContainer.set(migrationKey, PersistentDataType.INTEGER, OriginDiningAmbientLayout.seatLayoutVersion)
        info(
            "ORIGIN_DINING phase=SEAT_BLOCKS_RECONCILED placed={} cleared={} skipped={} migrated={} version={}",
            placed,
            cleared,
            skipped,
            migrate,
            OriginDiningAmbientLayout.seatLayoutVersion,
        )
    }

    private fun resolveAutoGuestSeats() {
        if (OriginDiningAmbientLayout.autoSeatingZones.isEmpty()) return
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD) ?: return
        val configured = OriginDiningAmbientLayout.guestSeats.associateBy(OriginDiningGuestSeat::npcId)
        val assignments = linkedMapOf<Int, OriginDiningPoint>()
        val tables = linkedMapOf<Int, OriginDiningGuestTable>()
        OriginDiningAmbientLayout.autoSeatingZones.forEach { zone ->
            val bounds = zone.bounds
            var loadedChunks = 0
            for (chunkX in (bounds.minX shr 4)..(bounds.maxX shr 4)) {
                for (chunkZ in (bounds.minZ shr 4)..(bounds.maxZ shr 4)) {
                    if (!world.isChunkLoaded(chunkX, chunkZ) && zone.loadChunks && world.isChunkGenerated(chunkX, chunkZ)) {
                        world.getChunkAt(chunkX, chunkZ)
                        loadedChunks++
                    }
                }
            }
            val candidates = buildList {
                for (x in bounds.minX..bounds.maxX) {
                    for (z in bounds.minZ..bounds.maxZ) {
                        if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
                        for (y in bounds.minY..bounds.maxY) {
                            val block = world.getBlockAt(x, y, z)
                            if (Triple(x, y, z) in zone.excludedBlocks) continue
                            val stairs = block.blockData as? Stairs ?: continue
                            if (stairs.half != Bisected.Half.BOTTOM) continue
                            val yaw = OriginDiningLayout.stairYaw(stairs.facing) ?: continue
                            val front = block.getRelative(stairs.facing.oppositeFace)
                            if (front.type.isAir && !hasFurnitureAt(front)) continue
                            add(OriginDiningPoint(x + 0.5, y.toDouble(), z + 0.5, yaw))
                        }
                    }
                }
            }.toMutableList()
            val furnitureRoots = linkedMapOf<UUID, Pair<Entity, OriginDiningFurnitureSeatProfile>>()
            world.getNearbyEntities(
                BoundingBox(
                    bounds.minX.toDouble(),
                    bounds.minY.toDouble(),
                    bounds.minZ.toDouble(),
                    bounds.maxX + 1.0,
                    bounds.maxY + 2.0,
                    bounds.maxZ + 1.0,
                ),
            ).forEach { entity ->
                val handle = ItemsAdderFurnitureRuntime.inspect(entity) ?: return@forEach
                val profile = OriginDiningLayout.furnitureSeatProfile(handle.namespacedId) ?: return@forEach
                furnitureRoots.putIfAbsent(handle.root.uniqueId, handle.root to profile)
            }
            furnitureRoots.values.forEach { (root, profile) ->
                val seat = furnitureSeatPoint(root, profile) ?: return@forEach
                val block = Triple(floor(seat.x).toInt(), floor(seat.y).toInt(), floor(seat.z).toInt())
                if (block in zone.excludedBlocks) return@forEach
                candidates += seat
            }
            val guests = zone.guestIds.mapNotNull { npcId -> configured[npcId]?.let { npcId to it.seat } }
            val zoneAssignments =
                if (candidates.isEmpty()) {
                    warn(
                        "ORIGIN_DINING phase=AUTO_GUEST_SEATING zone={} status=empty reason=no-candidates loaded_chunks={} bounds={},{},{}:{},{},{}",
                        zone.id,
                        loadedChunks,
                        bounds.minX,
                        bounds.minY,
                        bounds.minZ,
                        bounds.maxX,
                        bounds.maxY,
                        bounds.maxZ,
                    )
                    emptyMap()
                } else {
                    OriginDiningLayout.selectGuestSeats(guests, candidates, zone.minimumFreeSeats)
                }
            assignments.putAll(zoneAssignments)
            zoneAssignments.forEach { (npcId, seat) ->
                val original = OriginDiningAmbientLayout.guestTables.firstOrNull { it.npcId == npcId }
                if (original != null && candidates.isNotEmpty()) {
                    val block = world.getBlockAt(floor(seat.x).toInt(), seat.y.toInt(), floor(seat.z).toInt())
                    tables[npcId] = original.copy(
                        meal = OriginDiningLayout.centeredDishPoint(seat, 1.0, OriginDiningLayout.dynamicTableHeight),
                        waiterStop = dynamicWaiterStop(block, seat),
                    )
                } else if (original != null) {
                    tables[npcId] = original
                }
            }
            info(
                "ORIGIN_DINING phase=AUTO_GUEST_SEATING zone={} status=ready loaded_chunks={} candidates={} assigned={} free={} minimum_free={} assignments={}",
                zone.id,
                loadedChunks,
                candidates.size,
                zoneAssignments.size,
                (candidates.size - zoneAssignments.size).coerceAtLeast(0),
                zone.minimumFreeSeats,
                zoneAssignments.entries.joinToString(",") { (npcId, seat) -> "$npcId@${point(seat)}" },
            )
        }
        OriginDiningAmbientLayout.applyAutoSeating(assignments, tables)
    }

    fun canOpen(player: Player, menu: BreweryTableDialogs.Menu): Boolean {
        val session = sessions[player.uniqueId]
        val allowed = session != null && session.mounted && session.seat.menu == menu && nearVenue(player, session.seat)
        if (!allowed) {
            player.sendActionBar(Component.text("Сначала сядьте за свободное место.", NamedTextColor.GRAY))
            logWarn("DIALOG_REJECTED", session, player, null, "no-active-seat")
            return false
        }
        if (session.seat.id in meals) {
            player.sendActionBar(Component.text("Сначала съешьте блюдо на столе.", NamedTextColor.GRAY))
            logWarn("DIALOG_REJECTED", session, player, meals[session.seat.id]?.dish, "meal-already-present")
            return false
        }
        val authorization = dialogAuthorizations[player.uniqueId]
        if (
            authorization?.first != session.id ||
            authorization.second < System.currentTimeMillis() ||
            waiterReadyFor[session.seat.waiterId] != session.id
        ) {
            dialogAuthorizations.remove(player.uniqueId)
            player.sendActionBar(Component.text("Дождитесь официанта и нажмите по нему.", NamedTextColor.GRAY))
            logWarn("DIALOG_REJECTED", session, player, null, "waiter-interaction-required")
            return false
        }
        session.touchedAt = System.currentTimeMillis()
        log("DIALOG_OPEN", session, player, null, session.seat.dish, null)
        return true
    }

    fun order(
        player: Player,
        menu: BreweryTableDialogs.Menu,
        dish: BreweryTableDialogs.Dish,
    ): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        if (!session.mounted || session.seat.menu != menu || !nearVenue(player, session.seat)) {
            logWarn("ORDER_REJECTED", session, player, dish, "seat-or-menu-mismatch")
            return true
        }
        if (session.phase == OriginDiningPhase.ORDERED) {
            player.sendActionBar(Component.text("Заказ уже несут к столу.", NamedTextColor.GRAY))
            logWarn("ORDER_REJECTED", session, player, dish, "delivery-already-pending")
            return true
        }
        if (session.seat.id in meals) {
            player.sendActionBar(Component.text("Сначала съешьте блюдо на столе.", NamedTextColor.GRAY))
            logWarn("ORDER_REJECTED", session, player, meals[session.seat.id]?.dish, "meal-already-present")
            return true
        }
        val economy = EconomyModule.getEconomy()
        if (economy == null) {
            player.sendActionBar(Component.text("Оплата временно недоступна.", NamedTextColor.RED))
            logWarn("ORDER_REJECTED", session, player, dish, "economy-unavailable")
            return true
        }
        if (!economy.has(player, dish.price.toDouble())) {
            player.sendActionBar(
                Component.text("Для заказа нужно ${dish.price} ", NamedTextColor.RED)
                    .append(Component.text("💰", NamedTextColor.WHITE))
                    .append(Component.text(".", NamedTextColor.RED)),
            )
            logWarn("ORDER_REJECTED", session, player, dish, "insufficient-funds")
            return true
        }
        if (session.seat.deliveryTarget == OriginDiningDeliveryTarget.INVENTORY) {
            if (player.inventory.firstEmpty() < 0) {
                player.sendActionBar(Component.text("Освободите один слот для бутылки.", NamedTextColor.RED))
                logWarn("ORDER_REJECTED", session, player, dish, "inventory-full")
                return true
            }
            if (breweryRecipe(dish.id) == null || Bukkit.getPluginCommand("brew") == null) {
                player.sendActionBar(Component.text("Подача напитков временно недоступна. Деньги не списаны.", NamedTextColor.RED))
                logWarn("ORDER_REJECTED", session, player, dish, "brewery-command-unavailable")
                return true
            }
        } else if (dishItem(dish) == null) {
            player.sendActionBar(Component.text("Блюдо пока недоступно. Деньги не списаны.", NamedTextColor.RED))
            logWarn("ORDER_REJECTED", session, player, dish, "itemsadder-item-unavailable")
            return true
        }
        session.phase = OriginDiningPhase.ORDERED
        session.touchedAt = System.currentTimeMillis()
        dialogAuthorizations.remove(player.uniqueId)
        clearWaiterReady(session.seat.waiterId, "order-accepted", session.id)
        log("ORDER_ACCEPTED", session, player, dish, session.seat.dish, null)
        queueDelivery(session, dish)
        player.sendActionBar(Component.text("Заказ принят. Официант заберёт его на кухне.", NamedTextColor.GOLD))
        return true
    }

    fun interact(player: Player, entity: Entity, source: String): Boolean {
        val managedInteraction = entity is Interaction && (entity.uniqueId in mealEntity || entity.uniqueId in guestMealEntity || entity.uniqueId in waiterHitboxEntity)
        if (managedInteraction) {
            info(
                "ORIGIN_DINING phase=INPUT_ENTITY player={} source={} entity={} entity_id={} actual={}",
                player.name,
                source,
                short(entity.uniqueId),
                entity.entityId,
                location(entity.location),
            )
        }
        mealEntity[entity.uniqueId]?.let { meal ->
            info(
                "ORIGIN_DINING phase=MEAL_INPUT player={} source={} table={} dish={} hitbox={} actual={}",
                player.name,
                source,
                meal.seat.id,
                meal.dish.id,
                short(entity.uniqueId),
                location(entity.location),
            )
            consume(player, meal)
            return true
        }
        guestMealEntity[entity.uniqueId]?.let { meal ->
            info(
                "ORIGIN_DINING phase=GUEST_MEAL_INPUT player={} source={} table={} dish={} hitbox={} actual={}",
                player.name,
                source,
                meal.table.id,
                meal.dish.id,
                short(entity.uniqueId),
                location(entity.location),
            )
            consumeGuestMeal(player, meal)
            return true
        }
        waiterHitboxEntity[entity.uniqueId]?.let { hitbox ->
            info(
                "ORIGIN_DINING phase=WAITER_HITBOX_INPUT player={} source={} npc={} session={} hitbox={} actual={}",
                player.name,
                source,
                hitbox.waiterId,
                short(hitbox.sessionId),
                short(entity.uniqueId),
                location(entity.location),
            )
            return interactWaiter(player, hitbox.waiterId, "$source:waiter-hitbox")
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            runCatching { CitizensAPI.getNPCRegistry().getNPC(entity) }.getOrNull()?.let { npc ->
                if (npc.id in OriginDiningLayout.waiterIds && interactWaiter(player, npc.id, "$source:citizens-entity")) return true
            }
        }
        dynamicFurnitureSeat(entity)?.let { seat ->
            requestSeatConfirmation(player, seat, "$source:itemsadder-furniture")
            // ItemsAdder owns the actual mount, so its interaction must continue.
            return false
        }
        if (managedInteraction) {
            warn(
                "ORIGIN_DINING phase=INPUT_UNMAPPED player={} source={} entity={} entity_id={} actual={}",
                player.name,
                source,
                short(entity.uniqueId),
                entity.entityId,
                location(entity.location),
            )
        }
        return false
    }

    fun interactBlock(player: Player, block: Block): Boolean {
        if (block.world.name != OriginDiningLayout.WORLD) return false
        val seat = dynamicSeat(block) ?: return false
        cleanupOrphanCmiChair(block)
        requestSeatConfirmation(player, seat, "block:${block.x},${block.y},${block.z}:world-stair")
        return true
    }

    private fun requestSeatConfirmation(player: Player, seat: OriginDiningSeat, source: String) {
        if (debounce(player)) {
            info("ORIGIN_DINING phase=INPUT_DEBOUNCED player={} table={} source={}", player.name, seat.id, source)
            return
        }
        val token = UUID.randomUUID()
        pendingSeatAttempts[player.uniqueId] = token
        info(
            "ORIGIN_DINING phase=SEAT_CONFIRM_PENDING player={} table={} attempt={} source={} actual_player={}",
            player.name,
            seat.id,
            short(token),
            source,
            location(player.location),
        )
        confirmSeat(player.uniqueId, seat, source, token, 0)
    }

    private fun confirmSeat(playerId: UUID, seat: OriginDiningSeat, source: String, token: UUID, poll: Int) {
        tasks.runLater(OriginDiningLayout.seatConfirmDelayTicks) {
            if (pendingSeatAttempts[playerId] != token) return@runLater
            val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)
            if (player == null) {
                pendingSeatAttempts.remove(playerId, token)
                return@runLater
            }
            val vehicle = restaurantChairVehicle(player, seat)
            if (vehicle != null) {
                pendingSeatAttempts.remove(playerId, token)
                sit(player, seat, source, vehicle.uniqueId)
                return@runLater
            }
            if (poll + 1 < OriginDiningLayout.seatConfirmMaxPolls) {
                confirmSeat(playerId, seat, source, token, poll + 1)
                return@runLater
            }
            pendingSeatAttempts.remove(playerId, token)
            warn(
                "ORIGIN_DINING phase=SEAT_CONFIRM_FAILED player={} table={} attempt={} source={} polls={} vehicle={} actual_player={}",
                player.name,
                seat.id,
                short(token),
                source,
                poll + 1,
                player.vehicle?.let { "${it.type}#${it.entityId}" } ?: "none",
                location(player.location),
            )
        }
    }

    private fun restaurantChairVehicle(player: Player, seat: OriginDiningSeat): Entity? {
        val vehicle = player.vehicle ?: return null
        if (vehicle !is ArmorStand) return null
        if (vehicle.world.name != OriginDiningLayout.WORLD) return null
        val target = seat.seat.inWorld(vehicle.world)
        val dx = vehicle.location.x - target.x
        val dz = vehicle.location.z - target.z
        return vehicle.takeIf {
            dx * dx + dz * dz <= OriginDiningLayout.seatConfirmHorizontalRadius * OriginDiningLayout.seatConfirmHorizontalRadius
        }
    }

    private fun dynamicSeat(block: Block): OriginDiningSeat? {
        val stairs = block.blockData as? Stairs ?: return null
        if (stairs.half != Bisected.Half.BOTTOM) return null
        val venueMenu = OriginDiningLayout.dynamicMenu(block.location) ?: return null
        if (occupiedByNpc(block)) {
            info("ORIGIN_DINING phase=DYNAMIC_SEAT_IGNORED block={},{},{} reason=npc-occupied", block.x, block.y, block.z)
            return null
        }
        val yaw = OriginDiningLayout.stairYaw(stairs.facing) ?: return null
        val seat = OriginDiningPoint(block.x + 0.5, block.y.toDouble(), block.z + 0.5, yaw)
        val front = block.getRelative(stairs.facing.oppositeFace)
        val hasTable = !front.type.isAir || hasFurnitureAt(front)
        val menu = if (!hasTable) BreweryTableDialogs.Menu.DRINKS else venueMenu
        val target = if (hasTable) OriginDiningDeliveryTarget.TABLE else OriginDiningDeliveryTarget.INVENTORY
        val baseDish =
            if (hasTable) {
                OriginDiningLayout.centeredDishPoint(seat, 1.0, OriginDiningLayout.dynamicTableHeight)
            } else {
                seat.copy(y = seat.y + 1.0)
            }
        val dish =
            baseDish.copy(
                x = baseDish.x + OriginDiningLayout.dynamicDishOffsetX,
                y = baseDish.y + OriginDiningLayout.dynamicDishOffsetY,
                z = baseDish.z + OriginDiningLayout.dynamicDishOffsetZ,
            )
        val waiterStop = dynamicWaiterStop(block, seat)
        val waiterId = chooseWaiter(venueMenu, seat)
        val venueId = if (venueMenu == BreweryTableDialogs.Menu.RESTAURANT) "restaurant" else "brewery"
        return OriginDiningSeat(
            id = "${venueId}_dynamic_${block.x}_${block.y}_${block.z}",
            menu = menu,
            seat = seat,
            dish = dish,
            waiterStop = waiterStop,
            waiterId = waiterId,
            clickedBlock = Triple(block.x, block.y, block.z),
            deliveryTarget = target,
            dynamic = true,
        ).also {
            info(
                "ORIGIN_DINING phase=DYNAMIC_SEAT_RESOLVED table={} block={},{},{} facing={} menu={} delivery={} dish_target={} waiter_stop={} npc={}",
                it.id,
                block.x,
                block.y,
                block.z,
                stairs.facing,
                menu.id,
                target,
                point(dish),
                point(waiterStop),
                waiterId,
            )
        }
    }

    private fun dynamicFurnitureSeat(entity: Entity): OriginDiningSeat? {
        val handle = ItemsAdderFurnitureRuntime.inspect(entity) ?: return null
        val profile = OriginDiningLayout.furnitureSeatProfile(handle.namespacedId) ?: return null
        val root = handle.root
        val venueMenu = OriginDiningLayout.dynamicMenu(root.location) ?: return null
        val seat = furnitureSeatPoint(root, profile) ?: return null
        if (occupiedByNpcAt(seat.inWorld(root.world))) {
            info("ORIGIN_DINING phase=DYNAMIC_SEAT_IGNORED furniture={} reason=npc-occupied", handle.namespacedId)
            return null
        }
        val tableFace = furnitureTableFace(root, profile)
        val hasTable = tableFace != null
        val menu = if (hasTable) venueMenu else BreweryTableDialogs.Menu.DRINKS
        val target = if (hasTable) OriginDiningDeliveryTarget.TABLE else OriginDiningDeliveryTarget.INVENTORY
        val baseDish =
            if (hasTable) OriginDiningLayout.centeredDishPoint(seat, 1.0, OriginDiningLayout.dynamicTableHeight)
            else seat.copy(y = seat.y + 1.0)
        val dish = baseDish.copy(
            x = baseDish.x + OriginDiningLayout.dynamicDishOffsetX,
            y = baseDish.y + OriginDiningLayout.dynamicDishOffsetY,
            z = baseDish.z + OriginDiningLayout.dynamicDishOffsetZ,
        )
        val anchorBlock = root.world.getBlockAt(floor(seat.x).toInt(), floor(seat.y).toInt(), floor(seat.z).toInt())
        val waiterStop = dynamicWaiterStop(anchorBlock, seat)
        val waiterId = chooseWaiter(venueMenu, seat)
        val venueId = if (venueMenu == BreweryTableDialogs.Menu.RESTAURANT) "restaurant" else "brewery"
        return OriginDiningSeat(
            id = "${venueId}_furniture_${root.uniqueId}",
            menu = menu,
            seat = seat,
            dish = dish,
            waiterStop = waiterStop,
            waiterId = waiterId,
            clickedBlock = Triple(anchorBlock.x, anchorBlock.y, anchorBlock.z),
            deliveryTarget = target,
            dynamic = true,
        ).also {
            info(
                "ORIGIN_DINING phase=DYNAMIC_FURNITURE_SEAT_RESOLVED table={} furniture={} yaw={} delivery={} dish_target={} npc={}",
                it.id,
                handle.namespacedId,
                fmt(seat.yaw.toDouble()),
                target,
                point(dish),
                waiterId,
            )
        }
    }

    private fun furnitureSeatPoint(root: Entity, profile: OriginDiningFurnitureSeatProfile): OriginDiningPoint? {
        val tableFace = furnitureTableFace(root, profile)
        if (profile.requireTable && tableFace == null) return null
        val radians = Math.toRadians(root.location.yaw.toDouble())
        val offsetX = profile.offsetX * cos(radians) - profile.offsetZ * sin(radians)
        val offsetZ = profile.offsetX * sin(radians) + profile.offsetZ * cos(radians)
        val yaw = OriginDiningLayout.yawToward(tableFace ?: BlockFace.SELF)
            ?: normalizeYaw(root.location.yaw + profile.yawOffset)
        return OriginDiningPoint(
            root.location.x + offsetX,
            root.location.y + profile.offsetY,
            root.location.z + offsetZ,
            yaw,
        )
    }

    private fun furnitureTableFace(root: Entity, profile: OriginDiningFurnitureSeatProfile): BlockFace? {
        val anchor = root.location.block
        val preferredYaw = normalizeYaw(root.location.yaw + profile.yawOffset)
        return listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
            .filter { face -> hasTableAt(anchor.getRelative(face), root.uniqueId) }
            .minByOrNull { face -> yawDistance(OriginDiningLayout.yawToward(face) ?: 0f, preferredYaw) }
    }

    private fun hasTableAt(block: Block, ignoredRoot: UUID): Boolean {
        if (!block.type.isAir) return true
        val center = block.location.add(0.5, 0.8, 0.5)
        return block.world.getNearbyEntities(center, 0.7, 1.2, 0.7).any { entity ->
            val handle = ItemsAdderFurnitureRuntime.inspect(entity)
            handle != null && handle.root.uniqueId != ignoredRoot && OriginDiningLayout.furnitureSeatProfile(handle.namespacedId) == null
        }
    }

    private fun normalizeYaw(yaw: Float): Float = ((yaw % 360f) + 540f) % 360f - 180f

    private fun yawDistance(first: Float, second: Float): Float = kotlin.math.abs(normalizeYaw(first - second))

    private fun occupiedByNpc(block: Block): Boolean {
        return occupiedByNpcAt(block.location.add(0.5, 0.7, 0.5))
    }

    private fun occupiedByNpcAt(center: Location): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return false
        return center.world.getNearbyEntities(center, 0.72, 1.4, 0.72).any { entity ->
            val npc = runCatching { CitizensAPI.getNPCRegistry().getNPC(entity) }.getOrNull()
            npc != null && entity.vehicle != null
        }
    }

    private fun hasFurnitureAt(block: Block): Boolean {
        val center = block.location.add(0.5, 0.8, 0.5)
        return block.world.getNearbyEntities(center, 0.7, 1.2, 0.7).any { entity ->
            entity is ItemDisplay && !entity.scoreboardTags.contains(MEAL_TAG)
        }
    }

    private fun dynamicWaiterStop(block: Block, seat: OriginDiningPoint): OriginDiningPoint {
        val radians = Math.toRadians(seat.yaw.toDouble())
        val sideX = -cos(radians) * OriginDiningLayout.dynamicWaiterSideOffset
        val sideZ = -sin(radians) * OriginDiningLayout.dynamicWaiterSideOffset
        val forwardX = -sin(radians)
        val forwardZ = cos(radians)
        val candidates =
            listOf(
                OriginDiningPoint(seat.x + sideX, seat.y, seat.z + sideZ),
                OriginDiningPoint(seat.x - sideX, seat.y, seat.z - sideZ),
                OriginDiningPoint(seat.x - forwardX, seat.y, seat.z - forwardZ),
            )
        return candidates.firstOrNull { walkable(block.world, it) } ?: seat
    }

    private fun walkable(world: org.bukkit.World, point: OriginDiningPoint): Boolean {
        val feet = world.getBlockAt(point.inWorld(world))
        return feet.isPassable &&
            feet.getRelative(BlockFace.UP).isPassable &&
            MinecraftBlockExaminer.canStandOn(feet.getRelative(BlockFace.DOWN))
    }

    private fun chooseWaiter(menu: BreweryTableDialogs.Menu, seat: OriginDiningPoint): Int {
        val candidates = if (menu == BreweryTableDialogs.Menu.RESTAURANT) listOf(431, 432) else listOf(410, 411)
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD)
        return candidates.minWithOrNull(
            compareBy<Int> { if (it in waiterAssignments || it in activeDeliveries || it in waiterApproaches || it in ambientRoutes) 1 else 0 }
                .thenBy { id -> waiter(id)?.takeIf { it.isSpawned && world != null }?.entity?.location?.distanceSquared(seat.inWorld(world!!)) ?: Double.MAX_VALUE },
        ) ?: candidates.first()
    }

    fun interactWaiter(player: Player, npcId: Int, source: String = "citizens-event"): Boolean {
        if (npcId !in OriginDiningLayout.waiterIds) return false
        val session = sessions[player.uniqueId]
        info(
            "ORIGIN_DINING phase=INPUT_WAITER player={} source={} npc={} session={} table={} actual_player={}",
            player.name,
            source,
            npcId,
            session?.id?.let(::short) ?: "none",
            session?.seat?.id ?: "none",
            location(player.location),
        )
        if (session == null) {
            warn("ORIGIN_DINING phase=WAITER_INPUT_REJECTED player={} source={} npc={} reason=no-active-seat", player.name, source, npcId)
            return false
        }
        if (!session.mounted) {
            player.sendActionBar(Component.text("Сначала снова сядьте за стол.", NamedTextColor.GRAY))
            logWarn("WAITER_INPUT_REJECTED", session, player, null, "npc=$npcId player-not-seated")
            return true
        }
        if (!OriginDiningLayout.waiterServes(session.seat, npcId) || !nearVenue(player, session.seat)) {
            warn(
                "ORIGIN_DINING phase=WAITER_INPUT_REJECTED player={} source={} npc={} table={} expected_npc={} reason=waiter-or-venue-mismatch",
                player.name,
                source,
                npcId,
                session.seat.id,
                OriginDiningLayout.waiterIds.sorted().joinToString(","),
            )
            return false
        }
        if (waiterReadyFor[npcId] != session.id) {
            player.sendActionBar(Component.text("Подождите, пока официант подойдёт к вашему столу.", NamedTextColor.GRAY))
            logWarn("WAITER_INPUT_REJECTED", session, player, null, "npc=$npcId waiter-not-ready")
            return true
        }
        if (debounce(player)) {
            info("ORIGIN_DINING phase=INPUT_DEBOUNCED player={} table={} source={}", player.name, session.seat.id, source)
            return true
        }
        session.touchedAt = System.currentTimeMillis()
        dialogAuthorizations[player.uniqueId] = session.id to (System.currentTimeMillis() + OriginDiningLayout.dialogAuthorizationMillis)
        openDialog(player, session, "waiter:$npcId")
        return true
    }

    fun interactMountedWaiter(player: Player, entityId: Int) {
        tasks.runLater(0L) {
            if (!player.isOnline) return@runLater
            val waiterId = OriginDiningLayout.waiterIds.firstOrNull { waiter(it)?.takeIf { npc -> npc.isSpawned }?.entity?.entityId == entityId } ?: return@runLater
            interactWaiter(player, waiterId, "packet-events:mounted")
        }
    }

    fun quit(player: Player) {
        releaseSession(player.uniqueId, "player-quit")
    }

    private fun cleanupWorldSeats() {
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD)
        if (world == null) {
            warn("ORIGIN_DINING phase=START_FAILED world={} reason=world-unavailable", OriginDiningLayout.WORLD)
            return
        }
        cleanupLegacy(world, "world-stairs-only")
        val centers =
            listOf(
                Location(world, BreweryTableDialogs.BREWERY_X, BreweryTableDialogs.BREWERY_Y, BreweryTableDialogs.BREWERY_Z),
                Location(world, BreweryTableDialogs.RESTAURANT_X, BreweryTableDialogs.RESTAURANT_Y, BreweryTableDialogs.RESTAURANT_Z),
            )
        var removed = 0
        centers.forEach { center ->
            world.getNearbyEntities(center, 24.0, 8.0, 24.0)
                .filterIsInstance<Interaction>()
                .filter { it.scoreboardTags.contains(SEAT_TAG) }
                .forEach {
                    it.remove()
                    removed++
                }
        }
        info("ORIGIN_DINING phase=LEGACY_SEAT_HITBOX_CLEANUP removed={}", removed)
    }

    private fun cleanupOrphanCmiChair(block: Block): Int {
        val center = block.location.add(0.5, -1.4, 0.5)
        val chairs = block.world.getNearbyEntities(center, 0.65, 0.35, 0.65)
            .filterIsInstance<ArmorStand>()
            .filter { stand ->
                stand.passengers.isEmpty() &&
                    PlainTextComponentSerializer.plainText().serialize(stand.customName() ?: Component.empty()) == CMI_CHAIR_NAME
            }
        chairs.forEach(Entity::remove)
        if (chairs.isNotEmpty()) {
            info(
                "ORIGIN_DINING phase=ORPHAN_CHAIR_CLEANUP removed={} reason={} center={} radius={}",
                chairs.size,
                "seat-attempt",
                location(center),
                "0.650",
            )
        }
        return chairs.size
    }

    private fun sit(player: Player, seat: OriginDiningSeat, source: String, chairVehicleId: UUID) {
        if (player.world.name != OriginDiningLayout.WORLD) return
        val existing = sessions[player.uniqueId]
        if (existing?.seat?.id == seat.id) {
            existing.chairVehicleId = chairVehicleId
            existing.mounted = true
            existing.touchedAt = System.currentTimeMillis()
            log("RESEATED", existing, player, null, seat.dish, "source=$source chair=${short(chairVehicleId)} seat_owner=server-chair-handler")
            if (existing.phase == OriginDiningPhase.SEATED && seat.id !in meals) summonWaiter(existing, player)
            return
        }
        occupants[seat.id]?.takeIf { it != player.uniqueId }?.let { occupant ->
            val other = Bukkit.getPlayer(occupant)
            if (other?.isOnline == true && nearVenue(other, seat)) {
                player.sendActionBar(Component.text("Это место уже занято.", NamedTextColor.GRAY))
                warn("ORIGIN_DINING phase=SEAT_REJECTED player={} table={} reason=occupied occupant={}", player.name, seat.id, other.name)
                return
            }
            releaseSession(occupant, "stale-occupant")
        }
        existing?.let { releaseSession(player.uniqueId, "seat-switched") }

        val session = OriginDiningSession(UUID.randomUUID(), player.uniqueId, seat, chairVehicleId, true, OriginDiningPhase.SEATED, System.currentTimeMillis())
        sessions[player.uniqueId] = session
        occupants[seat.id] = player.uniqueId
        log("SEATED", session, player, null, seat.dish, "source=$source chair=${short(chairVehicleId)} seat_owner=server-chair-handler")
        player.sendActionBar(Component.text("Официант сейчас подойдёт. Нажмите по нему, чтобы открыть меню.", NamedTextColor.GOLD))
        summonWaiter(session, player)
    }

    fun dismounted(player: Player, dismounted: Entity) {
        val session = sessions[player.uniqueId] ?: return
        if (session.chairVehicleId != dismounted.uniqueId) return
        tasks.runLater(1L) {
            val current = sessions[player.uniqueId]?.takeIf { it.id == session.id } ?: return@runLater
            val replacement = restaurantChairVehicle(player, current.seat)
            if (replacement != null) {
                current.chairVehicleId = replacement.uniqueId
                current.mounted = true
                log("SEAT_VEHICLE_REPLACED", current, player, meals[current.seat.id]?.dish, current.seat.seat, "chair=${short(replacement.uniqueId)}")
                return@runLater
            }
            current.mounted = false
            pendingSeatAttempts.remove(player.uniqueId)
            dialogAuthorizations.remove(player.uniqueId)
            if (current.phase != OriginDiningPhase.ORDERED) returnWaiterHome(current, "player-dismounted")
            log(
                "DISMOUNTED",
                current,
                player,
                meals[current.seat.id]?.dish,
                current.seat.seat,
                "chair=${short(dismounted.uniqueId)} order_continues=${current.phase == OriginDiningPhase.ORDERED}",
            )
        }
    }

    private fun openDialog(player: Player, session: OriginDiningSession, source: String) {
        log("DIALOG_REQUESTED", session, player, null, session.seat.seat, "source=$source menu=${session.seat.menu.id}")
        runCatching { BreweryTableDialogs.openOrder(player, session.seat.menu) }
            .onSuccess { log("DIALOG_DISPATCHED", session, player, null, session.seat.seat, "source=$source menu=${session.seat.menu.id}") }
            .onFailure { failure -> logWarn("DIALOG_FAILED", session, player, null, failure.message ?: failure.javaClass.simpleName) }
    }

    private fun queueDelivery(session: OriginDiningSession, dish: BreweryTableDialogs.Dish) {
        val queue = deliveryQueues.getOrPut(session.seat.waiterId) { ArrayDeque() }
        queue.addLast(OriginDiningDelivery(session.id, dish))
        log(
            "DELIVERY_QUEUED",
            session,
            Bukkit.getPlayer(session.playerId),
            dish,
            session.seat.dish,
            "npc=${session.seat.waiterId} queue=${queue.size}",
        )
        startNextDelivery(session.seat.waiterId)
    }

    private fun startNextDelivery(waiterId: Int) {
        if (waiterId in activeDeliveries) return
        cancelAmbientRoute(waiterId, "player-delivery")
        val queue = deliveryQueues[waiterId] ?: return
        var delivery: OriginDiningDelivery? = null
        var session: OriginDiningSession? = null
        while (queue.isNotEmpty() && session == null) {
            val candidate = queue.removeFirst()
            val current = sessions.values.firstOrNull { it.id == candidate.sessionId && it.phase == OriginDiningPhase.ORDERED }
            if (current != null) {
                delivery = candidate
                session = current
            }
        }
        if (queue.isEmpty()) deliveryQueues.remove(waiterId)
        val currentDelivery = delivery ?: return
        val currentSession = session ?: return
        val token = UUID.randomUUID()
        activeDeliveries[waiterId] = token
        waiterAssignments[waiterId] = currentSession.id
        waiterApproaches.remove(waiterId)
        val player = Bukkit.getPlayer(currentSession.playerId)
        val npc = waiter(waiterId)
        if (npc == null || !npc.isSpawned || npc.entity.world.name != OriginDiningLayout.WORLD) {
            logWarn("DELIVERY_ROUTE_FALLBACK", currentSession, player, currentDelivery.dish, "npc=$waiterId unavailable")
            tasks.runLater(OriginDiningLayout.deliveryFallbackTicks) {
                if (activeDeliveries[waiterId] != token) return@runLater
                deliver(currentSession.id, currentDelivery.dish)
                finishDelivery(waiterId, token, currentSession.id, "npc-unavailable")
            }
            return
        }
        npc.entity.addScoreboardTag(WAITER_BUSY_TAG)
        player?.takeIf(Player::isOnline)?.let { showWaiterGlow(waiterId, currentSession, npc, it) }
        val pickup = OriginDiningLayout.waiterHome(waiterId)?.inWorld(npc.entity.world)
        if (pickup == null) {
            beginTableRoute(currentSession, currentDelivery.dish, waiterId, token, npc)
            return
        }
        navigateDelivery(
            currentSession,
            currentDelivery.dish,
            waiterId,
            token,
            npc,
            pickup,
            "PICKUP",
        ) {
            if (activeDeliveries[waiterId] != token) return@navigateDelivery
            setWaiterCarry(waiterId, currentDelivery.dish)
            player?.takeIf(Player::isOnline)?.playSound(pickup, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.45f, 1.2f)
            log("ORDER_PICKED_UP", currentSession, player, currentDelivery.dish, OriginDiningLayout.waiterHome(waiterId) ?: currentSession.seat.dish, "npc=$waiterId")
            beginTableRoute(currentSession, currentDelivery.dish, waiterId, token, npc)
        }
    }

    private fun beginTableRoute(
        session: OriginDiningSession,
        dish: BreweryTableDialogs.Dish,
        waiterId: Int,
        token: UUID,
        npc: net.citizensnpcs.api.npc.NPC,
    ) {
        if (activeDeliveries[waiterId] != token) return
        if (waiterId !in waiterHeldItems) setWaiterCarry(waiterId, dish)
        val stop = session.seat.waiterStop.inWorld(npc.entity.world)
        navigateDelivery(session, dish, waiterId, token, npc, stop, "TABLE") {
            if (activeDeliveries[waiterId] != token) return@navigateDelivery
            Bukkit.getPlayer(session.playerId)?.takeIf(Player::isOnline)?.let { npc.faceLocation(it.eyeLocation) }
            tasks.runLater(OriginDiningLayout.servicePauseTicks) {
                if (activeDeliveries[waiterId] != token) return@runLater
                deliver(session.id, dish)
                finishDelivery(waiterId, token, session.id, "served")
            }
        }
    }

    private fun navigateDelivery(
        session: OriginDiningSession,
        dish: BreweryTableDialogs.Dish,
        waiterId: Int,
        token: UUID,
        npc: net.citizensnpcs.api.npc.NPC,
        destination: Location,
        stage: String,
        ready: () -> Unit,
    ) {
        navigateLevel(npc, destination)
        log(
            "DELIVERY_ROUTE_$stage",
            session,
            Bukkit.getPlayer(session.playerId),
            dish,
            session.seat.dish,
            "npc=$waiterId token=${short(token)} actual=${location(npc.entity.location)} destination=${location(destination)}",
        )
        monitorDeliveryRoute(session.id, dish, waiterId, token, destination, stage, 0, ready)
    }

    private fun monitorDeliveryRoute(
        sessionId: UUID,
        dish: BreweryTableDialogs.Dish,
        waiterId: Int,
        token: UUID,
        destination: Location,
        stage: String,
        poll: Int,
        ready: () -> Unit,
    ) {
        tasks.runLater(OriginDiningLayout.waiterPollTicks) {
            if (activeDeliveries[waiterId] != token) return@runLater
            val session = sessions.values.firstOrNull { it.id == sessionId && it.phase == OriginDiningPhase.ORDERED }
            if (session == null) {
                finishDelivery(waiterId, token, sessionId, "session-gone")
                return@runLater
            }
            val npc = waiter(waiterId)
            if (npc == null || !npc.isSpawned || npc.entity.world != destination.world) {
                logWarn("DELIVERY_ROUTE_FALLBACK", session, Bukkit.getPlayer(session.playerId), dish, "npc=$waiterId stage=$stage despawned")
                ready()
                return@runLater
            }
            val distance = npc.entity.location.distance(destination)
            if (distance <= OriginDiningLayout.waiterReadyMargin || poll >= OriginDiningLayout.deliveryRouteMaxPolls) {
                stopWaiterNavigation(npc)
                log(
                    if (poll >= OriginDiningLayout.deliveryRouteMaxPolls) "DELIVERY_ROUTE_TIMEOUT" else "DELIVERY_ROUTE_READY",
                    session,
                    Bukkit.getPlayer(session.playerId),
                    dish,
                    session.seat.dish,
                    "npc=$waiterId stage=$stage token=${short(token)} actual=${location(npc.entity.location)} destination=${location(destination)} distance=${fmt(distance)} poll=$poll",
                )
                ready()
                return@runLater
            }
            if (!isWaiterNavigating(npc)) {
                navigateLevel(npc, destination)
            }
            if (poll % OriginDiningLayout.waiterProgressEveryPolls == 0) {
                log(
                    "DELIVERY_ROUTE_PROGRESS",
                    session,
                    Bukkit.getPlayer(session.playerId),
                    dish,
                    session.seat.dish,
                    "npc=$waiterId stage=$stage token=${short(token)} actual=${location(npc.entity.location)} destination=${location(destination)} distance=${fmt(distance)} poll=$poll",
                )
            }
            monitorDeliveryRoute(sessionId, dish, waiterId, token, destination, stage, poll + 1, ready)
        }
    }

    private fun finishDelivery(waiterId: Int, token: UUID, sessionId: UUID, reason: String) {
        if (!activeDeliveries.remove(waiterId, token)) return
        clearWaiterCarry(waiterId)
        val session = sessions.values.firstOrNull { it.id == sessionId }
        val nextQueued = deliveryQueues[waiterId]?.isNotEmpty() == true
        if (nextQueued) {
            tasks.runLater(OriginDiningLayout.nextDeliveryTicks) { startNextDelivery(waiterId) }
        } else if (session != null) {
            returnWaiterHome(session, "delivery-$reason")
        } else {
            returnWaiterHome(waiterId, sessionId, null, "delivery-$reason")
        }
    }

    private fun waiter(waiterId: Int): net.citizensnpcs.api.npc.NPC? =
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) null
        else runCatching { CitizensAPI.getNPCRegistry().getById(waiterId) }.getOrNull()

    private fun navigateLevel(
        npc: net.citizensnpcs.api.npc.NPC,
        destination: Location,
    ): Boolean {
        val profile = OriginDiningLayout.routeProfile(destination)
        if (profile == null) {
            info(
                "ORIGIN_DINING phase=WAITER_GRID_PATH_UNAVAILABLE npc={} actual={} target={} reason=no-route-profile",
                npc.id,
                location(npc.entity.location),
                location(destination),
            )
            return false
        }
        return routeController.navigate(npc, destination, profile)
    }

    private fun logRouteEvent(event: NpcRouteEvent) {
        info(
            "ORIGIN_DINING phase=WAITER_GRID_{} profile={} npc={} cells={} actual={} target={} reason={}",
            event.phase,
            event.profileId,
            event.npcId,
            event.cells,
            location(event.actual),
            location(event.target),
            event.reason ?: "none",
        )
    }

    private fun isWaiterNavigating(npc: net.citizensnpcs.api.npc.NPC): Boolean =
        routeController.isNavigating(npc)

    private fun stopWaiterNavigation(npc: net.citizensnpcs.api.npc.NPC) {
        routeController.stop(npc)
    }

    private fun resetIdleWaiters(reason: String) {
        OriginDiningLayout.waiterIds.forEach { waiterId ->
            if (waiterId in activeDeliveries || waiterId in waiterAssignments || waiterId in ambientRoutes) return@forEach
            val npc = waiter(waiterId)?.takeIf { it.isSpawned && it.entity.world.name == OriginDiningLayout.WORLD } ?: return@forEach
            val home = OriginDiningLayout.waiterHome(waiterId)?.inWorld(npc.entity.world) ?: return@forEach
            clearWaiterReady(waiterId, "reset-$reason")
            clearWaiterGlow(waiterId, "reset-$reason")
            npc.entity.addScoreboardTag(WAITER_BUSY_TAG)
            navigateLevel(npc, home)
            info(
                "ORIGIN_DINING phase=WAITER_RESET_HOME npc={} reason={} actual={} target={}",
                waiterId,
                reason,
                location(npc.entity.location),
                location(home),
            )
            tasks.runLater(OriginDiningLayout.waiterReturnReleaseTicks) {
                if (waiterId in activeDeliveries || waiterId in waiterAssignments || waiterId in ambientRoutes) return@runLater
                waiter(waiterId)?.takeIf { it.isSpawned }?.entity?.removeScoreboardTag(WAITER_BUSY_TAG)
            }
        }
    }

    private fun setWaiterCarry(waiterId: Int, dish: BreweryTableDialogs.Dish) {
        val npc = waiter(waiterId)?.takeIf { it.isSpawned } ?: return
        val equipment = npc.getOrAddTrait(CitizensEquipment::class.java)
        if (waiterId !in waiterHeldItems) {
            waiterHeldItems[waiterId] =
                equipment.get(CitizensEquipment.EquipmentSlot.HAND)?.takeUnless { it.type.isAir }?.clone()
        }
        val stack = dishItem(dish) ?: return
        equipment.set(CitizensEquipment.EquipmentSlot.HAND, stack)
        info(
            "ORIGIN_DINING phase=WAITER_CARRY_SET npc={} dish={} item_id={} equipment_source=citizens-trait render_context=thirdperson_righthand actual={}",
            waiterId,
            dish.id,
            OriginDiningLayout.itemId(dish.id),
            location(npc.entity.location),
        )
    }

    private fun clearWaiterCarry(waiterId: Int) {
        if (waiterId !in waiterHeldItems) return
        val previous = waiterHeldItems.remove(waiterId)
        val npc = waiter(waiterId)?.takeIf { it.isSpawned } ?: return
        val equipment = npc.getOrAddTrait(CitizensEquipment::class.java)
        equipment.set(CitizensEquipment.EquipmentSlot.HAND, previous ?: ItemStack(Material.AIR))
        info("ORIGIN_DINING phase=WAITER_CARRY_CLEARED npc={} actual={}", waiterId, location(npc.entity.location))
    }

    private fun returnWaiterHome(session: OriginDiningSession, reason: String) {
        returnWaiterHome(session.seat.waiterId, session.id, session, reason)
    }

    private fun returnWaiterHome(
        waiterId: Int,
        assignmentId: UUID,
        session: OriginDiningSession?,
        reason: String,
    ) {
        if (waiterId in activeDeliveries) return
        clearWaiterReady(waiterId, "return-$reason", assignmentId)
        clearWaiterGlow(waiterId, "return-$reason", assignmentId)
        if (!waiterAssignments.remove(waiterId, assignmentId)) return
        waiterApproaches.remove(waiterId)
        clearWaiterCarry(waiterId)
        val npc = waiter(waiterId)?.takeIf { it.isSpawned } ?: return
        val home = OriginDiningLayout.waiterHome(waiterId)?.inWorld(npc.entity.world)
        if (home == null) {
            npc.entity.removeScoreboardTag(WAITER_BUSY_TAG)
            return
        }
        npc.entity.addScoreboardTag(WAITER_BUSY_TAG)
        navigateLevel(npc, home)
        if (session != null) {
            log(
                "WAITER_RETURN",
                session,
                Bukkit.getPlayer(session.playerId),
                null,
                OriginDiningLayout.waiterHome(waiterId) ?: session.seat.waiterStop,
                "npc=$waiterId reason=$reason actual=${location(npc.entity.location)}",
            )
        } else {
            info(
                "ORIGIN_DINING phase=WAITER_RETURN session={} npc={} reason={} actual={} target={}",
                short(assignmentId),
                waiterId,
                reason,
                location(npc.entity.location),
                location(home),
            )
        }
        tasks.runLater(OriginDiningLayout.waiterReturnReleaseTicks) {
            if (waiterId in activeDeliveries || waiterId in waiterAssignments || waiterId in ambientRoutes) return@runLater
            waiter(waiterId)?.takeIf { it.isSpawned }?.entity?.removeScoreboardTag(WAITER_BUSY_TAG)
            info("ORIGIN_DINING phase=WAITER_RELEASED npc={} reason={} target={}", waiterId, reason, location(home))
        }
    }

    private fun deliver(sessionId: UUID, dish: BreweryTableDialogs.Dish) {
        val session = sessions.values.firstOrNull { it.id == sessionId }
        if (session == null || session.phase != OriginDiningPhase.ORDERED) {
            warn("ORIGIN_DINING phase=DELIVERY_SKIPPED session={} dish={} reason=session-gone", short(sessionId), dish.id)
            return
        }
        val player = Bukkit.getPlayer(session.playerId)
        if (player == null || !player.isOnline) {
            session.phase = OriginDiningPhase.SEATED
            logWarn("DELIVERY_SKIPPED", session, player, dish, "player-offline-no-charge")
            return
        }
        if (session.seat.deliveryTarget == OriginDiningDeliveryTarget.INVENTORY) {
            deliverBreweryDrink(session, player, dish)
            return
        }
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD)
        if (world == null) {
            session.phase = OriginDiningPhase.SEATED
            logWarn("DELIVERY_FAILED", session, player, dish, "world-unavailable-no-charge")
            return
        }
        val stack = dishItem(dish)
        if (stack == null) {
            session.phase = OriginDiningPhase.SEATED
            logWarn("DELIVERY_FAILED", session, player, dish, "itemsadder-item-unavailable-no-charge")
            player.sendActionBar(Component.text("Блюдо не загрузилось. Деньги не списаны.", NamedTextColor.RED))
            return
        }
        log("DELIVERY_COMMIT_BEGIN", session, player, dish, session.seat.dish, null)
        val created = runCatching { spawnMeal(session, dish, stack) }.getOrElse { failure ->
            session.phase = OriginDiningPhase.SEATED
            warn(
                "ORIGIN_DINING phase=DELIVERY_FAILED session={} player={} table={} dish={} target={} reason=spawn-failed error={}",
                short(session.id), player.name, session.seat.id, dish.id, point(session.seat.dish), failure.message ?: failure.javaClass.simpleName,
            )
            player.sendActionBar(Component.text("Подача сорвалась. Деньги не списаны.", NamedTextColor.RED))
            return
        }
        log(
            "MEAL_SPAWNED_UNCOMMITTED",
            session,
            player,
            dish,
            session.seat.dish,
            "item_id=${OriginDiningLayout.itemId(dish.id)} scale=${OriginDiningLayout.displayScale(dish.id)} display=${short(created.display.uniqueId)}#${created.display.entityId}@${location(created.display.location)} hitbox=${short(created.hitbox.uniqueId)}#${created.hitbox.entityId}@${location(created.hitbox.location)}",
        )
        val economy = EconomyModule.getEconomy()
        val payment = economy?.withdrawPlayer(player, dish.price.toDouble())
        if (payment?.transactionSuccess() != true) {
            removeMeal(created, "payment-rejected")
            session.phase = OriginDiningPhase.SEATED
            logWarn("DELIVERY_FAILED", session, player, dish, "payment-rejected-no-charge")
            player.sendActionBar(Component.text("Оплата не прошла. Заказ не списан.", NamedTextColor.RED))
            return
        }
        log("PAYMENT_CAPTURED", session, player, dish, session.seat.dish, "amount=${dish.price}")
        meals.remove(session.seat.id)?.let { removeMeal(it, "replaced-by-new-order") }
        meals[session.seat.id] = created
        mealEntity[created.hitbox.uniqueId] = created
        showOwnerGlow(created)
        session.phase = OriginDiningPhase.SERVED
        session.touchedAt = System.currentTimeMillis()
        log(
            "SERVED",
            session,
            player,
            dish,
            session.seat.dish,
            "item_id=${OriginDiningLayout.itemId(dish.id)} display=${short(created.display.uniqueId)}#${created.display.entityId}@${location(created.display.location)} hitbox=${short(created.hitbox.uniqueId)}#${created.hitbox.entityId}@${location(created.hitbox.location)} transform=GROUND display_lift=${fmt(OriginDiningLayout.displayLift(dish.id).toDouble())} scale=${OriginDiningLayout.displayScale(dish.id)} hitbox_size=${OriginDiningLayout.mealHitboxSize} price=${dish.price}",
        )
        player.sendActionBar(Component.text("Блюдо на столе. Нажмите ПКМ, чтобы съесть.", NamedTextColor.GOLD))
        player.playSound(session.seat.dish.inWorld(world), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.15f)
    }

    private fun deliverBreweryDrink(
        session: OriginDiningSession,
        player: Player,
        dish: BreweryTableDialogs.Dish,
    ) {
        val recipe = breweryRecipe(dish.id)
        val quality = breweryQuality(dish.id)
        val slot = player.inventory.firstEmpty()
        if (recipe == null || slot < 0) {
            session.phase = OriginDiningPhase.SEATED
            val reason = if (slot < 0) "inventory-full" else "brewery-recipe-unavailable"
            logWarn("DELIVERY_FAILED", session, player, dish, "$reason-no-charge")
            player.sendActionBar(Component.text("Не удалось подать бутылку. Деньги не списаны.", NamedTextColor.RED))
            return
        }
        log("DELIVERY_COMMIT_BEGIN", session, player, dish, session.seat.seat, "target=inventory slot=$slot recipe=$recipe quality=$quality")
        val commandAccepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "brew give $recipe $quality ${player.name}")
        val issued = player.inventory.getItem(slot)
        val isBreweryDrink =
            issued != null &&
                issued.type == Material.POTION &&
                issued.itemMeta?.persistentDataContainer?.keys?.any { it.namespace.equals("breweryx", ignoreCase = true) && it.key == "brewdata" } == true
        if (!commandAccepted || !isBreweryDrink) {
            if (issued != null && isBreweryDrink) player.inventory.setItem(slot, null)
            session.phase = OriginDiningPhase.SEATED
            logWarn("DELIVERY_FAILED", session, player, dish, "brewery-issue-failed-no-charge command=$commandAccepted slot=$slot material=${issued?.type}")
            player.sendActionBar(Component.text("Пивоварня не выдала бутылку. Деньги не списаны.", NamedTextColor.RED))
            return
        }
        val orderId = UUID.randomUUID()
        NBT.modify(issued) { nbt ->
            nbt.setString("rc_brewery_origin", "shop")
            nbt.setString("arc:origin_dining_order", orderId.toString())
        }
        player.inventory.setItem(slot, issued)
        val economy = EconomyModule.getEconomy()
        val payment = economy?.withdrawPlayer(player, dish.price.toDouble())
        if (payment?.transactionSuccess() != true) {
            player.inventory.setItem(slot, null)
            session.phase = OriginDiningPhase.SEATED
            logWarn("DELIVERY_FAILED", session, player, dish, "payment-rejected-issued-item-rolled-back")
            player.sendActionBar(Component.text("Оплата не прошла. Бутылка возвращена, деньги не списаны.", NamedTextColor.RED))
            return
        }
        session.phase = OriginDiningPhase.SEATED
        session.touchedAt = System.currentTimeMillis()
        log(
            "INVENTORY_DRINK_SERVED",
            session,
            player,
            dish,
            session.seat.seat,
            "slot=$slot recipe=$recipe quality=$quality price=${dish.price} provenance=shop order=${short(orderId)}",
        )
        player.sendActionBar(Component.text("Напиток в инвентаре. Бутылка помечена как выданная заведением.", NamedTextColor.GOLD))
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.65f, 1.15f)
    }

    private fun spawnMeal(
        session: OriginDiningSession,
        dish: BreweryTableDialogs.Dish,
        stack: ItemStack,
    ): OriginDiningMeal {
        val world = requireNotNull(Bukkit.getWorld(OriginDiningLayout.WORLD))
        val anchor = session.seat.dish.inWorld(world)
        val display = world.spawn(anchor, ItemDisplay::class.java)
        var hitbox: Interaction? = null
        try {
            display.setItemStack(stack)
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            display.billboard = Display.Billboard.FIXED
            display.brightness = Display.Brightness(15, 15)
            display.shadowRadius = 0f
            display.shadowStrength = 0f
            display.viewRange = OriginDiningLayout.displayViewRange
            display.displayWidth = OriginDiningLayout.displayWidth
            display.displayHeight = OriginDiningLayout.displayHeight
            display.isPersistent = false
            display.setGravity(false)
            display.isInvulnerable = true
            display.isGlowing = false
            display.glowColorOverride = Color.fromRGB(0x92, 0xBE, 0xD8)
            display.transformation =
                Transformation(
                    Vector3f(0f, OriginDiningLayout.displayLift(dish.id), 0f),
                    AxisAngle4f(),
                    Vector3f(OriginDiningLayout.displayScale(dish.id), OriginDiningLayout.displayScale(dish.id), OriginDiningLayout.displayScale(dish.id)),
                    AxisAngle4f(),
                )
            display.addScoreboardTag(MEAL_TAG)

            hitbox = world.spawn(anchor.clone().add(0.0, OriginDiningLayout.mealHitboxYOffset, 0.0), Interaction::class.java)
            hitbox.interactionWidth = OriginDiningLayout.mealHitboxSize
            hitbox.interactionHeight = OriginDiningLayout.mealHitboxSize
            hitbox.isResponsive = true
            hitbox.isPersistent = false
            hitbox.isInvulnerable = true
            hitbox.addScoreboardTag(MEAL_TAG)
            return OriginDiningMeal(session.id, session.playerId, session.seat, dish, display, hitbox)
        } catch (failure: Throwable) {
            hitbox?.remove()
            display.remove()
            throw failure
        }
    }

    private fun consume(player: Player, meal: OriginDiningMeal) {
        if (!meal.display.isValid || !meal.hitbox.isValid) {
            warn(
                "ORIGIN_DINING phase=CONSUME_REJECTED player={} table={} dish={} display_valid={} hitbox_valid={}",
                player.name, meal.seat.id, meal.dish.id, meal.display.isValid, meal.hitbox.isValid,
            )
            removeMeal(meal, "invalid-before-consume")
            return
        }
        val stolen = player.uniqueId != meal.ownerId
        val now = System.currentTimeMillis()
        if (stolen && theftCooldownUntil.getOrDefault(player.uniqueId, 0L) > now) {
            player.sendActionBar(Component.text("Чужую еду пока трогать нельзя.", NamedTextColor.GRAY))
            warn("ORIGIN_DINING phase=THEFT_REJECTED player={} table={} dish={} reason=cooldown", player.name, meal.seat.id, meal.dish.id)
            return
        }
        if (stolen) theftCooldownUntil[player.uniqueId] = now + OriginDiningLayout.theftCooldownMillis

        val bite = meal.display.location.clone().add(0.0, 0.22, 0.0)
        val food = hunger(meal.dish.id)
        val saturation = saturation(meal.dish.id)
        player.foodLevel = min(20, player.foodLevel + food)
        player.saturation = min(20f, player.saturation + saturation)
        val particleStack = particleItem(meal.dish.id)
        player.spawnParticle(Particle.ITEM, bite, 18, 0.28, 0.13, 0.28, 0.02, particleStack)
        player.spawnParticle(Particle.HEART, bite.clone().add(0.0, 0.25, 0.0), 3, 0.22, 0.12, 0.22, 0.01)
        player.playSound(bite, Sound.ENTITY_GENERIC_EAT, SoundCategory.PLAYERS, 0.75f, 0.96f)
        tasks.runLater(4L) {
            if (player.isOnline) player.playSound(player.location, Sound.ENTITY_GENERIC_EAT, SoundCategory.PLAYERS, 0.62f, 1.14f)
        }
        tasks.runLater(8L) {
            if (player.isOnline) player.playSound(player.location, Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.45f, 1.2f)
        }
        removeMeal(meal, if (stolen) "consumed-by-stranger" else "consumed-by-owner")
        meals.remove(meal.seat.id, meal)
        val ownerSession = sessions[meal.ownerId]?.takeIf { it.id == meal.sessionId }
        ownerSession?.let { session ->
            if (session.phase == OriginDiningPhase.SERVED) session.phase = OriginDiningPhase.SEATED
            session.touchedAt = now
            log("CONSUMED", session, player, meal.dish, meal.seat.dish, "stolen=$stolen hunger=$food saturation=$saturation")
        }
        if (ownerSession == null) {
            info(
                "ORIGIN_DINING phase=CONSUMED session={} player={} owner={} table={} dish={} target={} actual_player={} detail=stolen:{} hunger:{} saturation:{}",
                short(meal.sessionId), player.name, meal.ownerId, meal.seat.id, meal.dish.id, point(meal.seat.dish), location(player.location), stolen, food, saturation,
            )
        }
        if (stolen) scold(player, meal)
        else player.sendActionBar(Component.text("Приятного аппетита.", NamedTextColor.GREEN))
    }

    private fun removeMeal(meal: OriginDiningMeal, reason: String) {
        info(
            "ORIGIN_DINING phase=MEAL_REMOVED session={} owner={} table={} dish={} display={}#{}@{} hitbox={}#{}@{} reason={}",
            short(meal.sessionId),
            meal.ownerId,
            meal.seat.id,
            meal.dish.id,
            short(meal.display.uniqueId),
            meal.display.entityId,
            location(meal.display.location),
            short(meal.hitbox.uniqueId),
            meal.hitbox.entityId,
            location(meal.hitbox.location),
            reason,
        )
        mealEntity.remove(meal.hitbox.uniqueId)
        if (meal.hitbox.isValid) meal.hitbox.remove()
        if (meal.display.isValid) meal.display.remove()
    }

    private fun scold(player: Player, meal: OriginDiningMeal) {
        val name = waiterName(meal.seat.waiterId) ?: "Официант"
        player.sendActionBar(Component.text("$name » Эй! Это чужой заказ. Закажи себе.", NamedTextColor.RED))
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 0.65f, 0.95f)
        Bukkit.getPlayer(meal.ownerId)?.takeIf(Player::isOnline)?.sendActionBar(
            Component.text("${player.name} съел ваш заказ.", NamedTextColor.RED),
        )
    }

    private fun summonWaiter(session: OriginDiningSession, player: Player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        val waiterId = session.seat.waiterId
        if (waiterReadyFor[waiterId] == session.id) return
        cancelAmbientRoute(waiterId, "player-approach")
        val busyReason =
            when {
                waiterId in activeDeliveries -> "active-delivery"
                waiterId in waiterApproaches -> "active-approach"
                waiterId in waiterAssignments -> "already-assigned"
                else -> null
            }
        if (busyReason != null) {
            log("WAITER_APPROACH_QUEUED", session, player, null, session.seat.waiterStop, "npc=$waiterId reason=$busyReason")
            return
        }
        runCatching {
            val npc = CitizensAPI.getNPCRegistry().getById(waiterId) ?: return
            if (!npc.isSpawned || npc.entity.world.name != OriginDiningLayout.WORLD) return
            val approachId = UUID.randomUUID()
            waiterApproaches[waiterId] = approachId
            waiterAssignments[waiterId] = session.id
            npc.entity.addScoreboardTag(WAITER_BUSY_TAG)
            val stop = session.seat.waiterStop.inWorld(npc.entity.world)
            navigateLevel(npc, stop)
            showWaiterGlow(waiterId, session, npc, player)
            log(
                "WAITER_APPROACH",
                session,
                player,
                null,
                session.seat.waiterStop,
                "npc=$waiterId approach=${short(approachId)} actual=${location(npc.entity.location)} target_distance=${fmt(npc.entity.location.distance(stop))}",
            )
            monitorWaiterApproach(session.id, player.uniqueId, waiterId, stop, approachId, 0)
        }.onFailure { failure ->
            warn(
                "ORIGIN_DINING phase=WAITER_FAILED session={} player={} table={} npc={} reason={}",
                short(session.id), player.name, session.seat.id, session.seat.waiterId, failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    private fun monitorWaiterApproach(
        sessionId: UUID,
        playerId: UUID,
        waiterId: Int,
        stop: Location,
        approachId: UUID,
        poll: Int,
    ) {
        tasks.runLater(OriginDiningLayout.waiterPollTicks) {
            if (waiterApproaches[waiterId] != approachId) return@runLater
            val session = sessions[playerId]?.takeIf { it.id == sessionId }
            val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)
            if (session == null || player == null) {
                waiterApproaches.remove(waiterId, approachId)
                returnWaiterHome(waiterId, sessionId, session, "approach-session-gone")
                return@runLater
            }
            if (!session.mounted || !nearVenue(player, session.seat)) {
                waiterApproaches.remove(waiterId, approachId)
                returnWaiterHome(waiterId, sessionId, session, "approach-player-not-seated")
                return@runLater
            }
            val npc = runCatching { CitizensAPI.getNPCRegistry().getById(waiterId) }.getOrNull()
            if (npc == null || !npc.isSpawned || npc.entity.world != stop.world) {
                waiterApproaches.remove(waiterId, approachId)
                waiterAssignments.remove(waiterId, sessionId)
                logWarn("WAITER_FAILED", session, player, null, "npc=$waiterId approach=${short(approachId)} reason=despawned")
                return@runLater
            }
            val actual = npc.entity.location
            val targetDistance = actual.distance(stop)
            val playerDistance = actual.distance(player.location)
            // Ready means the player can actually right-click the waiter in
            // survival. Reaching only the configured stop can leave a table
            // between them and silently discard the interaction packet.
            if (playerDistance <= OriginDiningLayout.waiterPlayerRange) {
                waiterApproaches.remove(waiterId, approachId)
                stopWaiterNavigation(npc)
                npc.faceLocation(player.eyeLocation)
                waiterReadyFor[waiterId] = session.id
                showWaiterHitbox(waiterId, session, npc)
                player.sendActionBar(Component.text("Официант подошёл. Нажмите по нему, чтобы открыть меню.", NamedTextColor.GOLD))
                player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, SoundCategory.PLAYERS, 0.45f, 1.2f)
                log(
                    "WAITER_READY",
                    session,
                    player,
                    null,
                    session.seat.waiterStop,
                    "npc=$waiterId approach=${short(approachId)} actual=${location(actual)} target_distance=${fmt(targetDistance)} player_distance=${fmt(playerDistance)} poll=$poll",
                )
                return@runLater
            }
            if (poll >= OriginDiningLayout.waiterMaxPolls) {
                waiterApproaches.remove(waiterId, approachId)
                logWarn(
                    "WAITER_STALLED",
                    session,
                    player,
                    null,
                    "npc=$waiterId approach=${short(approachId)} actual=${location(actual)} target=${location(stop)} target_distance=${fmt(targetDistance)} player_distance=${fmt(playerDistance)} navigating=${npc.navigator.isNavigating}",
                )
                returnWaiterHome(session, "approach-stalled")
                return@runLater
            }
            if (!isWaiterNavigating(npc)) {
                navigateLevel(npc, stop)
                log(
                    "WAITER_RETRY",
                    session,
                    player,
                    null,
                    session.seat.waiterStop,
                    "npc=$waiterId approach=${short(approachId)} actual=${location(actual)} target_distance=${fmt(targetDistance)} poll=$poll",
                )
            } else if (poll % OriginDiningLayout.waiterProgressEveryPolls == 0) {
                log(
                    "WAITER_PROGRESS",
                    session,
                    player,
                    null,
                    session.seat.waiterStop,
                    "npc=$waiterId approach=${short(approachId)} actual=${location(actual)} target_distance=${fmt(targetDistance)} player_distance=${fmt(playerDistance)} poll=$poll",
                )
            }
            monitorWaiterApproach(sessionId, playerId, waiterId, stop, approachId, poll + 1)
        }
    }

    private fun waiterName(id: Int): String? =
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) null
        else runCatching { CitizensAPI.getNPCRegistry().getById(id)?.name }.getOrNull()

    private fun cleanupAmbientLegacy() {
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD) ?: return
        var removed = 0
        OriginDiningAmbientLayout.guestSeats.forEach { guest ->
            val seat = guest.seat.inWorld(world)
            world.getNearbyEntities(seat.clone().add(0.0, 0.6, 0.0), 0.8, 0.8, 0.8)
                .filterIsInstance<ArmorStand>()
                .filter { it.isMarker && !it.isVisible && it.passengers.isEmpty() }
                .forEach { it.remove(); removed++ }
            world.getNearbyEntities(seat.clone().add(0.0, 2.4, 0.0), 0.9, 0.8, 0.9)
                .filterIsInstance<TextDisplay>()
                .forEach { it.remove(); removed++ }
        }
        OriginDiningAmbientLayout.guestTables.forEach { table ->
            val meal = table.meal.inWorld(world)
            world.getNearbyEntities(meal, 0.18, 0.18, 0.18)
                .filterIsInstance<ItemDisplay>()
                .filter(::isLegacyDishDisplay)
                .forEach { it.remove(); removed++ }
            world.getNearbyEntities(meal.clone().add(0.0, OriginDiningLayout.mealHitboxYOffset, 0.0), 0.18, 0.18, 0.18)
                .filterIsInstance<Interaction>()
                .forEach { it.remove(); removed++ }
        }
        OriginDiningAmbientLayout.cleanupPoints.forEach { point ->
            world.getNearbyEntities(point.inWorld(world), 1.0, 1.0, 1.0)
                .filterIsInstance<TextDisplay>()
                .forEach { it.remove(); removed++ }
        }
        OriginDiningAmbientLayout.legacyFurnitureCleanupPoints.forEach { cleanupPoint ->
            val handles = world.getNearbyEntities(cleanupPoint.inWorld(world), 0.45, 0.45, 0.45)
                .mapNotNull(ItemsAdderFurnitureRuntime::inspect)
                .distinctBy { it.root.uniqueId }
                .filter { it.namespacedId in OriginDiningAmbientLayout.legacyFurnitureCleanupIds }
            handles.forEach { handle ->
                if (ItemsAdderFurnitureRuntime.remove(handle.root, handle.family)) {
                    removed++
                    info(
                        "ORIGIN_DINING phase=LEGACY_FURNITURE_CLEANUP status=removed id={} entity={} target={}",
                        handle.namespacedId,
                        short(handle.root.uniqueId),
                        point(cleanupPoint),
                    )
                } else {
                    warn(
                        "ORIGIN_DINING phase=LEGACY_FURNITURE_CLEANUP status=failed id={} entity={} target={}",
                        handle.namespacedId,
                        short(handle.root.uniqueId),
                        point(cleanupPoint),
                    )
                }
            }
        }
        info("ORIGIN_DINING phase=AMBIENT_LEGACY_CLEANUP removed={}", removed)
    }

    private fun reconcileGuestSeats() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD) ?: return
        OriginDiningAmbientLayout.guestSeats.forEach { guest ->
            val npc = runCatching { CitizensAPI.getNPCRegistry().getById(guest.npcId) }.getOrNull()
                ?.takeIf { it.isSpawned && it.entity.world == world } ?: return@forEach
            val current = guestMarkers[guest.npcId]
            if (current?.isValid == true && npc.entity.vehicle?.uniqueId == current.uniqueId) {
                return@forEach
            }
            current?.takeIf { it.isValid }?.remove()
            if (npc.entity.isInsideVehicle) npc.entity.leaveVehicle()
            val seated = guest.seat.inWorld(world).add(0.0, OriginDiningLayout.guestEntityLift, 0.0)
            npc.entity.teleport(seated)
            val marker = world.spawn(guest.seat.inWorld(world).add(0.0, OriginDiningLayout.guestMarkerLift, 0.0), ArmorStand::class.java).apply {
                isVisible = false
                isMarker = true
                setGravity(false)
                isInvulnerable = true
                isSilent = true
                isPersistent = false
                addScoreboardTag(GUEST_MARKER_TAG)
            }
            marker.addPassenger(npc.entity)
            npc.entity.setRotation(guest.seat.yaw, 0f)
            guestMarkers[guest.npcId] = marker
            info("ORIGIN_DINING phase=GUEST_SEATED npc={} target={} marker={}", guest.npcId, point(guest.seat), short(marker.uniqueId))
        }
    }

    private fun seedGuestMeals() {
        OriginDiningAmbientLayout.guestTables.forEachIndexed { index, table ->
            if (table.id !in guestMeals) replaceGuestMeal(table, BreweryTableDialogs.food[index % BreweryTableDialogs.food.size], "scene-start")
        }
    }

    private fun spawnGuestMeal(table: OriginDiningGuestTable, dish: BreweryTableDialogs.Dish): OriginDiningGuestMeal? {
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD) ?: return null
        val stack = dishItem(dish) ?: return null
        val anchor = table.meal.inWorld(world)
        val display = world.spawn(anchor, ItemDisplay::class.java)
        var hitbox: Interaction? = null
        return try {
            display.setItemStack(stack)
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            display.billboard = Display.Billboard.FIXED
            display.brightness = Display.Brightness(15, 15)
            display.shadowRadius = 0f
            display.shadowStrength = 0f
            display.viewRange = OriginDiningLayout.displayViewRange
            display.displayWidth = OriginDiningLayout.displayWidth
            display.displayHeight = OriginDiningLayout.displayHeight
            display.isPersistent = false
            display.setGravity(false)
            display.isInvulnerable = true
            display.transformation = Transformation(
                Vector3f(0f, OriginDiningLayout.displayLift(dish.id), 0f),
                AxisAngle4f(),
                Vector3f(OriginDiningLayout.displayScale(dish.id), OriginDiningLayout.displayScale(dish.id), OriginDiningLayout.displayScale(dish.id)),
                AxisAngle4f(),
            )
            display.addScoreboardTag(GUEST_MEAL_TAG)
            hitbox = world.spawn(anchor.clone().add(0.0, OriginDiningLayout.mealHitboxYOffset, 0.0), Interaction::class.java)
            hitbox.interactionWidth = OriginDiningLayout.mealHitboxSize
            hitbox.interactionHeight = OriginDiningLayout.mealHitboxSize
            hitbox.isResponsive = true
            hitbox.isPersistent = false
            hitbox.isInvulnerable = true
            hitbox.addScoreboardTag(GUEST_MEAL_TAG)
            OriginDiningGuestMeal(table, dish, display, hitbox)
        } catch (failure: Throwable) {
            hitbox?.remove()
            display.remove()
            warn("ORIGIN_DINING phase=AMBIENT_MEAL_FAILED table={} dish={} reason={}", table.id, dish.id, failure.message ?: failure.javaClass.simpleName)
            null
        }
    }

    private fun replaceGuestMeal(table: OriginDiningGuestTable, dish: BreweryTableDialogs.Dish, reason: String) {
        guestMeals.remove(table.id)?.let(::removeGuestMeal)
        val meal = spawnGuestMeal(table, dish) ?: return
        guestMeals[table.id] = meal
        guestMealEntity[meal.hitbox.uniqueId] = meal
        ambientTableDueAt.remove(table.id)
        info(
            "ORIGIN_DINING phase=AMBIENT_MEAL_SERVED table={} guest={} npc={} dish={} target={} display={} hitbox={} reason={}",
            table.id, table.npcId, table.waiterId, dish.id, point(table.meal), short(meal.display.uniqueId), short(meal.hitbox.uniqueId), reason,
        )
    }

    private fun removeGuestMeal(meal: OriginDiningGuestMeal) {
        guestMealEntity.remove(meal.hitbox.uniqueId)
        if (meal.hitbox.isValid) meal.hitbox.remove()
        if (meal.display.isValid) meal.display.remove()
    }

    private fun consumeGuestMeal(player: Player, meal: OriginDiningGuestMeal) {
        if (!meal.display.isValid || !meal.hitbox.isValid || guestMeals[meal.table.id] !== meal) return
        val now = System.currentTimeMillis()
        if (theftCooldownUntil.getOrDefault(player.uniqueId, 0L) > now) {
            player.sendActionBar(Component.text("Хватит таскать чужое. Следующее блюдо закажи себе.", NamedTextColor.GRAY))
            return
        }
        theftCooldownUntil[player.uniqueId] = now + OriginDiningLayout.theftCooldownMillis
        val bite = meal.display.location.clone().add(0.0, 0.22, 0.0)
        player.foodLevel = min(20, player.foodLevel + hunger(meal.dish.id))
        player.saturation = min(20f, player.saturation + saturation(meal.dish.id))
        player.spawnParticle(Particle.ITEM, bite, 18, 0.28, 0.13, 0.28, 0.02, particleItem(meal.dish.id))
        player.playSound(bite, Sound.ENTITY_GENERIC_EAT, SoundCategory.PLAYERS, 0.75f, 0.96f)
        guestMeals.remove(meal.table.id, meal)
        removeGuestMeal(meal)
        ambientTableDueAt[meal.table.id] = now
        scoldGuest(player, meal.table)
        info("ORIGIN_DINING phase=AMBIENT_MEAL_STOLEN player={} table={} guest={} dish={} refill=queued", player.name, meal.table.id, meal.table.npcId, meal.dish.id)
        tasks.runLater(2L) { startAmbientRoute(meal.table, "stolen-refill") }
    }

    private fun scoldGuest(player: Player, table: OriginDiningGuestTable) {
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(table.npcId) }.getOrNull()?.takeIf { it.isSpawned } ?: return
        npc.faceLocation(player.eyeLocation)
        (npc.entity as? LivingEntity)?.swingMainHand()
        val lines = listOf("Эй, это мой обед!", "Руки от моей тарелки!", "Закажи себе, воришка.")
        showSpeech(npc.id, lines.random(), NamedTextColor.RED)
        tasks.runLater(55L) { restoreGuestLook(table.npcId) }
    }

    private fun restoreGuestLook(npcId: Int) {
        val guest = OriginDiningAmbientLayout.guestSeats.firstOrNull { it.npcId == npcId } ?: return
        waiter(npcId)?.takeIf { it.isSpawned }?.entity?.setRotation(guest.seat.yaw, 0f)
    }

    private fun showSpeech(npcId: Int, line: String, color: NamedTextColor = NamedTextColor.GOLD) {
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(npcId) }.getOrNull()?.takeIf { it.isSpawned } ?: return
        val display = npc.entity.world.spawn(npc.entity.location.clone().add(0.0, 2.45, 0.0), TextDisplay::class.java).apply {
            text(Component.text(line, color))
            billboard = Display.Billboard.CENTER
            isShadowed = true
            lineWidth = 170
            viewRange = 0.5f
            isPersistent = false
            addScoreboardTag(SPEECH_TAG)
        }
        speechDisplays += display
        tasks.runLater(45L) {
            speechDisplays.remove(display)
            if (display.isValid) display.remove()
        }
    }

    private fun startAmbientRoute(table: OriginDiningGuestTable, reason: String): Boolean {
        val waiterId = table.waiterId
        if (System.currentTimeMillis() < ambientWaiterAvailableAt.getOrDefault(waiterId, 0L)) return false
        if (waiterId in ambientRoutes || waiterId in activeDeliveries || waiterId in waiterApproaches || waiterId in waiterAssignments) return false
        val npc = waiter(waiterId)?.takeIf { it.isSpawned && it.entity.world.name == OriginDiningLayout.WORLD } ?: return false
        val currentDish = guestMeals[table.id]?.dish
        val choices = BreweryTableDialogs.food.filter { it.id != currentDish?.id }
        val dish = choices[Math.floorMod(ambientCursor + table.id.hashCode(), choices.size)]
        val world = npc.entity.world
        val waypoints = buildList {
            table.transit?.let { add(it.inWorld(world)) }
            add(table.waiterStop.inWorld(world))
        }
        val route = OriginDiningAmbientRoute(UUID.randomUUID(), table, dish, waypoints)
        ambientRoutes[waiterId] = route
        setWaiterCarry(waiterId, dish)
        npc.entity.addScoreboardTag(WAITER_BUSY_TAG)
        navigateLevel(npc, waypoints.first())
        info("ORIGIN_DINING phase=AMBIENT_ROUTE_STARTED npc={} table={} guest={} dish={} reason={} target={}", waiterId, table.id, table.npcId, dish.id, reason, location(waypoints.last()))
        monitorAmbientRoute(waiterId, route.token, 0)
        return true
    }

    private fun monitorAmbientRoute(waiterId: Int, token: UUID, poll: Int) {
        tasks.runLater(OriginDiningLayout.waiterPollTicks) {
            val route = ambientRoutes[waiterId]?.takeIf { it.token == token } ?: return@runLater
            val npc = waiter(waiterId)?.takeIf { it.isSpawned } ?: run {
                cancelAmbientRoute(waiterId, "npc-unavailable")
                return@runLater
            }
            val destination = route.waypoints[route.waypoint]
            val distance = npc.entity.location.distance(destination)
            if (distance <= OriginDiningLayout.waiterReadyMargin) {
                if (route.waypoint + 1 < route.waypoints.size) {
                    route.waypoint++
                    navigateLevel(npc, route.waypoints[route.waypoint])
                    monitorAmbientRoute(waiterId, token, 0)
                    return@runLater
                }
                stopWaiterNavigation(npc)
                replaceGuestMeal(route.table, route.dish, "waiter-cycle")
                val guest = runCatching { CitizensAPI.getNPCRegistry().getById(route.table.npcId) }.getOrNull()?.takeIf { it.isSpawned }
                if (guest != null) npc.faceLocation(guest.entity.location.clone().add(0.0, 1.4, 0.0))
                (npc.entity as? LivingEntity)?.swingMainHand()
                npc.entity.world.playSound(route.table.meal.inWorld(npc.entity.world), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.1f)
                showSpeech(waiterId, "Новое блюдо. Приятного аппетита.")
                tasks.runLater(24L) {
                    showSpeech(route.table.npcId, "Спасибо. Как раз вовремя.")
                    finishAmbientRoute(waiterId, token, "served")
                }
                return@runLater
            }
            if (poll >= OriginDiningLayout.ambientRouteMaxPolls) {
                cancelAmbientRoute(waiterId, "stalled")
                return@runLater
            }
            if (!isWaiterNavigating(npc)) navigateLevel(npc, destination)
            monitorAmbientRoute(waiterId, token, poll + 1)
        }
    }

    private fun finishAmbientRoute(waiterId: Int, token: UUID, reason: String) {
        val route = ambientRoutes[waiterId]?.takeIf { it.token == token } ?: return
        ambientRoutes.remove(waiterId, route)
        val restMillis = OriginDiningLayout.ambientWaiterRestMillis
        val restTicks = (restMillis / 50L).coerceAtLeast(1L)
        ambientWaiterAvailableAt[waiterId] = System.currentTimeMillis() + restMillis
        clearWaiterCarry(waiterId)
        val npc = waiter(waiterId)?.takeIf { it.isSpawned } ?: return
        val home = OriginDiningLayout.waiterHome(waiterId)?.inWorld(npc.entity.world)
        stopWaiterNavigation(npc)
        tasks.runLater(restTicks) {
            if (waiterId !in ambientRoutes && waiterId !in activeDeliveries && waiterId !in waiterAssignments && waiterId !in waiterApproaches) {
                waiter(waiterId)?.takeIf { it.isSpawned }?.let { current ->
                    if (home != null) navigateLevel(current, home)
                }
            }
        }
        tasks.runLater(restTicks + OriginDiningLayout.waiterReturnReleaseTicks) {
            if (waiterId !in ambientRoutes && waiterId !in activeDeliveries && waiterId !in waiterAssignments && waiterId !in waiterApproaches) {
                waiter(waiterId)?.takeIf { it.isSpawned }?.entity?.removeScoreboardTag(WAITER_BUSY_TAG)
            }
        }
        info(
            "ORIGIN_DINING phase=AMBIENT_ROUTE_FINISHED npc={} table={} reason={} rest_ms={}",
            waiterId,
            route.table.id,
            reason,
            restMillis,
        )
    }

    private fun cancelAmbientRoute(waiterId: Int, reason: String) {
        val route = ambientRoutes.remove(waiterId) ?: return
        waiter(waiterId)?.takeIf { it.isSpawned }?.let { npc ->
            stopWaiterNavigation(npc)
            npc.entity.removeScoreboardTag(WAITER_BUSY_TAG)
        }
        clearWaiterCarry(waiterId)
        ambientTableDueAt[route.table.id] = System.currentTimeMillis() + OriginDiningLayout.ambientRetryMillis
        info("ORIGIN_DINING phase=AMBIENT_ROUTE_CANCELLED npc={} table={} reason={}", waiterId, route.table.id, reason)
    }

    private fun tickAmbient(now: Long) {
        val tables = OriginDiningAmbientLayout.guestTables
        var routesStarted = 0
        val urgentTables = tables.filter { (ambientTableDueAt[it.id] ?: Long.MAX_VALUE) <= now }
        for (table in urgentTables) {
            if (startAmbientRoute(table, "queued-refill")) {
                routesStarted++
                if (routesStarted >= OriginDiningAmbientLayout.routesPerCycle) break
            }
        }
        if (routesStarted < OriginDiningAmbientLayout.routesPerCycle && now >= nextAmbientAt && tables.isNotEmpty()) {
            for (attempt in tables.indices) {
                val table = tables[ambientCursor++ % tables.size]
                if (table.id !in ambientTableDueAt && startAmbientRoute(table, "rotation")) {
                    routesStarted++
                    if (routesStarted >= OriginDiningAmbientLayout.routesPerCycle) break
                }
            }
        }
        if (routesStarted > 0) nextAmbientAt = now + OriginDiningAmbientLayout.cycleSeconds * 1_000L
        if (now >= nextDialogueAt && OriginDiningAmbientLayout.dialogue.isNotEmpty()) {
            val dialogue = OriginDiningAmbientLayout.dialogue[dialogueCursor++ % OriginDiningAmbientLayout.dialogue.size]
            val first = waiter(dialogue.firstNpcId)?.takeIf { it.isSpawned }
            val second = waiter(dialogue.secondNpcId)?.takeIf { it.isSpawned }
            if (first != null && second != null && first.entity.location.distanceSquared(second.entity.location) <= 64.0) {
                first.faceLocation(second.entity.location.clone().add(0.0, 1.4, 0.0))
                showSpeech(first.id, dialogue.firstLine)
                tasks.runLater(24L) {
                    val currentSecond = waiter(dialogue.secondNpcId)?.takeIf { it.isSpawned } ?: return@runLater
                    currentSecond.faceLocation(first.entity.location.clone().add(0.0, 1.4, 0.0))
                    showSpeech(currentSecond.id, dialogue.secondLine)
                    tasks.runLater(45L) {
                        restoreGuestLook(dialogue.firstNpcId)
                        restoreGuestLook(dialogue.secondNpcId)
                    }
                }
            }
            nextDialogueAt = now + OriginDiningLayout.ambientDialogueMillis
        }
    }

    private fun reconcile() {
        val now = System.currentTimeMillis()
        if (now >= nextGuestSeatReconcileAt) {
            reconcileGuestSeats()
            nextGuestSeatReconcileAt = now + OriginDiningLayout.guestReconcileMillis
        }
        tickAmbient(now)
        sessions.values.toList().forEach { session ->
            val player = Bukkit.getPlayer(session.playerId)
            if (player == null || !player.isOnline) {
                releaseSession(session.playerId, "offline")
                return@forEach
            }
            if (!nearVenue(player, session.seat) || now - session.touchedAt > OriginDiningLayout.sessionTtlMillis) {
                releaseSession(session.playerId, "left-table")
                return@forEach
            }
            if (session.mounted && restaurantChairVehicle(player, session.seat) == null) {
                session.mounted = false
                dialogAuthorizations.remove(session.playerId)
                if (session.phase != OriginDiningPhase.ORDERED) returnWaiterHome(session, "chair-state-lost")
                logWarn("CHAIR_STATE_LOST", session, player, meals[session.seat.id]?.dish, "vehicle=${player.vehicle?.entityId ?: "none"}")
            } else if (session.mounted) {
                session.touchedAt = now
            }
            if (session.seat.dynamic && player.world.getBlockAt(session.seat.clickedBlock.first, session.seat.clickedBlock.second, session.seat.clickedBlock.third).blockData !is Stairs) {
                releaseSession(session.playerId, "dynamic-seat-removed")
                return@forEach
            }
            if (
                session.mounted &&
                session.phase == OriginDiningPhase.SEATED &&
                session.seat.id !in meals &&
                waiterReadyFor[session.seat.waiterId] != session.id &&
                session.seat.waiterId !in waiterApproaches &&
                session.seat.waiterId !in activeDeliveries &&
                session.seat.waiterId !in waiterAssignments
            ) {
                summonWaiter(session, player)
            }
        }
        meals.values.toList().forEach { meal ->
            if (!meal.display.isValid || !meal.hitbox.isValid) {
                warn(
                    "ORIGIN_DINING phase=MEAL_LOST session={} player={} table={} dish={} expected={} display_valid={} hitbox_valid={}",
                    short(meal.sessionId), meal.ownerId, meal.seat.id, meal.dish.id, point(meal.seat.dish), meal.display.isValid, meal.hitbox.isValid,
                )
                removeMeal(meal, "entity-invalid")
                meals.remove(meal.seat.id, meal)
            } else {
                showOwnerGlow(meal)
            }
        }
    }

    private fun showOwnerGlow(meal: OriginDiningMeal) {
        val owner = Bukkit.getPlayer(meal.ownerId)?.takeIf(Player::isOnline) ?: return
        val packetHook = HookRegistry.packetEventsHook
        if (packetHook == null) meal.display.isGlowing = true
        else packetHook.setDisplayGlowingFor(meal.display, owner, true)
    }

    private fun setWaiterGlow(
        npc: net.citizensnpcs.api.npc.NPC,
        player: Player?,
        glowing: Boolean,
    ) {
        val entity = npc.entity as? LivingEntity ?: return
        val packetHook = HookRegistry.packetEventsHook
        if (packetHook == null) entity.isGlowing = glowing
        else player?.takeIf(Player::isOnline)?.let { packetHook.setEntityGlowingFor(entity, it, glowing) }
    }

    private fun showWaiterGlow(
        waiterId: Int,
        session: OriginDiningSession,
        npc: net.citizensnpcs.api.npc.NPC,
        player: Player,
    ) {
        val previous = waiterGlowingFor[waiterId]
        if (previous != null && previous != session.id) clearWaiterGlow(waiterId, "reassigned", previous)
        waiterGlowingFor[waiterId] = session.id
        setWaiterGlow(npc, player, true)
        info(
            "ORIGIN_DINING phase=WAITER_GLOW_STARTED npc={} session={} player={} actual={}",
            waiterId,
            short(session.id),
            player.name,
            location(npc.entity.location),
        )
    }

    private fun clearWaiterGlow(
        waiterId: Int,
        reason: String,
        expectedSessionId: UUID? = null,
    ) {
        val sessionId = waiterGlowingFor[waiterId] ?: return
        if (expectedSessionId != null && sessionId != expectedSessionId) return
        if (!waiterGlowingFor.remove(waiterId, sessionId)) return
        val session = sessions.values.firstOrNull { it.id == sessionId }
        val player = session?.playerId?.let(Bukkit::getPlayer)
        waiter(waiterId)?.takeIf { it.isSpawned }?.let { setWaiterGlow(it, player, false) }
        info(
            "ORIGIN_DINING phase=WAITER_GLOW_CLEARED npc={} session={} player={} reason={}",
            waiterId,
            short(sessionId),
            player?.name ?: session?.playerId ?: "offline",
            reason,
        )
    }

    private fun clearWaiterReady(
        waiterId: Int,
        reason: String,
        expectedSessionId: UUID? = null,
    ) {
        val sessionId = waiterReadyFor[waiterId]
        if (sessionId != null && (expectedSessionId == null || sessionId == expectedSessionId)) {
            waiterReadyFor.remove(waiterId, sessionId)
        }
        clearWaiterHitbox(waiterId, expectedSessionId)
        clearWaiterGlow(waiterId, reason, expectedSessionId)
    }

    private fun showWaiterHitbox(waiterId: Int, session: OriginDiningSession, npc: net.citizensnpcs.api.npc.NPC) {
        clearWaiterHitbox(waiterId)
        val entity = npc.entity.world.spawn(npc.entity.location, Interaction::class.java).apply {
            interactionWidth = OriginDiningLayout.waiterHitboxWidth
            interactionHeight = OriginDiningLayout.waiterHitboxHeight
            isResponsive = true
            isPersistent = false
            isInvulnerable = true
            addScoreboardTag(WAITER_HITBOX_TAG)
        }
        val hitbox = OriginDiningWaiterHitbox(session.id, waiterId, entity)
        waiterHitboxes[waiterId] = hitbox
        waiterHitboxEntity[entity.uniqueId] = hitbox
        info(
            "ORIGIN_DINING phase=WAITER_HITBOX_READY npc={} session={} hitbox={} actual={} width={} height={}",
            waiterId,
            short(session.id),
            short(entity.uniqueId),
            location(entity.location),
            OriginDiningLayout.waiterHitboxWidth,
            OriginDiningLayout.waiterHitboxHeight,
        )
    }

    private fun clearWaiterHitbox(waiterId: Int, expectedSessionId: UUID? = null) {
        val hitbox = waiterHitboxes[waiterId] ?: return
        if (expectedSessionId != null && hitbox.sessionId != expectedSessionId) return
        waiterHitboxes.remove(waiterId, hitbox)
        waiterHitboxEntity.remove(hitbox.entity.uniqueId, hitbox)
        if (hitbox.entity.isValid) hitbox.entity.remove()
    }

    private fun releaseSession(playerId: UUID, reason: String) {
        pendingSeatAttempts.remove(playerId)
        val session = sessions[playerId] ?: return
        clearWaiterReady(session.seat.waiterId, "session-$reason", session.id)
        clearWaiterGlow(session.seat.waiterId, "session-$reason", session.id)
        if (!sessions.remove(playerId, session)) return
        dialogAuthorizations.remove(playerId)
        occupants.remove(session.seat.id, playerId)
        val player = Bukkit.getPlayer(playerId)
        returnWaiterHome(session, "session-$reason")
        log("SEAT_RELEASED", session, player, null, session.seat.seat, "reason=$reason")
    }

    private fun releaseWaiter(waiterId: Int, force: Boolean = false) {
        if (!force && (waiterId in activeDeliveries || waiterId in waiterAssignments)) return
        waiterApproaches.remove(waiterId)
        clearWaiterReady(waiterId, "waiter-release")
        clearWaiterGlow(waiterId, "waiter-release")
        if (force) {
            activeDeliveries.remove(waiterId)
            waiterAssignments.remove(waiterId)
            deliveryQueues.remove(waiterId)
        }
        clearWaiterCarry(waiterId)
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        runCatching { CitizensAPI.getNPCRegistry().getById(waiterId) }.getOrNull()?.takeIf { it.isSpawned }?.let { npc ->
            if (force) stopWaiterNavigation(npc)
            npc.entity.removeScoreboardTag(WAITER_BUSY_TAG)
        }
    }

    private fun cleanupLegacy(world: org.bukkit.World, pass: String) {
        // The two-seat legacy controller is fully retired and cannot clean up
        // its own persisted entities. Every retired service anchor is exact so
        // unrelated scene entities outside these points remain untouched.
        val seatAnchors = OriginDiningLayout.seats.map { it.seat }
        var removed = 0
        for (anchor in seatAnchors) {
            val point = anchor.inWorld(world)
            for (entity in world.getNearbyEntities(point, 0.35, 0.8, 0.35)) {
                if (entity.scoreboardTags.contains(SEAT_TAG) || entity.scoreboardTags.contains(MEAL_TAG)) continue
                if (entity is Interaction || entity is ArmorStand && entity.isMarker && !entity.isVisible && entity.passengers.isEmpty()) {
                    entity.remove()
                    removed++
                }
            }
        }
        val oldMealHitbox = OriginDiningAmbientLayout.legacyMealHitbox.inWorld(world)
        for (entity in world.getNearbyEntities(oldMealHitbox, 0.18, 0.18, 0.18)) {
            if (entity.scoreboardTags.contains(MEAL_TAG)) continue
            if (entity is Interaction) {
                entity.remove()
                removed++
            }
        }
        val oldMealDisplay = OriginDiningAmbientLayout.legacyMealDisplay.inWorld(world)
        for (entity in world.getNearbyEntities(oldMealDisplay, 0.18, 0.18, 0.18)) {
            if (entity.scoreboardTags.contains(MEAL_TAG)) continue
            if (entity is ItemDisplay && isLegacyDishDisplay(entity)) {
                entity.remove()
                removed++
            }
        }
        val oldLabel = OriginDiningAmbientLayout.legacyOrderLabel.inWorld(world)
        world.getNearbyEntities(oldLabel, 0.6, 0.6, 0.6).filterIsInstance<TextDisplay>().forEach {
            it.remove()
            removed++
        }
        info("ORIGIN_DINING phase=LEGACY_CLEANUP pass={} removed={}", pass, removed)
    }

    @Suppress("DEPRECATION")
    private fun isLegacyDishDisplay(display: ItemDisplay): Boolean {
        val meta = display.itemStack.itemMeta ?: return false
        return meta.hasCustomModelData() && meta.customModelData in LEGACY_DISH_MODEL_DATA
    }

    private fun removeRuntimeEntities() {
        ambientRoutes.keys.toList().forEach { cancelAmbientRoute(it, "service-stop") }
        meals.values.forEach { removeMeal(it, "service-stop") }
        guestMeals.values.forEach(::removeGuestMeal)
        guestMarkers.values.forEach { if (it.isValid) it.remove() }
        speechDisplays.forEach { if (it.isValid) it.remove() }
        OriginDiningLayout.seats.map(OriginDiningSeat::waiterId).distinct().forEach { releaseWaiter(it, force = true) }
    }

    override fun close() {
        tasks.close()
        routeController.close()
        removeRuntimeEntities()
        sessions.clear()
        occupants.clear()
        meals.clear()
        mealEntity.clear()
        clickAt.clear()
        dialogAuthorizations.clear()
        theftCooldownUntil.clear()
        deliveryQueues.clear()
        activeDeliveries.clear()
        waiterAssignments.clear()
        waiterHeldItems.clear()
        waiterReadyFor.clear()
        waiterGlowingFor.clear()
        waiterHitboxes.values.forEach { if (it.entity.isValid) it.entity.remove() }
        waiterHitboxes.clear()
        waiterHitboxEntity.clear()
        pendingSeatAttempts.clear()
        guestMeals.clear()
        guestMealEntity.clear()
        guestMarkers.clear()
        ambientRoutes.clear()
        ambientWaiterAvailableAt.clear()
        ambientTableDueAt.clear()
        speechDisplays.clear()
        info("ORIGIN_DINING phase=STOPPED")
    }

    private fun dishItem(dish: BreweryTableDialogs.Dish): ItemStack? {
        val id = OriginDiningLayout.itemId(dish.id) ?: return null
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null
        return runCatching { CustomStack.getInstance(id)?.itemStack?.clone() }.getOrNull()
    }

    private fun breweryRecipe(dishId: String): String? =
        when (dishId) {
            "herbal_tea" -> "beer"
            "berry_kvass" -> "wine"
            "spiced_mead" -> "mead"
            else -> null
        }

    private fun breweryQuality(dishId: String): Int =
        when (dishId) {
            "herbal_tea" -> 5
            "berry_kvass" -> 7
            "spiced_mead" -> 9
            else -> 5
        }

    private fun particleItem(id: String): ItemStack =
        ItemStack(
            when (id) {
                "egg" -> Material.COOKED_CHICKEN
                "fish" -> Material.COOKED_COD
                "steak" -> Material.COOKED_BEEF
                "berry_kvass" -> Material.SWEET_BERRIES
                else -> Material.HONEY_BOTTLE
            },
        )

    private fun hunger(id: String): Int =
        when (id) {
            "egg" -> 5
            "fish" -> 7
            "steak" -> 9
            "herbal_tea" -> 2
            "berry_kvass" -> 3
            "spiced_mead" -> 4
            else -> 0
        }

    private fun saturation(id: String): Float =
        when (id) {
            "egg" -> 3f
            "fish" -> 5f
            "steak" -> 7f
            "herbal_tea" -> 1f
            "berry_kvass" -> 2f
            "spiced_mead" -> 3f
            else -> 0f
        }

    private fun debounce(player: Player): Boolean {
        val now = System.currentTimeMillis()
        val before = clickAt.put(player.uniqueId, now)
        return before != null && now - before < 250L
    }

    private fun nearVenue(player: Player, seat: OriginDiningSeat): Boolean {
        if (!player.isOnline || player.world.name != OriginDiningLayout.WORLD) return false
        return player.location.distanceSquared(seat.seat.inWorld(player.world)) <= OriginDiningLayout.sessionRadius * OriginDiningLayout.sessionRadius
    }

    private fun nearestSeat(location: Location, radius: Double): OriginDiningSeat? {
        val world = location.world ?: return null
        if (world.name != OriginDiningLayout.WORLD) return null
        return seatsById.values.firstOrNull { it.seat.inWorld(world).distanceSquared(location) <= radius * radius }
    }

    private fun log(
        phase: String,
        session: OriginDiningSession,
        player: Player?,
        dish: BreweryTableDialogs.Dish?,
        target: OriginDiningPoint,
        detail: String?,
    ) {
        info(
            "ORIGIN_DINING phase={} state={} session={} player={} table={} dish={} target={} actual_player={} detail={}",
            phase,
            session.phase,
            short(session.id),
            player?.name ?: session.playerId,
            session.seat.id,
            dish?.id ?: "none",
            point(target),
            player?.location?.let(::location) ?: "offline",
            detail ?: "none",
        )
    }

    private fun logWarn(
        phase: String,
        session: OriginDiningSession?,
        player: Player?,
        dish: BreweryTableDialogs.Dish?,
        reason: String,
    ) {
        warn(
            "ORIGIN_DINING phase={} session={} player={} table={} dish={} target={} reason={}",
            phase,
            session?.id?.let(::short) ?: "none",
            player?.name ?: session?.playerId ?: "unknown",
            session?.seat?.id ?: "none",
            dish?.id ?: "none",
            session?.seat?.dish?.let(::point) ?: "none",
            reason,
        )
    }

    private fun point(point: OriginDiningPoint): String = "${fmt(point.x)},${fmt(point.y)},${fmt(point.z)}"

    private fun location(location: Location): String = "${fmt(location.x)},${fmt(location.y)},${fmt(location.z)}"

    private fun fmt(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private fun short(uuid: UUID): String = uuid.toString().take(8)

    private companion object {
        const val CMI_CHAIR_NAME = "CMIArmorStandForSit"
        const val WAITER_HITBOX_TAG = "arc_origin_dining_waiter_hitbox"
        const val SEAT_TAG = "arc_origin_dining_seat"
        const val MEAL_TAG = "arc_origin_dining_meal"
        const val GUEST_MEAL_TAG = "arc_origin_dining_guest_meal"
        const val GUEST_MARKER_TAG = "arc_origin_dining_guest_marker"
        const val SPEECH_TAG = "arc_origin_dining_speech"
        const val WAITER_BUSY_TAG = "arc_origin_dining_waiter_busy"
        val LEGACY_DISH_MODEL_DATA = setOf(11890, 11891, 11893)
    }
}
