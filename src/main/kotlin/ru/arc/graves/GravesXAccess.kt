package ru.arc.graves

import com.ranull.graves.Graves
import com.ranull.graves.type.Grave
import dev.cwhead.GravesX.api.GravesXAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Narrow adapter over the exact GravesX runtime API. GravesX remains the source of truth. */
internal class GravesXAccess(
    private val gravesPlugin: Graves,
    private val localServer: String,
) {
    private val api = GravesXAPI(gravesPlugin)
    private val graveApi = api.gravesManagementAPI
    private val gson = GsonComponentSerializer.gson()

    init {
        require(localServer.isNotBlank()) { "A local server name is required for GravesX access" }
        require(gravesPlugin.isEnabled) { "GravesX must be enabled before its API is accessed" }
    }

    /** Returns all graves currently loaded by this GravesX instance for the requested server. */
    fun snapshot(server: String): List<GraveView> {
        requireLocalServer(server)
        if (!gravesPlugin.isEnabled) return emptyList()

        val now = System.currentTimeMillis()
        return graveApi.graveUUIDs.asSequence()
            .mapNotNull(graveApi::getGrave)
            .mapNotNull { toView(it, now) }
            .sortedByDescending(GraveView::createdAt)
            .toList()
    }

    /** Returns a locally loaded grave by ID; remote GravesX instances are not queried here. */
    fun grave(id: UUID): Grave? =
        if (gravesPlugin.isEnabled) graveApi.getGrave(id) else null

    /**
     * Checks the same owner, permission and per-grave enablement gates as GravesX's GUI route.
     * ARC's paid/free decision remains separate and must be made before [teleport] is invoked.
     */
    fun canTeleport(player: Player, id: UUID): Boolean {
        val grave = grave(id) ?: return false
        if (!player.isOnline || grave.ownerUUID != player.uniqueId) return false
        if (grave.timeAliveRemaining == 0L) return false

        val deathWorld = grave.locationDeath?.world ?: return false
        val target = resolveDisplayAnchor(grave) ?: return false
        if (target.world == null) return false

        val hasBasePermission = api.permissionAPI.hasGrantedPermission("graves.teleport", player)
        if (!hasBasePermission) return false

        val graveConfig = gravesPlugin.configManager.getConfigSection("teleport.enabled", grave) ?: return false
        val teleportEnabled = graveConfig.getBoolean("teleport.enabled", true)
        val hasWorldPermission = api.permissionAPI.hasGrantedPermission(
            "graves.teleport.world.${deathWorld.name}",
            player,
        )
        val hasGeneralBypass = api.permissionAPI.hasGrantedPermission("graves.bypass", player)
        val hasTeleportBypass = api.permissionAPI.hasGrantedPermission("graves.teleport.bypass", player)

        // Mirrors EntityManager.runFunction("teleport"): the base node is checked before its
        // enablement/world/bypass expression, and teleport.bypass can pass that expression.
        return (teleportEnabled && (hasBasePermission || hasWorldPermission || hasGeneralBypass)) ||
            hasTeleportBypass
    }

    /**
     * Dispatches GravesX's own safe teleport path and returns whether it was admitted for dispatch.
     * A true result is not proof of completion: GravesX may defer, cancel, or roll back the move.
     */
    fun teleport(player: Player, id: UUID): Boolean {
        val grave = grave(id) ?: return false
        if (!canTeleport(player, id)) return false

        val target = resolveDisplayAnchor(grave) ?: return false
        gravesPlugin.entityManager.teleportEntity(player, target, grave)
        return true
    }

    /** Resolves an actual placed grave block, falling back to the death location if none remain. */
    fun anchor(id: UUID): Location? = grave(id)?.let(::resolveDisplayAnchor)

    private fun toView(grave: Grave, now: Long): GraveView? {
        val ownerId = grave.ownerUUID ?: return null
        val anchor = resolveDisplayAnchor(grave) ?: return null
        val world = anchor.world ?: return null
        val remaining = grave.timeAliveRemaining
        val expiresAt = when {
            remaining < 0L -> -1L
            Long.MAX_VALUE - now < remaining -> Long.MAX_VALUE
            else -> now + remaining
        }
        val items = grave.inventoryItemStack.orEmpty()
            .asSequence()
            .take(MAX_NATIVE_INVENTORY_SLOTS)
            .filterNotNull()
            .filter(::hasContents)
            .map(::toItem)
            .toList()

        return GraveView(
            id = grave.uuid,
            ownerId = ownerId,
            server = localServer,
            world = world.name,
            x = anchor.blockX,
            y = anchor.blockY,
            z = anchor.blockZ,
            createdAt = grave.timeCreation,
            expiresAt = expiresAt,
            experience = grave.experience.coerceAtLeast(0),
            items = items,
        )
    }

    private fun toItem(item: ItemStack): GraveItem {
        val fallbackName = Component.translatable(item.type.translationKey())
        val displayName = runCatching {
            item.itemMeta?.displayName() ?: fallbackName
        }.getOrDefault(fallbackName)
        val nameJson = runCatching { gson.serialize(withoutActions(displayName)) }
            .getOrNull()
            ?.takeIf(::isWireSafeItemName)
            ?: runCatching { gson.serialize(fallbackName) }
                .getOrNull()
                ?.takeIf(::isWireSafeItemName)
            ?: FALLBACK_ITEM_NAME_JSON
        return GraveItem(nameJson, item.amount)
    }

    private fun withoutActions(component: Component): Component = component
        .clickEvent(null)
        .hoverEvent(null)
        .insertion(null)
        .children(component.children().map(::withoutActions))

    private fun isWireSafeItemName(value: String): Boolean =
        value.length in 1..MAX_ITEM_NAME_JSON_CHARS &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_ITEM_NAME_JSON_BYTES &&
            value.none(Char::isISOControl)

    private fun hasContents(item: ItemStack): Boolean = item.amount > 0 && !item.type.isAir

    private fun resolveDisplayAnchor(grave: Grave): Location? = selectGraveAnchor(
        deathLocation = grave.locationDeath,
        placedBlocks = gravesPlugin.blockManager.getBlockList(grave),
    )

    private fun requireLocalServer(requested: String) {
        require(requested.equals(localServer, ignoreCase = true)) {
            "GravesX API exposes only this server's loaded cache (local='$localServer', requested='$requested')"
        }
    }

    companion object {
        private const val MAX_NATIVE_INVENTORY_SLOTS = 54
        private const val MAX_ITEM_NAME_JSON_CHARS = 512
        private const val MAX_ITEM_NAME_JSON_BYTES = 1_024
        private const val FALLBACK_ITEM_NAME_JSON = "{\"text\":\"Предмет\"}"

        fun find(server: Server, localServer: String): GravesXAccess? {
            val plugin = server.pluginManager.getPlugin("GravesX") as? Graves ?: return null
            return plugin.takeIf { it.isEnabled }?.let { GravesXAccess(it, localServer) }
        }
    }
}

