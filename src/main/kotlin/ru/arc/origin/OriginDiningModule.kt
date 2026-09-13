package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
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
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
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
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.core.modules.EconomyModule
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.worldcontent.BreweryTableDialogs
import java.util.UUID
import kotlin.math.min

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
        service?.restart()
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
    const val MODEL_HEAD_TRANSLATION_PIXELS = -35.25
    const val MODEL_HEAD_TRANSLATION_BLOCKS = MODEL_HEAD_TRANSLATION_PIXELS / 16.0
    const val DISPLAY_LIFT_BLOCKS = 2.25
    const val MEAL_HITBOX_SIZE = 1.8f

    val seats =
        listOf(
            OriginDiningSeat("brewery_south_west", BreweryTableDialogs.Menu.FOOD, OriginDiningPoint(-7.5, 70.0, 37.5, -90f), OriginDiningPoint(-6.55, 71.1, 37.9), OriginDiningPoint(-7.5, 70.0, 39.5), 410, Triple(-8, 70, 37)),
            OriginDiningSeat("brewery_south_east", BreweryTableDialogs.Menu.FOOD, OriginDiningPoint(-3.5, 70.0, 37.5, 90f), OriginDiningPoint(-4.45, 71.1, 37.9), OriginDiningPoint(-3.5, 70.0, 39.5), 410, Triple(-4, 70, 37)),
            OriginDiningSeat("brewery_fire", BreweryTableDialogs.Menu.DRINKS, OriginDiningPoint(-8.5, 70.0, 48.5, 180f), OriginDiningPoint(-8.5, 70.75, 47.2), OriginDiningPoint(-6.5, 70.0, 48.5), 411, Triple(-9, 70, 48)),
            OriginDiningSeat("brewery_west", BreweryTableDialogs.Menu.COURTYARD, OriginDiningPoint(-13.5, 70.0, 53.5, 90f), OriginDiningPoint(-14.8, 71.1, 53.5), OriginDiningPoint(-13.5, 70.0, 55.5), 411, Triple(-14, 70, 53)),
            OriginDiningSeat("brewery_east", BreweryTableDialogs.Menu.COURTYARD, OriginDiningPoint(-3.5, 70.0, 52.5, 0f), OriginDiningPoint(-3.2, 71.1, 53.5), OriginDiningPoint(-1.5, 70.0, 52.5), 411, Triple(-4, 70, 52)),
            OriginDiningSeat("restaurant_a", BreweryTableDialogs.Menu.RESTAURANT, OriginDiningPoint(-54.5, 72.0, 48.5, 180f), OriginDiningPoint(-54.5, 73.1, 47.2), OriginDiningPoint(-52.5, 72.0, 46.5), 431, Triple(-55, 72, 48)),
            OriginDiningSeat("restaurant_b", BreweryTableDialogs.Menu.RESTAURANT, OriginDiningPoint(-47.5, 72.0, 52.5, 180f), OriginDiningPoint(-47.5, 73.1, 51.2), OriginDiningPoint(-50.5, 72.0, 50.5), 432, Triple(-48, 72, 52)),
        )

    fun visibleModelY(anchorY: Double): Double = anchorY + DISPLAY_LIFT_BLOCKS + MODEL_HEAD_TRANSLATION_BLOCKS

    fun seatForBlock(x: Int, y: Int, z: Int): OriginDiningSeat? = seats.firstOrNull { it.clickedBlock == Triple(x, y, z) }

    fun itemId(dishId: String): String? =
        when (dishId) {
            "egg" -> "elitecreatures:restaurant_food_eggbeacon"
            "fish" -> "elitecreatures:restaurant_food_halffish"
            "steak" -> "elitecreatures:restaurant_food_steak"
            "herbal_tea", "berry_kvass", "spiced_mead" -> "elitecreatures:restaurant_drink"
            else -> null
        }

    fun displayScale(dishId: String): Float =
        when (dishId) {
            "steak" -> 2.6f
            "egg", "herbal_tea", "berry_kvass", "spiced_mead" -> 1.3f
            else -> 0.65f
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
    private val theftCooldownUntil = mutableMapOf<UUID, Long>()

    fun start() {
        spawnSeats()
        tasks.runTimer(20L, 20L, ::reconcile)
        info("ORIGIN_DINING phase=READY world={} seats={} placement=arc-fixed", OriginDiningLayout.WORLD, seatsById.size)
    }

    fun restart() {
        tasks.restart()
        removeRuntimeEntities()
        sessions.clear()
        occupants.clear()
        meals.clear()
        mealEntity.clear()
        seatEntity.clear()
        seatHitboxes.clear()
        spawnSeats()
        tasks.runTimer(20L, 20L, ::reconcile)
        info("ORIGIN_DINING phase=RELOADED world={} seats={}", OriginDiningLayout.WORLD, seatsById.size)
    }

    fun canOpen(player: Player, menu: BreweryTableDialogs.Menu): Boolean {
        val session = sessions[player.uniqueId]
        val allowed = session != null && session.seat.menu == menu && nearVenue(player, session.seat)
        if (!allowed) {
            player.sendActionBar(Component.text("Сначала сядьте за свободное место.", NamedTextColor.GRAY))
            logWarn("DIALOG_REJECTED", session, player, null, "no-active-seat")
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
        session.phase = OriginDiningPhase.ORDERED
        session.touchedAt = System.currentTimeMillis()
        log("ORDER_ACCEPTED", session, player, dish, session.seat.dish, null)
        summonWaiter(session, player)
        tasks.runLater(DELIVERY_TICKS) { deliver(session.id, dish) }
        log("DELIVERY_SCHEDULED", session, player, dish, session.seat.dish, "delayTicks=$DELIVERY_TICKS")
        player.sendActionBar(Component.text("Заказ принят. Официант уже идёт.", NamedTextColor.GOLD))
        return true
    }

    fun interact(player: Player, entity: Entity, source: String): Boolean {
        if (entity is Interaction && entity.world.name == OriginDiningLayout.WORLD) {
            info(
                "ORIGIN_DINING phase=INPUT_ENTITY player={} source={} entity={} entity_id={} actual={}",
                player.name,
                source,
                short(entity.uniqueId),
                entity.entityId,
                location(entity.location),
            )
        }
        seatEntity[entity.uniqueId]?.let { seat ->
            sit(player, seat, "$source:arc-seat:${short(entity.uniqueId)}@${location(entity.location)}")
            return true
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
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            runCatching { CitizensAPI.getNPCRegistry().getNPC(entity) }.getOrNull()?.let { npc ->
                if (interactWaiter(player, npc.id, "$source:citizens-entity")) return true
            }
        }
        // During migration, capture clicks on exact Denizen seat/meal hitboxes too.
        nearestSeat(entity.location, 0.8)?.let { seat ->
            if (entity is Interaction) {
                sit(player, seat, "$source:legacy-seat:${short(entity.uniqueId)}@${location(entity.location)}")
                return true
            }
        }
        if (entity is Interaction && entity.world.name == OriginDiningLayout.WORLD) {
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
        if (npcId != session.seat.waiterId || !nearVenue(player, session.seat)) {
            warn(
                "ORIGIN_DINING phase=WAITER_INPUT_REJECTED player={} source={} npc={} table={} expected_npc={} reason=waiter-or-venue-mismatch",
                player.name,
                source,
                npcId,
                session.seat.id,
                session.seat.waiterId,
            )
            return false
        }
        if (debounce(player)) {
            info("ORIGIN_DINING phase=INPUT_DEBOUNCED player={} table={} source={}", player.name, session.seat.id, source)
            return true
        }
        session.touchedAt = System.currentTimeMillis()
        openDialog(player, session, "waiter:$npcId")
        return true
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
        cleanupLegacy(world)
        for (seat in seatsById.values) {
            val hitbox = world.spawn(seat.seat.inWorld(world), Interaction::class.java)
            hitbox.interactionWidth = 1.45f
            hitbox.interactionHeight = 1.8f
            hitbox.isResponsive = true
            hitbox.isPersistent = false
            hitbox.isInvulnerable = true
            hitbox.addScoreboardTag(SEAT_TAG)
            seatHitboxes[seat.id] = hitbox
            seatEntity[hitbox.uniqueId] = seat
            info("ORIGIN_DINING phase=SEAT_SPAWNED table={} hitbox={} actual={}", seat.id, short(hitbox.uniqueId), location(hitbox.location))
        }
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
            tasks.runLater(1L) { if (player.isOnline) openDialog(player, existing, "reseat") }
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
        summonWaiter(session, player)
        tasks.runLater(1L) {
            if (player.isOnline && sessions[player.uniqueId]?.id == session.id) openDialog(player, session, "seat")
        }
    }

    private fun openDialog(player: Player, session: OriginDiningSession, source: String) {
        log("DIALOG_REQUESTED", session, player, null, session.seat.seat, "source=$source menu=${session.seat.menu.id}")
        runCatching { BreweryTableDialogs.openOrder(player, session.seat.menu) }
            .onSuccess { log("DIALOG_DISPATCHED", session, player, null, session.seat.seat, "source=$source menu=${session.seat.menu.id}") }
            .onFailure { failure -> logWarn("DIALOG_FAILED", session, player, null, failure.message ?: failure.javaClass.simpleName) }
    }

    private fun spawnMarker(seat: OriginDiningSeat): ArmorStand {
        val world = requireNotNull(Bukkit.getWorld(OriginDiningLayout.WORLD))
        return world.spawn(seat.seat.inWorld(world).add(0.0, 0.58, 0.0), ArmorStand::class.java).apply {
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
        if (player.isInsideVehicle) player.leaveVehicle()
        if (!session.marker.addPassenger(player)) {
            tasks.runLater(1L) {
                if (player.isOnline && sessions[player.uniqueId]?.id == session.id) {
                    session.marker.addPassenger(player)
                    log("MOUNT_RETRY", session, player, null, session.seat.seat, "mounted=${player.vehicle?.uniqueId == session.marker.uniqueId}")
                }
            }
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
            "display=${short(created.display.uniqueId)}@${location(created.display.location)} hitbox=${short(created.hitbox.uniqueId)}@${location(created.hitbox.location)}",
        )
        val economy = EconomyModule.getEconomy()
        val payment = economy?.withdrawPlayer(player, dish.price.toDouble())
        if (payment?.transactionSuccess() != true) {
            created.display.remove()
            created.hitbox.remove()
            session.phase = OriginDiningPhase.SEATED
            logWarn("DELIVERY_FAILED", session, player, dish, "payment-rejected-no-charge")
            player.sendActionBar(Component.text("Оплата не прошла. Заказ не списан.", NamedTextColor.RED))
            return
        }
        log("PAYMENT_CAPTURED", session, player, dish, session.seat.dish, "amount=${dish.price}")
        meals.remove(session.seat.id)?.let(::removeMeal)
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
            "display=${short(created.display.uniqueId)}@${location(created.display.location)} hitbox=${short(created.hitbox.uniqueId)}@${location(created.hitbox.location)} visibleY=${fmt(OriginDiningLayout.visibleModelY(created.display.location.y))} scale=${OriginDiningLayout.displayScale(dish.id)} price=${dish.price}",
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
        try {
            display.setItemStack(stack)
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.HEAD
            display.billboard = Display.Billboard.FIXED
            display.brightness = Display.Brightness(15, 15)
            display.shadowRadius = 0f
            display.shadowStrength = 0f
            display.viewRange = 2f
            display.displayWidth = 4f
            display.displayHeight = 4f
            display.isPersistent = false
            display.setGravity(false)
            display.isInvulnerable = true
            display.isGlowing = false
            display.glowColorOverride = Color.fromRGB(0x92, 0xBE, 0xD8)
            display.transformation =
                Transformation(
                    Vector3f(0f, OriginDiningLayout.DISPLAY_LIFT_BLOCKS.toFloat(), 0f),
                    AxisAngle4f(),
                    Vector3f(OriginDiningLayout.displayScale(dish.id), OriginDiningLayout.displayScale(dish.id), OriginDiningLayout.displayScale(dish.id)),
                    AxisAngle4f(),
                )
            display.addScoreboardTag(MEAL_TAG)

            val hitbox = world.spawn(anchor.clone().add(0.0, -0.15, 0.0), Interaction::class.java)
            hitbox.interactionWidth = OriginDiningLayout.MEAL_HITBOX_SIZE
            hitbox.interactionHeight = OriginDiningLayout.MEAL_HITBOX_SIZE
            hitbox.isResponsive = true
            hitbox.isPersistent = false
            hitbox.isInvulnerable = true
            hitbox.addScoreboardTag(MEAL_TAG)
            return OriginDiningMeal(session.id, session.playerId, session.seat, dish, display, hitbox)
        } catch (failure: Throwable) {
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
            removeMeal(meal)
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
        removeMeal(meal)
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

    private fun removeMeal(meal: OriginDiningMeal) {
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
        runCatching {
            val npc = CitizensAPI.getNPCRegistry().getById(session.seat.waiterId) ?: return
            if (!npc.isSpawned || npc.entity.world.name != OriginDiningLayout.WORLD) return
            npc.entity.addScoreboardTag(WAITER_BUSY_TAG)
            val stop = session.seat.waiterStop.inWorld(npc.entity.world)
            val navigator = npc.navigator
            navigator.cancelNavigation()
            navigator.setTarget(stop)
            navigator.localParameters.distanceMargin(0.7).pathDistanceMargin(1.0).speedModifier(0.72f)
            log("WAITER_APPROACH", session, player, null, session.seat.waiterStop, "npc=${session.seat.waiterId}")
            tasks.runLater(80L) {
                if (sessions[player.uniqueId]?.id == session.id && npc.isSpawned) {
                    if (npc.navigator.isNavigating) npc.navigator.cancelNavigation()
                    npc.faceLocation(player.eyeLocation)
                    log("WAITER_READY", session, player, null, session.seat.waiterStop, "npc=${session.seat.waiterId} actual=${location(npc.entity.location)}")
                }
            }
        }.onFailure { failure ->
            warn(
                "ORIGIN_DINING phase=WAITER_FAILED session={} player={} table={} npc={} reason={}",
                short(session.id), player.name, session.seat.id, session.seat.waiterId, failure.message ?: failure.javaClass.simpleName,
            )
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
        }
        meals.values.toList().forEach { meal ->
            if (!meal.display.isValid || !meal.hitbox.isValid) {
                warn(
                    "ORIGIN_DINING phase=MEAL_LOST session={} player={} table={} dish={} expected={} display_valid={} hitbox_valid={}",
                    short(meal.sessionId), meal.ownerId, meal.seat.id, meal.dish.id, point(meal.seat.dish), meal.display.isValid, meal.hitbox.isValid,
                )
                removeMeal(meal)
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

    private fun releaseSession(playerId: UUID, reason: String) {
        val session = sessions.remove(playerId) ?: return
        occupants.remove(session.seat.id, playerId)
        if (session.marker.isValid) session.marker.remove()
        val player = Bukkit.getPlayer(playerId)
        releaseWaiter(session.seat.waiterId)
        log("SEAT_RELEASED", session, player, null, session.seat.seat, "reason=$reason")
    }

    private fun releaseWaiter(waiterId: Int, force: Boolean = false) {
        if (!force && sessions.values.any { it.seat.waiterId == waiterId }) return
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        runCatching { CitizensAPI.getNPCRegistry().getById(waiterId) }.getOrNull()?.takeIf { it.isSpawned }?.let { npc ->
            if (force && npc.navigator.isNavigating) npc.navigator.cancelNavigation()
            npc.entity.removeScoreboardTag(WAITER_BUSY_TAG)
        }
    }

    private fun cleanupLegacy(world: org.bukkit.World) {
        // The two-seat legacy controller is fully retired and cannot clean up
        // its own persisted entities. The other Denizen scenes keep their
        // flag-aware cleanup tasks, so ARC deliberately does not scan their
        // furniture areas or remove unrelated Interaction entities there.
        val anchors =
            OriginDiningLayout.seats.take(2).map { it.seat } +
                listOf(OriginDiningPoint(-5.5, 70.95, 37.9))
        var removed = 0
        for (anchor in anchors) {
            val point = anchor.inWorld(world)
            for (entity in world.getNearbyEntities(point, 0.35, 0.45, 0.35)) {
                if (entity.scoreboardTags.contains(SEAT_TAG) || entity.scoreboardTags.contains(MEAL_TAG)) continue
                val legacyInteraction =
                    entity is Interaction &&
                        ((entity.interactionWidth <= 0.95f && entity.interactionHeight == 1.4f) ||
                            (entity.interactionWidth == 1.8f && entity.interactionHeight == 1.8f))
                if (legacyInteraction || entity is ArmorStand && entity.isMarker && !entity.isVisible) {
                    entity.remove()
                    removed++
                }
            }
        }
        val oldLabel = Location(world, -5.5, 72.35, 37.5)
        world.getNearbyEntities(oldLabel, 0.6, 0.6, 0.6).filterIsInstance<TextDisplay>().forEach {
            it.remove()
            removed++
        }
        info("ORIGIN_DINING phase=LEGACY_CLEANUP removed={}", removed)
    }

    private fun removeRuntimeEntities() {
        seatHitboxes.values.forEach { if (it.isValid) it.remove() }
        sessions.values.forEach { if (it.marker.isValid) it.marker.remove() }
        meals.values.forEach(::removeMeal)
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
        theftCooldownUntil.clear()
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
            "ORIGIN_DINING phase={} session={} player={} table={} dish={} target={} actual_player={} detail={}",
            phase,
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
        const val DELIVERY_TICKS = 100L
        const val THEFT_COOLDOWN_MILLIS = 90_000L
        const val SESSION_TTL_MILLIS = 300_000L
        const val SESSION_RADIUS = 8.0
    }
}
