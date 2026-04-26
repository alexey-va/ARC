package ru.arc.util

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player

/**
 * Kotlin extensions for Entity operations.
 *
 * Provides convenient methods for entity lookups and cleanup.
 */

// === Nearby Entity Queries ===

/**
 * Gets nearby entities of a specific type.
 *
 * @param T the entity type
 * @param radius the search radius
 * @return list of nearby entities
 */
inline fun <reified T : Entity> Location.nearbyEntities(radius: Double): Collection<T> = getNearbyEntitiesByType(T::class.java, radius)

/**
 * Gets nearby entities of a specific type around a block.
 *
 * @param T the entity type
 * @param radius the search radius
 * @return list of nearby entities
 */
inline fun <reified T : Entity> Block.nearbyEntities(radius: Double): Collection<T> =
    location.getNearbyEntitiesByType(T::class.java, radius)

/**
 * Gets nearby players.
 *
 * @param radius the search radius
 * @return list of nearby players
 */
fun Location.nearbyPlayers(radius: Double): Collection<Player> = getNearbyEntitiesByType(Player::class.java, radius)

/**
 * Gets nearby item frames.
 *
 * @param radius the search radius
 * @return list of nearby item frames
 */
fun Location.nearbyItemFrames(radius: Double): Collection<ItemFrame> = getNearbyEntitiesByType(ItemFrame::class.java, radius)

/**
 * Gets nearby display entities (ItemDisplay, TextDisplay, BlockDisplay).
 *
 * @param radius the search radius
 * @return list of nearby display entities
 */
fun Location.nearbyDisplays(radius: Double): Collection<Display> = getNearbyEntitiesByType(Display::class.java, radius)

// === Cleanup Operations ===

/**
 * Removes all invisible item frames with custom model data nearby.
 * Useful for cleaning up ItemsAdder furniture remnants.
 *
 * @param radius the search radius
 * @return the number of removed frames
 */
fun Location.cleanupCustomItemFrames(radius: Double): Int {
    var count = 0
    nearbyItemFrames(radius).forEach { frame ->
        if (!frame.isVisible) {
            val item = frame.item
            if (!item.type.isAir && item.hasCustomModelDataSafe()) {
                frame.remove()
                count++
            }
        }
    }
    return count
}

/**
 * Removes all display entities nearby.
 * Useful for cleaning up ItemsAdder furniture remnants.
 *
 * @param radius the search radius
 * @return the number of removed displays
 */
fun Location.cleanupDisplayEntities(radius: Double): Int {
    var count = 0
    nearbyDisplays(radius).forEach { display ->
        display.remove()
        count++
    }
    return count
}

/**
 * Removes all custom furniture remnants (item frames and displays) nearby.
 *
 * @param radius the search radius
 * @return the total number of removed entities
 */
fun Location.cleanupFurnitureEntities(radius: Double): Int = cleanupCustomItemFrames(radius) + cleanupDisplayEntities(radius)

/**
 * Removes all custom furniture remnants around this block.
 *
 * @param radius the search radius
 * @return the total number of removed entities
 */
fun Block.cleanupFurnitureEntities(radius: Double): Int = location.cleanupFurnitureEntities(radius)

// === Entity Utilities ===

/**
 * Checks if this entity is within range of a location.
 *
 * @param location the target location
 * @param range the maximum distance
 * @return true if within range
 */
fun Entity.isWithinRange(
    location: Location,
    range: Double,
): Boolean {
    if (this.world != location.world) return false
    return this.location.distanceSquared(location) <= range * range
}

/**
 * Gets the distance to a location, or null if in different worlds.
 *
 * @param location the target location
 * @return the distance or null
 */
fun Entity.distanceToOrNull(location: Location): Double? {
    if (this.world != location.world) return null
    return this.location.distance(location)
}
