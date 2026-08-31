package ru.arc.origin

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.zauction.AuctionShowcaseListing
import ru.arc.hooks.zauction.AuctionShowcaseOpenResult
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.Locale
import java.util.UUID
import kotlin.math.PI

internal object AuctionShowcasePlanner {
    fun select(
        listings: List<AuctionShowcaseListing>,
        slotCount: Int,
        offset: Int,
    ): List<AuctionShowcaseListing?> {
        require(slotCount > 0) { "slotCount must be positive" }
        val distinct = listings.distinctBy(AuctionShowcaseListing::id)
        if (distinct.isEmpty()) return List(slotCount) { null }
        val start = Math.floorMod(offset, distinct.size)
        return List(slotCount) { slot -> distinct[(start + slot) % distinct.size] }
    }
}

internal class AuctionShowcaseManager {
    private data class Pedestal(
        val base: BlockDisplay,
        val item: ItemDisplay,
        val text: TextDisplay,
        val interaction: Interaction,
        var listingId: Int? = null,
    )

    private val tasks = LifecycleTaskScope()
    private val pedestals = mutableListOf<Pedestal>()
    private val interactionSlots = mutableMapOf<UUID, Pedestal>()
    private val clickTimes = mutableMapOf<UUID, Long>()

    private var config: OriginSpawnConfig? = null
    private var cycleOffset = 0
    private var rotation = 0f

    fun apply(next: OriginSpawnConfig) {
        config = next
        val token = tasks.restart()
        removeEntities()
        cycleOffset = 0
        rotation = 0f
        if (!next.enabled || !next.showcaseEnabled) return

        val world = world(next.worldName)
        if (world == null) {
            warn("Origin auction showcase is enabled, but world '{}' is unavailable", next.worldName)
            return
        }
        val anchor = next.pedestals.first()
        world.getChunkAtAsync(anchor.x.toInt() shr 4, anchor.z.toInt() shr 4, false)
            .whenCompleteSync(tasks, token) { _, failure ->
                if (failure != null) {
                    warn("Failed to load Origin auction showcase chunk", failure)
                    return@whenCompleteSync
                }
                cleanupTaggedEntities(world, next)
                next.pedestals.forEach { spec -> pedestals += spawnPedestal(world, spec) }
                refresh(advance = false)
                tasks.runTimer(token, next.cycleTicks, next.cycleTicks) { refresh(advance = true) }
                tasks.runTimer(token, next.rotationTicks, next.rotationTicks) { rotate(next.rotationTicks) }
                info("Origin auction showcase ready: world={}, pedestals={}", next.worldName, pedestals.size)
            }
    }

    fun handle(event: PlayerInteractEntityEvent): Boolean {
        if (event.hand != EquipmentSlot.HAND) return false
        val pedestal = interactionSlots[event.rightClicked.uniqueId] ?: return false
        event.isCancelled = true
        val player = event.player
        val now = System.currentTimeMillis()
        val debounce = config?.clickDebounceMillis ?: 500L
        val previous = clickTimes.put(player.uniqueId, now)
        if (previous != null && now - previous < debounce) return true

        val listingId = pedestal.listingId ?: return true
        val hook = HookRegistry.auctionHook
        if (hook == null) {
            player.sendActionBar(config?.message("unavailable") ?: Component.empty())
            return true
        }
        hook.openShowcaseListing(player, listingId) { result ->
            when (result) {
                AuctionShowcaseOpenResult.ConfirmationOpened,
                AuctionShowcaseOpenResult.InsufficientFunds,
                AuctionShowcaseOpenResult.Busy,
                -> Unit
                AuctionShowcaseOpenResult.Stale -> {
                    if (player.isOnline) player.sendActionBar(config?.message("stale") ?: Component.empty())
                    refresh(advance = false)
                }
                AuctionShowcaseOpenResult.Unavailable ->
                    if (player.isOnline) player.sendActionBar(config?.message("unavailable") ?: Component.empty())
                AuctionShowcaseOpenResult.Failed ->
                    if (player.isOnline) player.sendActionBar(config?.message("failed") ?: Component.empty())
            }
        }
        return true
    }

    fun forget(player: Player) {
        clickTimes.remove(player.uniqueId)
    }

    fun shutdown() {
        tasks.close()
        removeEntities()
        config = null
    }

    private fun refresh(advance: Boolean) {
        if (pedestals.isEmpty()) return
        val listings = HookRegistry.auctionHook?.showcaseListings().orEmpty()
        if (advance && listings.isNotEmpty()) cycleOffset = (cycleOffset + 1) % listings.size
        val selected = AuctionShowcasePlanner.select(listings, pedestals.size, cycleOffset)
        pedestals.zip(selected).forEachIndexed { index, (pedestal, listing) ->
            render(pedestal, listing, emptyLabel = index == 0 && listings.isEmpty())
        }
    }

