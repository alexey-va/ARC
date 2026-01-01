@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
package ru.arc.util

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase

class UtilsTest : TestBase() {

    private lateinit var world: WorldMock

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        world = server.addSimpleWorld("test-world")
    }

    // ========== Split Method Tests ==========

    @Test
    fun testSplitNormal() {
        val stack = ItemStackMock(Material.DIAMOND, 64)
        val result = ItemUtils.split(stack, 32)

        assertEquals(1, result.size, "Should return 1 stack for 32 items")
        assertEquals(32, result[0].amount, "Stack should have 32 items")
    }

    @Test
    fun testSplitMultipleStacks() {
        val stack = ItemStackMock(Material.DIAMOND, 64)
        val result = ItemUtils.split(stack, 100)

        assertEquals(2, result.size, "Should return 2 stacks for 100 items")
        assertEquals(64, result[0].amount, "First stack should be full")
        assertEquals(36, result[1].amount, "Second stack should have remaining")
    }

    @Test
    fun testSplitExactMaxStack() {
        val stack = ItemStackMock(Material.DIAMOND, 64)
        val result = ItemUtils.split(stack, 64)

        assertEquals(1, result.size, "Should return 1 stack for exact max stack")
        assertEquals(64, result[0].amount, "Stack should be full")
    }

    @Test
    fun testSplitMoreThanMaxStack() {
        val stack = ItemStackMock(Material.DIAMOND, 64)
        val result = ItemUtils.split(stack, 128)

        assertEquals(2, result.size, "Should return 2 stacks for 128 items")
        assertEquals(64, result[0].amount, "First stack should be full")
        assertEquals(64, result[1].amount, "Second stack should be full")
    }

    @Test
    fun testSplitZero() {
        val stack = ItemStackMock(Material.DIAMOND, 64)
        val result = ItemUtils.split(stack, 0)

        assertTrue(result.isEmpty(), "Should return empty list for 0 count")
    }

    @Test
    fun testSplitNullStack() {
        val result = ItemUtils.split(null, 10)

        assertTrue(result.isEmpty(), "Should return empty list for null stack")
    }

    @Test
    fun testSplitSmallStack() {
        val stack = ItemStackMock(Material.DIAMOND, 10)
        val result = ItemUtils.split(stack, 5)

        assertEquals(1, result.size, "Should return 1 stack")
        assertEquals(5, result[0].amount, "Stack should have 5 items")
    }

    @Test
    fun testSplitNonStackable() {
        val stack = ItemStackMock(Material.DIAMOND_SWORD, 1)
        val result = ItemUtils.split(stack, 5)

        assertEquals(5, result.size, "Should return 5 stacks for non-stackable items")
        result.forEach { assertEquals(1, it.amount, "Each stack should have 1 item") }
    }

    // ========== Random Array Tests ==========

    @Test
    fun testRandomArray() {
        val array = arrayOf("a", "b", "c", "d", "e")

        repeat(100) {
            val result = RandomUtils.random(array)
            assertTrue(array.contains(result), "Result should be from array")
        }
    }

    @Test
    fun testRandomArraySingleElement() {
        val array = arrayOf("single")
        val result = RandomUtils.random(array)

        assertEquals("single", result, "Should return the only element")
    }

    @Test
    fun testRandomArrayEmpty() {
        val array = arrayOf<String>()

        // Empty array causes IllegalArgumentException from nextInt(0)
        assertThrows<IllegalArgumentException> {
            RandomUtils.random(array)
        }
    }

    @Test
    fun testRandomArrayWithAmount() {
        val array = arrayOf("a", "b", "c", "d", "e")
        val result = RandomUtils.random(array, 3)

        assertEquals(3, result.size, "Should return 3 elements")
        result.forEach { assertTrue(array.contains(it), "Each element should be from original array") }
        assertEquals(result.toSet().size, result.size, "Elements should be unique")
    }

    @Test
    fun testRandomArrayWithAmountEqualToLength() {
        val array = arrayOf("a", "b", "c")
        val result = RandomUtils.random(array, 3)

        assertEquals(3, result.size, "Should return all elements")
        assertEquals(array.toSet(), result.toSet(), "Should contain all original elements")
    }

    @Test
    fun testRandomArrayWithAmountGreaterThanLength() {
        val array = arrayOf("a", "b", "c")
        val result = RandomUtils.random(array, 5)

        assertEquals(3, result.size, "Should return all elements when amount > length")
        assertEquals(array.toSet(), result.toSet(), "Should contain all original elements")
    }

    @Test
    fun testRandomArrayWithAmountZero() {
        val array = arrayOf("a", "b", "c")
        val result = RandomUtils.random(array, 0)

        assertEquals(0, result.size, "Should return empty array for amount 0")
    }

    // ========== Random Collection Tests ==========

    @Test
    fun testRandomList() {
        val list = listOf("a", "b", "c", "d", "e")

        repeat(100) {
            val result = RandomUtils.random(list)
            assertTrue(list.contains(result), "Result should be from list")
        }
    }

    @Test
    fun testRandomSet() {
        val set = setOf("a", "b", "c", "d", "e")

        repeat(100) {
            val result = RandomUtils.random(set)
            assertTrue(set.contains(result), "Result should be from set")
        }
    }

    @Test
    fun testRandomCollectionSingleElement() {
        val collection = listOf("single")
        val result = RandomUtils.random(collection)

        assertEquals("single", result, "Should return the only element")
    }

    @Test
    fun testRandomCollectionEmpty() {
        val collection = emptyList<String>()

        assertThrows<IllegalArgumentException> {
            RandomUtils.random(collection)
        }
    }

    @Test
    fun testRandomCollectionNull() {
        assertThrows<IllegalArgumentException> {
            RandomUtils.random(null as Collection<String>?)
        }
    }

    @Test
    fun testRandomCollectionLarge() {
        val collection = (1..1000).toList()

        repeat(100) {
            val result = RandomUtils.random(collection)
            assertTrue(collection.contains(result), "Result should be from collection")
        }
    }

    // ========== Random Map Tests ==========

    @Test
    fun testRandomMapEntry() {
        val map = mapOf(
            "key1" to "value1",
            "key2" to "value2",
            "key3" to "value3"
        )

        repeat(100) {
            val result = RandomUtils.random(map)
            assertTrue(map.containsKey(result.key), "Key should be from map")
            assertEquals(map[result.key], result.value, "Value should match")
        }
    }

    @Test
    fun testRandomMapEntrySingle() {
        val map = mapOf("key1" to "value1")
        val result = RandomUtils.random(map)

        assertEquals("key1", result.key, "Should return the only entry")
        assertEquals("value1", result.value, "Should return the only entry")
    }

    @Test
    fun testRandomMapEntryEmpty() {
        val map = emptyMap<String, String>()

        assertThrows<IllegalArgumentException> {
            RandomUtils.random(map)
        }
    }

    @Test
    fun testRandomMapEntryLarge() {
        val map = (1..1000).associateWith { "value$it" }

        repeat(100) {
            val result = RandomUtils.random(map)
            assertTrue(map.containsKey(result.key), "Key should be from map")
        }
    }

    // ========== Rotate Facing Tests ==========

    @Test
    fun testRotateFacingClockwise() {
        assertEquals(BlockFace.EAST, BlockUtils.rotateFacingClockwise(BlockFace.NORTH), "NORTH -> EAST")
        assertEquals(BlockFace.SOUTH, BlockUtils.rotateFacingClockwise(BlockFace.EAST), "EAST -> SOUTH")
        assertEquals(BlockFace.WEST, BlockUtils.rotateFacingClockwise(BlockFace.SOUTH), "SOUTH -> WEST")
        assertEquals(BlockFace.NORTH, BlockUtils.rotateFacingClockwise(BlockFace.WEST), "WEST -> NORTH")
    }

    @Test
    fun testRotateFacingCounterClockwise() {
        assertEquals(BlockFace.WEST, BlockUtils.rotateFacingCounterClockwise(BlockFace.NORTH), "NORTH -> WEST")
        assertEquals(BlockFace.NORTH, BlockUtils.rotateFacingCounterClockwise(BlockFace.EAST), "EAST -> NORTH")
        assertEquals(BlockFace.EAST, BlockUtils.rotateFacingCounterClockwise(BlockFace.SOUTH), "SOUTH -> EAST")
        assertEquals(BlockFace.SOUTH, BlockUtils.rotateFacingCounterClockwise(BlockFace.WEST), "WEST -> SOUTH")
    }

    @Test
    fun testRotateFacing180() {
        assertEquals(BlockFace.SOUTH, BlockUtils.rotateFacing180(BlockFace.NORTH), "NORTH -> SOUTH")
        assertEquals(BlockFace.WEST, BlockUtils.rotateFacing180(BlockFace.EAST), "EAST -> WEST")
        assertEquals(BlockFace.NORTH, BlockUtils.rotateFacing180(BlockFace.SOUTH), "SOUTH -> NORTH")
        assertEquals(BlockFace.EAST, BlockUtils.rotateFacing180(BlockFace.WEST), "WEST -> EAST")
    }

    @Test
    fun testRotateFacingClockwiseNonCardinal() {
        val original = BlockFace.UP
        val result = BlockUtils.rotateFacingClockwise(original)
        assertEquals(original, result, "Non-cardinal faces should remain unchanged")
    }

    @Test
    fun testRotateFacingCounterClockwiseNonCardinal() {
        val original = BlockFace.DOWN
        val result = BlockUtils.rotateFacingCounterClockwise(original)
        assertEquals(original, result, "Non-cardinal faces should remain unchanged")
    }

    @Test
    fun testRotateFacing180NonCardinal() {
        val original = BlockFace.UP
        val result = BlockUtils.rotateFacing180(original)
        assertEquals(original, result, "Non-cardinal faces should remain unchanged")
    }

    @Test
    fun testRotateFacingFullCircle() {
        var facing = BlockFace.NORTH
        repeat(4) {
            facing = BlockUtils.rotateFacingClockwise(facing)
        }
        assertEquals(BlockFace.NORTH, facing, "4 clockwise rotations should return to original")
    }

    // ========== GetLine Tests ==========

    @Test
    fun testGetLine() {
        val l1 = Location(world, 0.0, 0.0, 0.0)
        val l2 = Location(world, 10.0, 0.0, 0.0)
        val result = LocationUtils.getLine(l1, l2, 1.0, false)

        assertTrue(result.isNotEmpty(), "Should return locations")
        assertEquals(l1, result[0], "First location should match l1 when skipFirst=false")
    }

    @Test
    fun testGetLineSkipFirst() {
        val l1 = Location(world, 0.0, 0.0, 0.0)
        val l2 = Location(world, 10.0, 0.0, 0.0)
        val result = LocationUtils.getLine(l1, l2, 1.0, true)

        assertTrue(result.isNotEmpty(), "Should return locations")
        assertNotEquals(l1, result[0], "First location should not match l1 when skipFirst=true")
    }

    @Test
    fun testGetLineSameLocation() {
        val l1 = Location(world, 5.0, 5.0, 5.0)
        val l2 = Location(world, 5.0, 5.0, 5.0)
        val result = LocationUtils.getLine(l1, l2, 1.0, false)

        assertTrue(result.isNotEmpty(), "Should return at least one location")
        if (!result.isEmpty()) {
            assertEquals(l1, result[0], "Should include l1 when skipFirst=false")
        }
    }

    @Test
    fun testGetLineHighDensity() {
        val l1 = Location(world, 0.0, 0.0, 0.0)
        val l2 = Location(world, 10.0, 0.0, 0.0)
        val result = LocationUtils.getLine(l1, l2, 10.0, false)

        assertTrue(result.size > 10, "High density should produce many locations")
    }

    @Test
    fun testGetLineLowDensity() {
        val l1 = Location(world, 0.0, 0.0, 0.0)
        val l2 = Location(world, 10.0, 0.0, 0.0)
        val result = LocationUtils.getLine(l1, l2, 0.1, false)

        assertTrue(result.size < 10, "Low density should produce fewer locations")
    }

    @Test
    fun testGetLine3D() {
        val l1 = Location(world, 0.0, 0.0, 0.0)
        val l2 = Location(world, 10.0, 10.0, 10.0)
        val result = LocationUtils.getLine(l1, l2, 1.0, false)

        assertTrue(result.isNotEmpty(), "Should handle 3D lines")
        result.forEach { location ->
            assertNotNull(location.world, "Location should have world")
        }
    }

    // ========== GetBorderLocations Tests ==========

    @Test
    fun testGetBorderLocations() {
        val corner1 = Location(world, 0.0, 0.0, 0.0)
        val corner2 = Location(world, 10.0, 10.0, 10.0)
        val result = LocationUtils.getBorderLocations(corner1, corner2, 1)

        assertTrue(result.isNotEmpty(), "Should return border locations")
        // Border should have locations on the edges of the bounding box
    }

    @Test
    fun testGetBorderLocationsSameCorner() {
        val corner1 = Location(world, 5.0, 5.0, 5.0)
        val corner2 = Location(world, 5.0, 5.0, 5.0)
        val result = LocationUtils.getBorderLocations(corner1, corner2, 1)

        // Should still return some locations (at least the corner itself)
        assertNotNull(result, "Should return result even for same corner")
    }

    @Test
    fun testGetBorderLocationsReversed() {
        val corner1 = Location(world, 10.0, 10.0, 10.0)
        val corner2 = Location(world, 0.0, 0.0, 0.0)
        val result1 = LocationUtils.getBorderLocations(corner1, corner2, 1)
        val result2 = LocationUtils.getBorderLocations(corner2, corner1, 1)

        // Results should be similar (order shouldn't matter much)
        assertTrue(result1.isNotEmpty(), "Should return locations")
        assertTrue(result2.isNotEmpty(), "Should return locations")
    }

    // ========== Edge Cases and Integration Tests ==========

    @Test
    fun testRandomArrayDistribution() {
        val array = arrayOf("a", "b", "c")
        val counts = mutableMapOf<String, Int>()

        repeat(1000) {
            val result = RandomUtils.random(array)
            counts[result] = counts.getOrDefault(result, 0) + 1
        }

        // Each element should appear roughly equally (within 20% variance)
        val expected = 333
        counts.values.forEach { count ->
            assertTrue(count > 250 && count < 450, "Distribution should be roughly equal")
        }
    }

    @Test
    fun testRandomCollectionDistribution() {
        val collection = listOf("a", "b", "c")
        val counts = mutableMapOf<String, Int>()

        repeat(1000) {
            val result = RandomUtils.random(collection)
            counts[result] = counts.getOrDefault(result, 0) + 1
        }

        // Each element should appear roughly equally
        val expected = 333
        counts.values.forEach { count ->
            assertTrue(count > 250 && count < 450, "Distribution should be roughly equal")
        }
    }

    @Test
    fun testRotateFacingAllDirections() {
        val directions = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

        directions.forEach { direction ->
            val clockwise = BlockUtils.rotateFacingClockwise(direction)
            val counterClockwise = BlockUtils.rotateFacingCounterClockwise(direction)
            val rotated180 = BlockUtils.rotateFacing180(direction)

            assertNotNull(clockwise, "Clockwise rotation should not be null")
            assertNotNull(counterClockwise, "Counter-clockwise rotation should not be null")
            assertNotNull(rotated180, "180 rotation should not be null")
        }
    }

    @Test
    fun testSplitPreservesMaterial() {
        val stack = ItemStackMock(Material.DIAMOND, 64)
        val result = ItemUtils.split(stack, 32)

        result.forEach {
            assertEquals(Material.DIAMOND, it.type, "All stacks should have same material")
        }
    }

    @Test
    fun testSplitPreservesItemMeta() {
        val stack = ItemStackMock(Material.DIAMOND, 64)
        val meta = stack.itemMeta
        meta?.setDisplayName("Test Item")
        stack.itemMeta = meta

        val result = ItemUtils.split(stack, 32)

        result.forEach {
            assertEquals("Test Item", it.itemMeta?.displayName, "All stacks should preserve meta")
        }
    }

    @Test
    fun testGetLineNegativeCoordinates() {
        val l1 = Location(world, -10.0, -10.0, -10.0)
        val l2 = Location(world, -5.0, -5.0, -5.0)
        val result = LocationUtils.getLine(l1, l2, 1.0, false)

        assertTrue(result.isNotEmpty(), "Should handle negative coordinates")
    }

    @Test
    fun testGetLineLargeDistance() {
        val l1 = Location(world, 0.0, 0.0, 0.0)
        val l2 = Location(world, 1000.0, 1000.0, 1000.0)
        val result = LocationUtils.getLine(l1, l2, 1.0, false)

        assertTrue(result.isNotEmpty(), "Should handle large distances")
    }

    @Test
    fun testRandomArrayWithAmountOne() {
        val array = arrayOf("a", "b", "c", "d", "e")
        val result = RandomUtils.random(array, 1)

        assertEquals(1, result.size, "Should return 1 element")
        assertTrue(array.contains(result[0]), "Element should be from array")
    }

    @Test
    fun testRandomMapEntryAllEntries() {
        val map = mapOf(
            "a" to 1,
            "b" to 2,
            "c" to 3
        )
        val seenKeys = mutableSetOf<String>()

        // Try to get all entries (might take many attempts)
        repeat(1000) {
            val entry = RandomUtils.random(map)
            seenKeys.add(entry.key)
            if (seenKeys.size == map.size) return
        }

        assertEquals(map.size, seenKeys.size, "Should eventually see all entries")
    }
}

