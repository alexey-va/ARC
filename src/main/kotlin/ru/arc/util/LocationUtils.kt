package ru.arc.util

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.roundToInt

object LocationUtils {

    data class LocationData(
        val location: Location,
        val corner: Boolean
    )

    @JvmStatic
    fun getLine(l1: Location, l2: Location, density: Double, skipFirst: Boolean): List<Location> {
        val locations = mutableListOf<Location>()

        val distance = l1.distance(l2)
        val count = (distance * density).roundToInt()
        val vector = Vector(l2.x - l1.x, l2.y - l1.y, l2.z - l1.z)
        vector.multiply(1.0 / count)

        if (!skipFirst) locations.add(l1)
        var l = l1.clone()
        repeat(count) {
            l = l.add(vector)
            locations.add(l.clone())
        }

        return locations
    }

    @JvmStatic
    fun getLineWithCornerData(
        l1: Location,
        l2: Location,
        density: Double,
        skipFirst: Boolean,
        cornerDistance: Int
    ): List<LocationData> {
        val locations = mutableListOf<LocationData>()

        val distance = l1.distance(l2)
        val count = (distance * density).roundToInt()
        val vector = Vector(l2.x - l1.x, l2.y - l1.y, l2.z - l1.z)
        vector.multiply(1.0 / count)

        if (!skipFirst) locations.add(LocationData(l1, true))
        var l = l1.clone()
        repeat(count) {
            l = l.add(vector)
            locations.add(
                LocationData(
                    l.clone(),
                    l.distanceSquared(l2) <= cornerDistance * cornerDistance ||
                        l.distanceSquared(l1) <= cornerDistance * cornerDistance
                )
            )
        }

        return locations
    }

    @JvmStatic
    fun getBorderLocations(corner1: Location, corner2: Location, density: Int): List<Location> {
        val minX = minOf(corner1.x, corner2.x)
        val minY = minOf(corner1.y, corner2.y)
        val minZ = minOf(corner1.z, corner2.z)
        val maxX = maxOf(corner1.x, corner2.x)
        val maxY = maxOf(corner1.y, corner2.y)
        val maxZ = maxOf(corner1.z, corner2.z)
        val w = corner1.world

        val locations = mutableListOf<Location>()
        locations.addAll(
            getLine(
                Location(w, minX, minY, minZ),
                Location(w, maxX, minY, minZ),
                density.toDouble(),
                false
            )
        )
        locations.addAll(
            getLine(
                Location(w, minX, minY, minZ),
                Location(w, minX, minY, maxZ),
                density.toDouble(),
                true
            )
        )
        locations.addAll(
            getLine(
                Location(w, maxX, minY, minZ),
                Location(w, maxX, minY, maxZ),
                density.toDouble(),
                true
            )
        )
        locations.addAll(
            getLine(
                Location(w, minX, minY, maxZ),
                Location(w, maxX, minY, maxZ),
                density.toDouble(),
                true
            )
        )

        locations.addAll(
            getLine(
                Location(w, minX, maxY, minZ),
                Location(w, maxX, maxY, minZ),
                density.toDouble(),
                true
            )
        )
        locations.addAll(
            getLine(
                Location(w, minX, maxY, minZ),
                Location(w, minX, maxY, maxZ),
                density.toDouble(),
                true
            )
        )
        locations.addAll(
            getLine(
                Location(w, maxX, maxY, minZ),
                Location(w, maxX, maxY, maxZ),
                density.toDouble(),
                true
            )
        )
        locations.addAll(
            getLine(
                Location(w, minX, maxY, maxZ),
                Location(w, maxX, maxY, maxZ),
                density.toDouble(),
                true
            )
        )

        locations.addAll(
            getLine(
                Location(w, minX, minY, minZ),
                Location(w, minX, maxY, minZ),
                density.toDouble(),
                false
            )
        )
        locations.addAll(
            getLine(
                Location(w, maxX, minY, minZ),
                Location(w, maxX, maxY, minZ),
                density.toDouble(),
                true
            )
        )
        locations.addAll(
            getLine(
                Location(w, maxX, minY, maxZ),
                Location(w, maxX, maxY, maxZ),
                density.toDouble(),
                true
            )
        )
        locations.addAll(
            getLine(
                Location(w, minX, minY, maxZ),
                Location(w, minX, maxY, maxZ),
                density.toDouble(),
                true
            )
        )
        return locations
    }

    @JvmStatic
    fun getBorderLocationsWithCornerData(
        corner1: Location,
        corner2: Location,
        density: Int,
        cornerDistance: Int
    ): List<LocationData> {
        val minX = minOf(corner1.x, corner2.x)
        val minY = minOf(corner1.y, corner2.y)
        val minZ = minOf(corner1.z, corner2.z)
        val maxX = maxOf(corner1.x, corner2.x)
        val maxY = maxOf(corner1.y, corner2.y)
        val maxZ = maxOf(corner1.z, corner2.z)
        val w = corner1.world

        val locations = mutableListOf<LocationData>()
        locations.addAll(
            getLineWithCornerData(
                Location(w, minX, minY, minZ),
                Location(w, maxX, minY, minZ),
                density.toDouble(),
                false,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, minX, minY, minZ),
                Location(w, minX, minY, maxZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, maxX, minY, minZ),
                Location(w, maxX, minY, maxZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, minX, minY, maxZ),
                Location(w, maxX, minY, maxZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )

        locations.addAll(
            getLineWithCornerData(
                Location(w, minX, maxY, minZ),
                Location(w, maxX, maxY, minZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, minX, maxY, minZ),
                Location(w, minX, maxY, maxZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, maxX, maxY, minZ),
                Location(w, maxX, maxY, maxZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, minX, maxY, maxZ),
                Location(w, maxX, maxY, maxZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )

        locations.addAll(
            getLineWithCornerData(
                Location(w, minX, minY, minZ),
                Location(w, minX, maxY, minZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, maxX, minY, minZ),
                Location(w, maxX, maxY, minZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, maxX, minY, maxZ),
                Location(w, maxX, maxY, maxZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        locations.addAll(
            getLineWithCornerData(
                Location(w, minX, minY, maxZ),
                Location(w, minX, maxY, maxZ),
                density.toDouble(),
                true,
                cornerDistance
            )
        )
        return locations
    }
}