    private fun render(
        pedestal: Pedestal,
        listing: AuctionShowcaseListing?,
        emptyLabel: Boolean,
    ) {
        val current = config ?: return
        pedestal.listingId = listing?.id
        pedestal.interaction.isResponsive = listing != null
        pedestal.item.setItemStack(listing?.item?.clone() ?: ItemStack(Material.AIR))
        pedestal.text.text(
            when {
                listing != null -> current.listingText(listing.itemName, listing.sellerName, listing.price)
                emptyLabel -> current.emptyText()
                else -> Component.empty()
            },
        )
    }

    private fun rotate(periodTicks: Long) {
        rotation = (rotation + (2.0 * PI * periodTicks / 240.0).toFloat()) % (2f * PI.toFloat())
        pedestals.forEach { pedestal ->
            if (pedestal.listingId == null || !pedestal.item.isValid) return@forEach
            pedestal.item.transformation =
                Transformation(
                    Vector3f(),
                    AxisAngle4f(rotation, 0f, 1f, 0f),
                    Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE),
                    AxisAngle4f(),
                )
        }
    }

    private fun spawnPedestal(world: org.bukkit.World, spec: AuctionPedestalSpec): Pedestal {
        val floor = Location(world, spec.x, spec.y, spec.z, spec.yaw, 0f)
        val base = world.spawn(floor, BlockDisplay::class.java)
        configureDisplay(base)
        base.block = Material.POLISHED_DEEPSLATE.createBlockData()
        base.transformation =
            Transformation(
                Vector3f(-0.45f, 0f, -0.45f),
                AxisAngle4f(),
                Vector3f(0.9f, 0.24f, 0.9f),
                AxisAngle4f(),
            )

        val item = world.spawn(floor.clone().add(0.0, 1.22, 0.0), ItemDisplay::class.java)
        configureDisplay(item)
        item.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
        item.interpolationDelay = 0
        item.interpolationDuration = (config?.rotationTicks ?: 2L).toInt()
        item.transformation =
            Transformation(Vector3f(), AxisAngle4f(), Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE), AxisAngle4f())

        val text = world.spawn(floor.clone().add(0.0, 2.52, 0.0), TextDisplay::class.java)
        configureDisplay(text)
        text.billboard = Display.Billboard.CENTER
        text.alignment = TextDisplay.TextAlignment.CENTER
        text.lineWidth = 220
        text.backgroundColor = Color.fromARGB(96, 0, 0, 0)
        text.isShadowed = true
        text.isSeeThrough = false

        val interaction = world.spawn(floor.clone().add(0.0, 0.08, 0.0), Interaction::class.java)
        interaction.interactionWidth = 1.55f
        interaction.interactionHeight = 2.95f
        interaction.isResponsive = false
        interaction.isPersistent = false
        interaction.isInvulnerable = true

        listOf(base, item, text, interaction).forEach { it.addScoreboardTag(ENTITY_TAG) }
        return Pedestal(base, item, text, interaction).also {
            interactionSlots[interaction.uniqueId] = it
        }
    }

    private fun configureDisplay(display: Display) {
        display.billboard = Display.Billboard.FIXED
        display.brightness = Display.Brightness(12, 15)
        display.shadowRadius = 0f
        display.shadowStrength = 0f
        display.viewRange = 1.5f
        display.displayWidth = 2f
        display.displayHeight = 3.2f
        display.isPersistent = false
        display.setGravity(false)
        display.isInvulnerable = true
    }

    private fun cleanupTaggedEntities(world: org.bukkit.World, current: OriginSpawnConfig) {
        current.pedestals
            .map { (it.x.toInt() shr 4) to (it.z.toInt() shr 4) }
            .distinct()
            .forEach { (x, z) ->
                if (!world.isChunkLoaded(x, z)) return@forEach
                world.getChunkAt(x, z).entities
                    .filter { ENTITY_TAG in it.scoreboardTags }
                    .forEach(Entity::remove)
            }
    }

    private fun removeEntities() {
        pedestals.forEach { pedestal ->
            listOf(pedestal.base, pedestal.item, pedestal.text, pedestal.interaction)
                .filter(Entity::isValid)
                .forEach(Entity::remove)
        }
        pedestals.clear()
        interactionSlots.clear()
        clickTimes.clear()
    }

    private fun world(name: String): org.bukkit.World? =
        Bukkit.getWorld(name)
            ?: Bukkit.getWorlds().firstOrNull { it.name.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) }

    private companion object {
        const val ENTITY_TAG = "arc_origin_auction_showcase"
        const val ITEM_SCALE = 1.25f
    }
}
