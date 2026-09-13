package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
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
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.core.modules.EconomyModule
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.worldcontent.BreweryTableDialogs
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Owns the reliable player-facing restaurant path in Origin.
 *
 * Denizen still owns ambient NPC scenes. ARC owns seat capture, native dialogs,
 * payment, fixed table anchors, edible displays and every recovery transition.
 */
object OriginDiningModule : PluginModule, Listener {
    override val name = "OriginDining"
    override val priority = 26

    private var service: OriginDiningService? = null
    private var citizensListener: OriginDiningCitizensListener? = null

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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        service?.interactBlock(event.player, block.location)?.let { handled ->
            if (handled) event.isCancelled = true
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

internal data class OriginDiningSeat(
    val id: String,
    val menu: BreweryTableDialogs.Menu,
    val seat: OriginDiningPoint,
    val dish: OriginDiningPoint,
    val waiterStop: OriginDiningPoint,
    val waiterId: Int,
    val clickedBlock: Triple<Int, Int, Int>,
)

internal object OriginDiningLayout {
    const val WORLD = "rc_origin_spawn"
    private const val DEFAULT_FORWARD_BLOCKS = 1.0
    private const val DEFAULT_VERTICAL_OFFSET_BLOCKS = 0.0

    private data class SeatTemplate(
        val id: String,
        val menu: BreweryTableDialogs.Menu,
        val seat: OriginDiningPoint,
        val surfaceHeight: Double,
        val waiterStop: OriginDiningPoint,
        val waiterId: Int,
        val clickedBlock: Triple<Int, Int, Int>,
    )

    private val templates =
        listOf(
            SeatTemplate("brewery_south_west", BreweryTableDialogs.Menu.FOOD, OriginDiningPoint(-7.5, 70.0, 37.5, -90f), 1.1, OriginDiningPoint(-7.5, 70.0, 38.5), 410, Triple(-8, 70, 37)),
            SeatTemplate("brewery_south_east", BreweryTableDialogs.Menu.FOOD, OriginDiningPoint(-3.5, 70.0, 37.5, 90f), 1.1, OriginDiningPoint(-3.5, 70.0, 38.5), 410, Triple(-4, 70, 37)),
            SeatTemplate("brewery_fire", BreweryTableDialogs.Menu.DRINKS, OriginDiningPoint(-8.5, 70.0, 48.5, 180f), 0.75, OriginDiningPoint(-6.5, 70.0, 48.5), 411, Triple(-9, 70, 48)),
            SeatTemplate("brewery_rina", BreweryTableDialogs.Menu.COURTYARD, OriginDiningPoint(-13.5, 70.0, 47.5, 90f), 1.1, OriginDiningPoint(-13.5, 70.0, 45.5), 410, Triple(-14, 70, 47)),
            SeatTemplate("brewery_west", BreweryTableDialogs.Menu.COURTYARD, OriginDiningPoint(-13.5, 70.0, 53.5, 90f), 1.1, OriginDiningPoint(-13.5, 70.0, 55.5), 411, Triple(-14, 70, 53)),
            SeatTemplate("brewery_east", BreweryTableDialogs.Menu.COURTYARD, OriginDiningPoint(-3.5, 70.0, 52.5, 0f), 1.1, OriginDiningPoint(-1.5, 70.0, 52.5), 411, Triple(-4, 70, 52)),
            SeatTemplate("restaurant_a", BreweryTableDialogs.Menu.RESTAURANT, OriginDiningPoint(-54.5, 72.0, 48.5, 180f), 1.1, OriginDiningPoint(-52.5, 72.0, 46.5), 431, Triple(-55, 72, 48)),
            SeatTemplate("restaurant_b", BreweryTableDialogs.Menu.RESTAURANT, OriginDiningPoint(-47.5, 72.0, 52.5, 180f), 1.1, OriginDiningPoint(-50.5, 72.0, 50.5), 432, Triple(-48, 72, 52)),
        )

    var seats: List<OriginDiningSeat> = buildSeats()
        private set
    var mealHitboxSize = 1.8f
        private set
    var mealHitboxYOffset = -0.15
        private set
    var seatHitboxWidth = 1.45f
        private set
    var seatHitboxHeight = 1.8f
        private set
    var displayViewRange = 2f
        private set
    var displayWidth = 4f
        private set
    var displayHeight = 4f
        private set
    private var scales = defaultScales()
    private var surfaceLifts = defaultSurfaceLifts()

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
        seatHitboxWidth = source.real("interaction.seat-hitbox-width", 1.45).toFloat().coerceIn(0.5f, 2.5f)
        seatHitboxHeight = source.real("interaction.seat-hitbox-height", 1.8).toFloat().coerceIn(0.5f, 3f)
        displayViewRange = source.real("display.view-range", 2.0).toFloat().coerceIn(0.5f, 16f)
        displayWidth = source.real("display.culling-width", 4.0).toFloat().coerceIn(0.5f, 16f)
        displayHeight = source.real("display.culling-height", 4.0).toFloat().coerceIn(0.5f, 16f)
        seats = buildSeats(source, forward, vertical)
        info(
            "ORIGIN_DINING phase=CONFIG_LOADED forward_blocks={} vertical_offset={} transform=GROUND meal_hitbox={} seats={}",
            forward,
            vertical,
            mealHitboxSize,
            seats.size,
        )
    }

    private fun buildSeats(
        source: ru.arc.config.Config? = null,
        forward: Double = DEFAULT_FORWARD_BLOCKS,
        vertical: Double = DEFAULT_VERTICAL_OFFSET_BLOCKS,
    ): List<OriginDiningSeat> =
        templates.map { template ->
            val prefix = "placement.tables.${template.id}"
            val height = source?.real("$prefix.height-above-seat", template.surfaceHeight) ?: template.surfaceHeight
            val offsetX = source?.real("$prefix.offset-x", 0.0) ?: 0.0
            val offsetY = source?.real("$prefix.offset-y", 0.0) ?: 0.0
            val offsetZ = source?.real("$prefix.offset-z", 0.0) ?: 0.0
            OriginDiningSeat(
                template.id,
                template.menu,
                template.seat,
                centeredDishPoint(template.seat, forward, height + vertical + offsetY, offsetX, offsetZ),
                template.waiterStop,
                template.waiterId,
                template.clickedBlock,
            )
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

    fun seatForBlock(x: Int, y: Int, z: Int): OriginDiningSeat? = seats.firstOrNull { it.clickedBlock == Triple(x, y, z) }

    fun itemId(dishId: String): String? =
        when (dishId) {
            "egg" -> "elitecreatures:restaurant_food_eggbeacon"
            "fish" -> "elitecreatures:restaurant_food_halffish"
            "steak" -> "elitecreatures:restaurant_food_steak"
            "herbal_tea", "berry_kvass", "spiced_mead" -> "elitecreatures:restaurant_drink"
            else -> null
        }

    val waiterIds: Set<Int> get() = seats.map(OriginDiningSeat::waiterId).toSet()

    fun waiterHome(waiterId: Int): OriginDiningPoint? =
        when (waiterId) {
            410 -> OriginDiningPoint(1.5, 70.0, 57.5, 180f)
            411 -> OriginDiningPoint(-1.5, 70.0, 60.5, 180f)
            431 -> OriginDiningPoint(-51.0, 72.0, 54.0, 180f)
            432 -> OriginDiningPoint(-47.0, 72.0, 54.0, 180f)
            else -> null
        }

    fun waiterServes(seat: OriginDiningSeat, waiterId: Int): Boolean =
        if (seat.id.startsWith("brewery_")) waiterId == 410 || waiterId == 411
        else waiterId == 431 || waiterId == 432

    fun displayScale(dishId: String): Float = scales[dishId] ?: 0.65f

    private fun defaultScales() =
        mapOf(
            "egg" to 0.65f,
            "fish" to 0.65f,
            "steak" to 1.3f,
            "herbal_tea" to 0.65f,
            "berry_kvass" to 0.65f,
            "spiced_mead" to 0.65f,
        )

    private fun defaultSurfaceLifts() =
        mapOf(
            "egg" to 0.12125f,
            "fish" to 0.12125f,
            "steak" to 0.2025f,
            "herbal_tea" to 0.0205f,
            "berry_kvass" to 0.0205f,
            "spiced_mead" to 0.0205f,
        )
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
    val marker: ArmorStand,
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

private class OriginDiningService : AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val seatsById = OriginDiningLayout.seats.associateBy(OriginDiningSeat::id)
    private val seatEntity = mutableMapOf<UUID, OriginDiningSeat>()
    private val seatHitboxes = mutableMapOf<String, Interaction>()
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

    fun start() {
        info(
            "ORIGIN_DINING phase=STARTING world={} seats={} waiter_ids={} route_timeout_ticks={} session_ttl_ms={}",
            OriginDiningLayout.WORLD,
            seatsById.size,
            OriginDiningLayout.waiterIds.sorted().joinToString(","),
            DELIVERY_ROUTE_MAX_POLLS * WAITER_POLL_TICKS,
            SESSION_TTL_MILLIS,
        )
        spawnSeats()
        tasks.runLater(20L) { resetIdleWaiters("service-start") }
        tasks.runTimer(20L, 20L, ::reconcile)
        info("ORIGIN_DINING phase=READY world={} seats={} placement=arc-fixed", OriginDiningLayout.WORLD, seatsById.size)
    }

    fun canOpen(player: Player, menu: BreweryTableDialogs.Menu): Boolean {
        val session = sessions[player.uniqueId]
        val allowed = session != null && session.seat.menu == menu && nearVenue(player, session.seat)
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
        if (session.seat.menu != menu || !nearVenue(player, session.seat)) {
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
        if (dishItem(dish) == null) {
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
        val managedInteraction =
            entity is Interaction &&
                entity.world.name == OriginDiningLayout.WORLD &&
                (entity.uniqueId in seatEntity || entity.uniqueId in mealEntity || nearestSeat(entity.location, 0.8) != null)
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
        seatEntity[entity.uniqueId]?.let { seat ->
            sit(player, seat, "$source:arc-seat:${short(entity.uniqueId)}@${location(entity.location)}")
            return true
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            runCatching { CitizensAPI.getNPCRegistry().getNPC(entity) }.getOrNull()?.let { npc ->
                if (npc.id in OriginDiningLayout.waiterIds && interactWaiter(player, npc.id, "$source:citizens-entity")) return true
            }
        }
        // During migration, capture clicks on exact Denizen seat/meal hitboxes too.
        nearestSeat(entity.location, 0.8)?.let { seat ->
            if (entity is Interaction) {
                sit(player, seat, "$source:legacy-seat:${short(entity.uniqueId)}@${location(entity.location)}")
                return true
            }
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

    fun interactBlock(player: Player, block: Location): Boolean {
        if (block.world?.name != OriginDiningLayout.WORLD) return false
        val seat = OriginDiningLayout.seatForBlock(block.blockX, block.blockY, block.blockZ) ?: return false
        sit(player, seat, "block:${block.blockX},${block.blockY},${block.blockZ}")
        return true
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
        dialogAuthorizations[player.uniqueId] = session.id to (System.currentTimeMillis() + DIALOG_AUTHORIZATION_MILLIS)
        openDialog(player, session, "waiter:$npcId")
        return true
    }

    fun dismounted(player: Player, dismounted: Entity) {
        val session = sessions[player.uniqueId] ?: return
        if (dismounted.uniqueId != session.marker.uniqueId) return
        tasks.runLater(1L) {
            if (!player.isOnline || sessions[player.uniqueId]?.id != session.id) return@runLater
            log(
                "DISMOUNTED",
                session,
                player,
                meals[session.seat.id]?.dish,
                session.seat.seat,
                "marker=${short(dismounted.uniqueId)} retained=true near_venue=${nearVenue(player, session.seat)} meal_present=${session.seat.id in meals}",
            )
        }
        tasks.runLater(WAITER_DISMOUNT_GRACE_TICKS) {
            val current = sessions[player.uniqueId]?.takeIf { it.id == session.id } ?: return@runLater
            if (player.isOnline && player.vehicle?.uniqueId == current.marker.uniqueId) return@runLater
            if (current.phase != OriginDiningPhase.ORDERED) returnWaiterHome(current, "player-dismounted")
        }
    }

    fun quit(player: Player) {
        releaseSession(player.uniqueId, "player-quit")
    }

    private fun spawnSeats() {
        val world = Bukkit.getWorld(OriginDiningLayout.WORLD)
        if (world == null) {
            warn("ORIGIN_DINING phase=START_FAILED world={} reason=world-unavailable", OriginDiningLayout.WORLD)
            return
        }
        cleanupLegacy(world, "before-seat-spawn")
        for (seat in seatsById.values) {
            val hitbox = world.spawn(seat.seat.inWorld(world), Interaction::class.java)
            hitbox.interactionWidth = OriginDiningLayout.seatHitboxWidth
            hitbox.interactionHeight = OriginDiningLayout.seatHitboxHeight
            hitbox.isResponsive = true
            hitbox.isPersistent = false
            hitbox.isInvulnerable = true
            hitbox.addScoreboardTag(SEAT_TAG)
            seatHitboxes[seat.id] = hitbox
            seatEntity[hitbox.uniqueId] = seat
            info(
                "ORIGIN_DINING phase=SEAT_SPAWNED table={} menu={} waiter={} hitbox={} entity_id={} actual={} dish_target={} waiter_stop={} width={} height={}",
                seat.id,
                seat.menu.id,
                seat.waiterId,
                short(hitbox.uniqueId),
                hitbox.entityId,
                location(hitbox.location),
                point(seat.dish),
                point(seat.waiterStop),
                fmt(hitbox.interactionWidth.toDouble()),
                fmt(hitbox.interactionHeight.toDouble()),
            )
        }
        // Spawning the seats loads their chunks. Persisted Denizen entities can
        // therefore become visible only after the first cleanup pass.
        cleanupLegacy(world, "after-seat-spawn")
    }

    private fun sit(player: Player, seat: OriginDiningSeat, source: String) {
        if (player.world.name != OriginDiningLayout.WORLD) return
        if (debounce(player)) {
            info("ORIGIN_DINING phase=INPUT_DEBOUNCED player={} table={} source={}", player.name, seat.id, source)
            return
        }
        val existing = sessions[player.uniqueId]
        if (existing?.seat?.id == seat.id) {
            if (player.vehicle?.uniqueId != existing.marker.uniqueId) mount(player, existing)
            existing.touchedAt = System.currentTimeMillis()
            log("RESEATED", existing, player, null, seat.dish, "source=$source")
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

        val marker = spawnMarker(seat)
        val session = OriginDiningSession(UUID.randomUUID(), player.uniqueId, seat, marker, OriginDiningPhase.SEATED, System.currentTimeMillis())
        sessions[player.uniqueId] = session
        occupants[seat.id] = player.uniqueId
        mount(player, session)
        log("SEATED", session, player, null, seat.dish, "source=$source mounted=${player.vehicle?.uniqueId == marker.uniqueId}")
        player.sendActionBar(Component.text("Официант сейчас подойдёт. Нажмите по нему, чтобы открыть меню.", NamedTextColor.GOLD))
        summonWaiter(session, player)
    }

    private fun openDialog(player: Player, session: OriginDiningSession, source: String) {
        log("DIALOG_REQUESTED", session, player, null, session.seat.seat, "source=$source menu=${session.seat.menu.id}")
        runCatching { BreweryTableDialogs.openOrder(player, session.seat.menu) }
            .onSuccess { log("DIALOG_DISPATCHED", session, player, null, session.seat.seat, "source=$source menu=${session.seat.menu.id}") }
            .onFailure { failure -> logWarn("DIALOG_FAILED", session, player, null, failure.message ?: failure.javaClass.simpleName) }
    }

    private fun spawnMarker(seat: OriginDiningSeat): ArmorStand {
        val world = requireNotNull(Bukkit.getWorld(OriginDiningLayout.WORLD))
        // Marker armor stands render their player passenger 0.6 blocks below
        // the vehicle location. Lift the vehicle so the seated player's feet
        // remain 0.58 blocks above the chair instead of inside its stair block.
        return world.spawn(seat.seat.inWorld(world).add(0.0, SEAT_MARKER_LIFT, 0.0), ArmorStand::class.java).apply {
            isVisible = false
            isMarker = true
            setGravity(false)
            isInvulnerable = true
            isSilent = true
            isPersistent = false
            addScoreboardTag(MARKER_TAG)
        }
    }

    private fun mount(player: Player, session: OriginDiningSession) {
        if (player.vehicle?.uniqueId == session.marker.uniqueId) return
        log(
            "MOUNT_BEGIN",
            session,
            player,
            null,
            session.seat.seat,
            "marker=${short(session.marker.uniqueId)} marker_actual=${location(session.marker.location)} previous_vehicle=${player.vehicle?.uniqueId?.let(::short) ?: "none"}",
        )
        if (player.isInsideVehicle) player.leaveVehicle()
        if (!session.marker.addPassenger(player)) {
            tasks.runLater(1L) {
                if (player.isOnline && sessions[player.uniqueId]?.id == session.id) {
                    session.marker.addPassenger(player)
                    log("MOUNT_RETRY", session, player, null, session.seat.seat, "mounted=${player.vehicle?.uniqueId == session.marker.uniqueId}")
                }
            }
        } else {
            log(
                "MOUNTED",
                session,
                player,
                null,
                session.seat.seat,
                "marker=${short(session.marker.uniqueId)} mounted=${player.vehicle?.uniqueId == session.marker.uniqueId}",
            )
        }
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
            tasks.runLater(DELIVERY_FALLBACK_TICKS) {
                if (activeDeliveries[waiterId] != token) return@runLater
                deliver(currentSession.id, currentDelivery.dish)
                finishDelivery(waiterId, token, currentSession.id, "npc-unavailable")
            }
            return
        }
        npc.entity.addScoreboardTag(WAITER_BUSY_TAG)
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
            tasks.runLater(SERVICE_PAUSE_TICKS) {
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
        val navigator = npc.navigator
        navigator.cancelNavigation()
        navigator.setTarget(destination)
        navigator.localParameters.distanceMargin(0.7).pathDistanceMargin(1.0).speedModifier(0.72f)
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
        tasks.runLater(WAITER_POLL_TICKS) {
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
            if (distance <= WAITER_READY_MARGIN || poll >= DELIVERY_ROUTE_MAX_POLLS) {
                if (npc.navigator.isNavigating) npc.navigator.cancelNavigation()
                log(
                    if (poll >= DELIVERY_ROUTE_MAX_POLLS) "DELIVERY_ROUTE_TIMEOUT" else "DELIVERY_ROUTE_READY",
                    session,
                    Bukkit.getPlayer(session.playerId),
                    dish,
                    session.seat.dish,
                    "npc=$waiterId stage=$stage token=${short(token)} actual=${location(npc.entity.location)} destination=${location(destination)} distance=${fmt(distance)} poll=$poll",
                )
                ready()
                return@runLater
            }
            if (!npc.navigator.isNavigating) {
                npc.navigator.setTarget(destination)
                npc.navigator.localParameters.distanceMargin(0.7).pathDistanceMargin(1.0).speedModifier(0.72f)
            }
            if (poll % WAITER_PROGRESS_EVERY_POLLS == 0) {
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
            tasks.runLater(NEXT_DELIVERY_TICKS) { startNextDelivery(waiterId) }
        } else if (session != null) {
            returnWaiterHome(session, "delivery-$reason")
        } else {
            returnWaiterHome(waiterId, sessionId, null, "delivery-$reason")
        }
    }

    private fun waiter(waiterId: Int): net.citizensnpcs.api.npc.NPC? =
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) null
        else runCatching { CitizensAPI.getNPCRegistry().getById(waiterId) }.getOrNull()

    private fun resetIdleWaiters(reason: String) {
        OriginDiningLayout.waiterIds.forEach { waiterId ->
            if (waiterId in activeDeliveries || waiterId in waiterAssignments) return@forEach
            val npc = waiter(waiterId)?.takeIf { it.isSpawned && it.entity.world.name == OriginDiningLayout.WORLD } ?: return@forEach
            val home = OriginDiningLayout.waiterHome(waiterId)?.inWorld(npc.entity.world) ?: return@forEach
            npc.entity.addScoreboardTag(WAITER_BUSY_TAG)
            npc.navigator.cancelNavigation()
            npc.navigator.setTarget(home)
            npc.navigator.localParameters.distanceMargin(0.7).pathDistanceMargin(1.0).speedModifier(0.72f)
            info(
                "ORIGIN_DINING phase=WAITER_RESET_HOME npc={} reason={} actual={} target={}",
                waiterId,
                reason,
                location(npc.entity.location),
                location(home),
            )
            tasks.runLater(WAITER_RETURN_RELEASE_TICKS) {
                if (waiterId in activeDeliveries || waiterId in waiterAssignments) return@runLater
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
        npc.navigator.cancelNavigation()
        npc.navigator.setTarget(home)
        npc.navigator.localParameters.distanceMargin(0.7).pathDistanceMargin(1.0).speedModifier(0.72f)
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
        tasks.runLater(WAITER_RETURN_RELEASE_TICKS) {
            if (waiterId in activeDeliveries || waiterId in waiterAssignments) return@runLater
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
        if (stolen) theftCooldownUntil[player.uniqueId] = now + THEFT_COOLDOWN_MILLIS

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
            val navigator = npc.navigator
            navigator.cancelNavigation()
            navigator.setTarget(stop)
            navigator.localParameters.distanceMargin(0.7).pathDistanceMargin(1.0).speedModifier(0.72f)
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
        tasks.runLater(WAITER_POLL_TICKS) {
            if (waiterApproaches[waiterId] != approachId) return@runLater
            val session = sessions[playerId]?.takeIf { it.id == sessionId }
            val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)
            if (session == null || player == null) {
                waiterApproaches.remove(waiterId, approachId)
                returnWaiterHome(waiterId, sessionId, session, "approach-session-gone")
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
            if (targetDistance <= WAITER_READY_MARGIN || playerDistance <= WAITER_PLAYER_RANGE) {
                waiterApproaches.remove(waiterId, approachId)
                if (npc.navigator.isNavigating) npc.navigator.cancelNavigation()
                npc.faceLocation(player.eyeLocation)
                waiterReadyFor[waiterId] = session.id
                setWaiterGlow(npc, player, true)
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
            if (poll >= WAITER_MAX_POLLS) {
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
            if (!npc.navigator.isNavigating) {
                npc.navigator.setTarget(stop)
                npc.navigator.localParameters.distanceMargin(0.7).pathDistanceMargin(1.0).speedModifier(0.72f)
                log(
                    "WAITER_RETRY",
                    session,
                    player,
                    null,
                    session.seat.waiterStop,
                    "npc=$waiterId approach=${short(approachId)} actual=${location(actual)} target_distance=${fmt(targetDistance)} poll=$poll",
                )
            } else if (poll % WAITER_PROGRESS_EVERY_POLLS == 0) {
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

    private fun reconcile() {
        val now = System.currentTimeMillis()
        sessions.values.toList().forEach { session ->
            val player = Bukkit.getPlayer(session.playerId)
            if (player == null || !player.isOnline) {
                releaseSession(session.playerId, "offline")
                return@forEach
            }
            if (!nearVenue(player, session.seat) || now - session.touchedAt > SESSION_TTL_MILLIS) {
                releaseSession(session.playerId, "left-table")
                return@forEach
            }
            if (player.vehicle?.uniqueId == session.marker.uniqueId) session.touchedAt = now
            if (
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

    private fun clearWaiterReady(
        waiterId: Int,
        reason: String,
        expectedSessionId: UUID? = null,
    ) {
        val sessionId = waiterReadyFor[waiterId] ?: return
        if (expectedSessionId != null && sessionId != expectedSessionId) return
        if (!waiterReadyFor.remove(waiterId, sessionId)) return
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

    private fun releaseSession(playerId: UUID, reason: String) {
        val session = sessions.remove(playerId) ?: return
        dialogAuthorizations.remove(playerId)
        occupants.remove(session.seat.id, playerId)
        if (session.marker.isValid) session.marker.remove()
        val player = Bukkit.getPlayer(playerId)
        clearWaiterReady(session.seat.waiterId, "session-$reason", session.id)
        returnWaiterHome(session, "session-$reason")
        log("SEAT_RELEASED", session, player, null, session.seat.seat, "reason=$reason")
    }

    private fun releaseWaiter(waiterId: Int, force: Boolean = false) {
        if (!force && (waiterId in activeDeliveries || waiterId in waiterAssignments)) return
        waiterApproaches.remove(waiterId)
        clearWaiterReady(waiterId, "waiter-release")
        if (force) {
            activeDeliveries.remove(waiterId)
            waiterAssignments.remove(waiterId)
            deliveryQueues.remove(waiterId)
        }
        clearWaiterCarry(waiterId)
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        runCatching { CitizensAPI.getNPCRegistry().getById(waiterId) }.getOrNull()?.takeIf { it.isSpawned }?.let { npc ->
            if (force && npc.navigator.isNavigating) npc.navigator.cancelNavigation()
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
                if (entity is Interaction || entity is ArmorStand && entity.isMarker && !entity.isVisible) {
                    entity.remove()
                    removed++
                }
            }
        }
        val oldMealHitbox = OriginDiningPoint(-5.5, 70.95, 37.9).inWorld(world)
        for (entity in world.getNearbyEntities(oldMealHitbox, 0.35, 0.45, 0.35)) {
            if (entity.scoreboardTags.contains(MEAL_TAG)) continue
            if (entity is Interaction) {
                entity.remove()
                removed++
            }
        }
        val oldMealDisplay = OriginDiningPoint(-5.5, 71.1, 37.9).inWorld(world)
        for (entity in world.getNearbyEntities(oldMealDisplay, 0.35, 0.35, 0.35)) {
            if (entity.scoreboardTags.contains(MEAL_TAG)) continue
            if (entity is ItemDisplay) {
                entity.remove()
                removed++
            }
        }
        val oldLabel = Location(world, -5.5, 72.35, 37.5)
        world.getNearbyEntities(oldLabel, 0.6, 0.6, 0.6).filterIsInstance<TextDisplay>().forEach {
            it.remove()
            removed++
        }
        info("ORIGIN_DINING phase=LEGACY_CLEANUP pass={} removed={}", pass, removed)
    }

    private fun removeRuntimeEntities() {
        seatHitboxes.values.forEach { if (it.isValid) it.remove() }
        sessions.values.forEach { if (it.marker.isValid) it.marker.remove() }
        meals.values.forEach { removeMeal(it, "service-stop") }
        OriginDiningLayout.seats.map(OriginDiningSeat::waiterId).distinct().forEach { releaseWaiter(it, force = true) }
    }

    override fun close() {
        tasks.close()
        removeRuntimeEntities()
        sessions.clear()
        occupants.clear()
        meals.clear()
        mealEntity.clear()
        seatEntity.clear()
        seatHitboxes.clear()
        clickAt.clear()
        dialogAuthorizations.clear()
        theftCooldownUntil.clear()
        deliveryQueues.clear()
        activeDeliveries.clear()
        waiterAssignments.clear()
        waiterHeldItems.clear()
        waiterReadyFor.clear()
        info("ORIGIN_DINING phase=STOPPED")
    }

    private fun dishItem(dish: BreweryTableDialogs.Dish): ItemStack? {
        val id = OriginDiningLayout.itemId(dish.id) ?: return null
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null
        return runCatching { CustomStack.getInstance(id)?.itemStack?.clone() }.getOrNull()
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
        return player.location.distanceSquared(seat.seat.inWorld(player.world)) <= SESSION_RADIUS * SESSION_RADIUS
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
        const val SEAT_TAG = "arc_origin_dining_seat"
        const val MARKER_TAG = "arc_origin_dining_marker"
        const val MEAL_TAG = "arc_origin_dining_meal"
        const val WAITER_BUSY_TAG = "arc_origin_dining_waiter_busy"
        const val DELIVERY_FALLBACK_TICKS = 20L
        const val SERVICE_PAUSE_TICKS = 12L
        const val NEXT_DELIVERY_TICKS = 10L
        const val WAITER_POLL_TICKS = 10L
        const val WAITER_MAX_POLLS = 20
        const val DELIVERY_ROUTE_MAX_POLLS = 30
        const val WAITER_DISMOUNT_GRACE_TICKS = 30L
        const val WAITER_RETURN_RELEASE_TICKS = 120L
        const val WAITER_PROGRESS_EVERY_POLLS = 4
        // Citizens may finish beside furniture within roughly 1.3 blocks of
        // the requested path target. Treat that normal navigator tolerance as
        // arrival while retaining the separate player-distance check.
        const val WAITER_READY_MARGIN = 1.5
        const val WAITER_PLAYER_RANGE = 2.8
        const val SEAT_MARKER_LIFT = 1.18
        const val THEFT_COOLDOWN_MILLIS = 90_000L
        const val SESSION_TTL_MILLIS = 300_000L
        const val DIALOG_AUTHORIZATION_MILLIS = 60_000L
        const val SESSION_RADIUS = 8.0
    }
}