/** Chooses only a currently placed block; death position is a fallback for graves without blocks. */
internal fun selectGraveAnchor(deathLocation: Location?, placedBlocks: Iterable<Location?>): Location? {
    val blocks = placedBlocks.filterNotNull().filter { it.world != null }
    if (blocks.isEmpty()) return deathLocation?.takeIf { it.world != null }

    val stableOrder = compareBy<Location>(
        { it.world!!.uid.toString() },
        { it.blockX },
        { it.blockY },
        { it.blockZ },
        { it.x },
        { it.y },
        { it.z },
    )
    val reference = deathLocation?.takeIf { it.world != null } ?: return blocks.minWithOrNull(stableOrder)
    val referenceWorldId = reference.world!!.uid

    return blocks.asSequence()
        .filter { it.world?.uid == referenceWorldId }
        .minWithOrNull(
            compareBy<Location> { squaredDistance(reference, it) }
                .thenBy { it.world!!.uid.toString() }
                .thenBy { it.blockX }
                .thenBy { it.blockY }
                .thenBy { it.blockZ }
                .thenBy { it.x }
                .thenBy { it.y }
                .thenBy { it.z },
        ) ?: blocks.minWithOrNull(stableOrder)
}

private fun squaredDistance(left: Location, right: Location): Double {
    val dx = left.x - right.x
    val dy = left.y - right.y
    val dz = left.z - right.z
    return dx * dx + dy * dy + dz * dz
}
