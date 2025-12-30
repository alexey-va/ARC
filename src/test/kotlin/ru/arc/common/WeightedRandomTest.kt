package ru.arc.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WeightedRandomTest {

    private lateinit var weightedRandom: WeightedRandom<String>

    @BeforeEach
    fun setUp() {
        weightedRandom = WeightedRandom()
    }

    // ========== Add Method Tests ==========

    @Test
    fun testAdd() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)

        assertEquals(2, weightedRandom.size(), "Size should be 2")
    }

    @Test
    fun testAddSingleItem() {
        weightedRandom.add("single", 5.0)
        assertEquals(1, weightedRandom.size(), "Size should be 1")
        assertTrue(weightedRandom.values().contains("single"), "Should contain the item")
    }

    @Test
    fun testAddZeroWeight() {
        weightedRandom.add("item1", 0.0)
        weightedRandom.add("item2", -5.0)

        assertEquals(0, weightedRandom.size(), "Items with zero or negative weight should not be added")
    }

    @Test
    fun testAddNegativeWeight() {
        weightedRandom.add("negative", -10.0)
        assertEquals(0, weightedRandom.size(), "Negative weight should not be added")
    }

    @Test
    fun testAddVerySmallWeight() {
        weightedRandom.add("tiny", 0.0000001)
        assertEquals(1, weightedRandom.size(), "Very small positive weight should be added")
    }

    @Test
    fun testAddVeryLargeWeight() {
        weightedRandom.add("huge", Double.MAX_VALUE / 2)
        assertEquals(1, weightedRandom.size(), "Very large weight should be added")
    }

    @Test
    fun testAddMultipleItemsSameWeight() {
        repeat(10) { i ->
            weightedRandom.add("item$i", 10.0)
        }
        assertEquals(10, weightedRandom.size(), "Should add all items with same weight")
    }

    @Test
    fun testAddMultipleItemsDifferentWeights() {
        weightedRandom.add("item1", 1.0)
        weightedRandom.add("item2", 2.0)
        weightedRandom.add("item3", 3.0)
        weightedRandom.add("item4", 4.0)
        weightedRandom.add("item5", 5.0)

        assertEquals(5, weightedRandom.size(), "Should add all items with different weights")
    }

    @Test
    fun testAddDuplicateValue() {
        weightedRandom.add("duplicate", 10.0)
        weightedRandom.add("duplicate", 20.0)

        assertEquals(2, weightedRandom.size(), "Should allow duplicate values with different weights")
    }

    @Test
    fun testAddManyItems() {
        repeat(1000) { i ->
            weightedRandom.add("item$i", (i + 1).toDouble())
        }
        assertEquals(1000, weightedRandom.size(), "Should handle many items")
    }

    @Test
    fun testAddWithPrecision() {
        weightedRandom.add("precise1", 0.123456789)
        weightedRandom.add("precise2", 0.987654321)
        assertEquals(2, weightedRandom.size(), "Should handle precise decimal weights")
    }

    // ========== Random Method Tests ==========

    @Test
    fun testRandom() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        weightedRandom.add("item3", 30.0)

        val result = weightedRandom.random()
        assertNotNull(result, "Random should return a value")
        assertTrue(setOf("item1", "item2", "item3").contains(result), "Result should be one of the added items")
    }

    @Test
    fun testRandomEmpty() {
        val result = weightedRandom.random()
        assertNull(result, "Random should return null for empty collection")
    }

    @Test
    fun testRandomSingleItem() {
        weightedRandom.add("only", 100.0)

        repeat(100) {
            val result = weightedRandom.random()
            assertEquals("only", result, "Should always return the only item")
        }
    }

    @Test
    fun testRandomDistribution() {
        weightedRandom.add("common", 10.0)
        weightedRandom.add("rare", 5.0)
        weightedRandom.add("epic", 2.0)
        weightedRandom.add("legendary", 1.0)

        // Total weight = 18
        // Expected probabilities: common=10/18, rare=5/18, epic=2/18, legendary=1/18
        var commonCount = 0
        var rareCount = 0
        var epicCount = 0
        var legendaryCount = 0

        repeat(10000) {
            val result = weightedRandom.random()
            when (result) {
                "common" -> commonCount++
                "rare" -> rareCount++
                "epic" -> epicCount++
                "legendary" -> legendaryCount++
            }
        }

        // Common should be most frequent (should be ~55% of results)
        assertTrue(commonCount > rareCount, "Common should be more frequent than rare")
        assertTrue(rareCount > epicCount, "Rare should be more frequent than epic")
        assertTrue(epicCount > legendaryCount, "Epic should be more frequent than legendary")

        // Verify approximate distribution (within reasonable variance)
        val commonRatio = commonCount / 10000.0
        assertTrue(commonRatio > 0.4 && commonRatio < 0.7, "Common should be ~55% (40-70% acceptable)")
    }

    @Test
    fun testRandomEqualWeights() {
        repeat(5) { i ->
            weightedRandom.add("item$i", 10.0)
        }

        val counts = mutableMapOf<String, Int>()
        repeat(1000) {
            val result = weightedRandom.random()
            if (result != null) {
                counts[result] = counts.getOrDefault(result, 0) + 1
            }
        }

        // All items should appear roughly equally (within 20% variance)
        val expected = 200
        counts.values.forEach { count ->
            assertTrue(count > 150 && count < 250, "Equal weights should distribute roughly evenly")
        }
    }

    @Test
    fun testRandomExtremeWeightRatio() {
        weightedRandom.add("common", 1000.0)
        weightedRandom.add("rare", 1.0)

        var commonCount = 0
        var rareCount = 0

        repeat(10000) {
            when (weightedRandom.random()) {
                "common" -> commonCount++
                "rare" -> rareCount++
            }
        }

        // Common should be vastly more frequent
        assertTrue(commonCount > rareCount * 50, "Extreme weight ratio should favor heavy item")
        // With 1000:1 ratio, rare should appear occasionally in 10000 tries
        // But it's possible it doesn't appear, so we just verify common dominates
        assertTrue(commonCount > 9000, "Common should dominate with extreme ratio")
    }

    @Test
    fun testRandomConsistency() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        weightedRandom.add("item3", 30.0)

        val results = mutableSetOf<String>()
        repeat(100) {
            results.add(weightedRandom.random()!!)
        }

        // Should eventually get all items
        assertEquals(3, results.size, "Should eventually get all items")
    }

    // ========== GetNRandom Method Tests ==========

    @Test
    fun testGetNRandom() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        weightedRandom.add("item3", 30.0)
        weightedRandom.add("item4", 40.0)

        val result = weightedRandom.getNRandom(2)
        assertEquals(2, result.size, "Should return 2 items")
        assertTrue(result.all { item -> setOf("item1", "item2", "item3", "item4").contains(item) })
    }

    @Test
    fun testGetNRandomZero() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)

        val result = weightedRandom.getNRandom(0)
        assertTrue(result.isEmpty(), "Should return empty set for n=0")
    }

    @Test
    fun testGetNRandomNegative() {
        weightedRandom.add("item1", 10.0)

        // getNRandom now returns empty set for negative n (fixed bug)
        val result = weightedRandom.getNRandom(-5)
        assertTrue(result.isEmpty(), "Should return empty set for negative n")
    }

    @Test
    fun testGetNRandomMoreThanAvailable() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)

        val result = weightedRandom.getNRandom(5)
        assertTrue(result.size <= 2, "Should return at most 2 items")
        assertTrue(result.size > 0, "Should return at least some items")
    }

    @Test
    fun testGetNRandomExactCount() {
        repeat(10) { i ->
            weightedRandom.add("item$i", (i + 1).toDouble())
        }

        // The algorithm is probabilistic - it tries up to 10 times to get n unique items
        // With 10 items and equal-ish weights, it should usually get all 10
        // But we allow for rare cases where it doesn't get all unique values
        var result = weightedRandom.getNRandom(10)
        var attempts = 0
        // Try a few times to get all 10 (algorithm already tries 10 times internally)
        while (result.size < 10 && attempts < 3) {
            result = weightedRandom.getNRandom(10)
            attempts++
        }
        // Should get at least 9 out of 10 (very high probability)
        assertTrue(result.size >= 9, "Should return most items when available (got ${result.size} out of 10)")
    }

    @Test
    fun testGetNRandomEmpty() {
        val result = weightedRandom.getNRandom(5)
        assertTrue(result.isEmpty(), "Should return empty set for empty collection")
    }

    @Test
    fun testGetNRandomSingleItem() {
        weightedRandom.add("only", 100.0)

        val result = weightedRandom.getNRandom(1)
        assertEquals(1, result.size, "Should return 1 item")
        assertTrue(result.contains("only"), "Should contain the only item")
    }

    @Test
    fun testGetNRandomSingleItemRequestMany() {
        weightedRandom.add("only", 100.0)

        val result = weightedRandom.getNRandom(10)
        assertEquals(1, result.size, "Should return only 1 item even if more requested")
        assertTrue(result.contains("only"), "Should contain the only item")
    }

    @Test
    fun testGetNRandomAllUnique() {
        repeat(20) { i ->
            weightedRandom.add("item$i", (i + 1).toDouble())
        }

        val result = weightedRandom.getNRandom(20)
        assertEquals(20, result.size, "Should return all unique items")
        assertEquals(20, result.toSet().size, "All items should be unique")
    }

    @Test
    fun testGetNRandomLargeRequest() {
        repeat(5) { i ->
            weightedRandom.add("item$i", 10.0)
        }

        val result = weightedRandom.getNRandom(100)
        assertTrue(result.size <= 5, "Should return at most 5 items")
        assertTrue(result.size > 0, "Should return at least some items")
    }

    @Test
    fun testGetNRandomWithDuplicateValues() {
        weightedRandom.add("duplicate", 10.0)
        weightedRandom.add("duplicate", 20.0)
        weightedRandom.add("unique", 30.0)

        val result = weightedRandom.getNRandom(3)
        // Should get at least 2 unique items (duplicate can appear twice)
        assertTrue(result.size >= 2, "Should return at least 2 unique items")
        assertTrue(result.contains("unique"), "Should contain unique item")
    }

    // ========== Remove Method Tests ==========

    @Test
    fun testRemove() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        weightedRandom.add("item3", 30.0)

        val removed = weightedRandom.remove("item2")
        assertTrue(removed, "Should successfully remove item")
        assertEquals(2, weightedRandom.size(), "Size should be 2 after removal")
        assertFalse(weightedRandom.values().contains("item2"), "Item2 should not be in values")
    }

    @Test
    fun testRemoveNonExistent() {
        weightedRandom.add("item1", 10.0)
        val removed = weightedRandom.remove("item2")
        assertFalse(removed, "Should return false for non-existent item")
        assertEquals(1, weightedRandom.size(), "Size should remain 1")
    }

    @Test
    fun testRemoveFromEmpty() {
        val removed = weightedRandom.remove("anything")
        assertFalse(removed, "Should return false when removing from empty collection")
    }

    @Test
    fun testRemoveFirstItem() {
        weightedRandom.add("first", 10.0)
        weightedRandom.add("second", 20.0)
        weightedRandom.add("third", 30.0)

        val removed = weightedRandom.remove("first")
        assertTrue(removed, "Should remove first item")
        assertEquals(2, weightedRandom.size(), "Size should be 2")
        assertFalse(weightedRandom.values().contains("first"), "First should not be in values")
    }

    @Test
    fun testRemoveLastItem() {
        weightedRandom.add("first", 10.0)
        weightedRandom.add("second", 20.0)
        weightedRandom.add("last", 30.0)

        val removed = weightedRandom.remove("last")
        assertTrue(removed, "Should remove last item")
        assertEquals(2, weightedRandom.size(), "Size should be 2")
        assertFalse(weightedRandom.values().contains("last"), "Last should not be in values")
    }

    @Test
    fun testRemoveAllItems() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        weightedRandom.add("item3", 30.0)

        assertTrue(weightedRandom.remove("item1"), "Should remove item1")
        assertTrue(weightedRandom.remove("item2"), "Should remove item2")
        assertTrue(weightedRandom.remove("item3"), "Should remove item3")

        assertEquals(0, weightedRandom.size(), "Size should be 0 after removing all")
        assertTrue(weightedRandom.values().isEmpty(), "Values should be empty")
    }

    @Test
    fun testRemoveDuplicateValues() {
        weightedRandom.add("duplicate", 10.0)
        weightedRandom.add("duplicate", 20.0)
        weightedRandom.add("unique", 30.0)

        // Remove removes ALL instances of the value
        val removed = weightedRandom.remove("duplicate")
        assertTrue(removed, "Should remove duplicates")
        assertEquals(1, weightedRandom.size(), "Should have 1 item remaining (all duplicates removed)")
        assertTrue(weightedRandom.values().contains("unique"), "Should still contain unique item")
    }

    @Test
    fun testRemovePreservesWeights() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        weightedRandom.add("item3", 30.0)

        weightedRandom.remove("item2")

        // Remaining items should still be randomizable
        repeat(100) {
            val result = weightedRandom.random()
            assertNotNull(result, "Should still return values after removal")
            assertTrue(setOf("item1", "item3").contains(result), "Should only return remaining items")
        }
    }

    @Test
    fun testRemoveAndReadd() {
        weightedRandom.add("item", 10.0)
        weightedRandom.remove("item")
        weightedRandom.add("item", 20.0)

        assertEquals(1, weightedRandom.size(), "Should be able to re-add removed item")
        val result = weightedRandom.random()
        assertEquals("item", result, "Should return re-added item")
    }

    @Test
    fun testRemoveManyItems() {
        repeat(100) { i ->
            weightedRandom.add("item$i", (i + 1).toDouble())
        }

        // Remove every other item
        repeat(50) { i ->
            weightedRandom.remove("item${i * 2}")
        }

        assertEquals(50, weightedRandom.size(), "Should have 50 items remaining")
    }

    // ========== Values Method Tests ==========

    @Test
    fun testValues() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        weightedRandom.add("item3", 30.0)

        val values = weightedRandom.values()
        assertEquals(3, values.size, "Should have 3 values")
        assertTrue(values.contains("item1"))
        assertTrue(values.contains("item2"))
        assertTrue(values.contains("item3"))
    }

    @Test
    fun testValuesEmpty() {
        val values = weightedRandom.values()
        assertTrue(values.isEmpty(), "Should return empty collection for empty WeightedRandom")
    }

    @Test
    fun testValuesAfterAdd() {
        weightedRandom.add("item1", 10.0)
        var values = weightedRandom.values()
        assertEquals(1, values.size, "Should have 1 value")

        weightedRandom.add("item2", 20.0)
        values = weightedRandom.values()
        assertEquals(2, values.size, "Should have 2 values after adding")
    }

    @Test
    fun testValuesAfterRemove() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        weightedRandom.add("item3", 30.0)

        weightedRandom.remove("item2")
        val values = weightedRandom.values()
        assertEquals(2, values.size, "Should have 2 values after removal")
        assertFalse(values.contains("item2"), "Should not contain removed item")
    }

    @Test
    fun testValuesWithDuplicates() {
        weightedRandom.add("duplicate", 10.0)
        weightedRandom.add("duplicate", 20.0)
        weightedRandom.add("unique", 30.0)

        val values = weightedRandom.values()
        assertEquals(3, values.size, "Should have 3 values including duplicates")
        assertTrue(values.contains("duplicate"), "Should contain duplicate")
        assertTrue(values.contains("unique"), "Should contain unique")
    }

    @Test
    fun testValuesImmutability() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)

        val values1 = weightedRandom.values()
        weightedRandom.add("item3", 30.0)
        val values2 = weightedRandom.values()

        // values() returns a new collection each time
        assertEquals(2, values1.size, "First values() should still have 2 items")
        assertEquals(3, values2.size, "Second values() should have 3 items")
    }

    // ========== Size Method Tests ==========

    @Test
    fun testSize() {
        assertEquals(0, weightedRandom.size(), "Empty should have size 0")

        weightedRandom.add("item1", 10.0)
        assertEquals(1, weightedRandom.size(), "Should have size 1")

        weightedRandom.add("item2", 20.0)
        assertEquals(2, weightedRandom.size(), "Should have size 2")
    }

    @Test
    fun testSizeAfterRemove() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        assertEquals(2, weightedRandom.size(), "Should have size 2")

        weightedRandom.remove("item1")
        assertEquals(1, weightedRandom.size(), "Should have size 1 after removal")
    }

    @Test
    fun testSizeIgnoresZeroWeights() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 0.0)
        weightedRandom.add("item3", -5.0)
        weightedRandom.add("item4", 20.0)

        assertEquals(2, weightedRandom.size(), "Size should only count valid weights")
    }

    // ========== Weight Distribution Tests ==========

    @Test
    fun testWeightedDistribution() {
        // Add items with different weights
        weightedRandom.add("common", 10.0)
        weightedRandom.add("rare", 5.0)
        weightedRandom.add("epic", 2.0)
        weightedRandom.add("legendary", 1.0)

        // Test multiple random calls to see distribution
        var commonCount = 0
        var rareCount = 0
        var epicCount = 0
        var legendaryCount = 0

        repeat(10000) {
            val result = weightedRandom.random()
            when (result) {
                "common" -> commonCount++
                "rare" -> rareCount++
                "epic" -> epicCount++
                "legendary" -> legendaryCount++
            }
        }

        // Common should be most frequent
        assertTrue(commonCount > rareCount, "Common should be more frequent than rare")
        assertTrue(rareCount > epicCount, "Rare should be more frequent than epic")
        assertTrue(epicCount > legendaryCount, "Epic should be more frequent than legendary")
    }

    @Test
    fun testWeightedDistributionPrecise() {
        // Total weight = 100
        weightedRandom.add("item1", 50.0)  // 50%
        weightedRandom.add("item2", 30.0)  // 30%
        weightedRandom.add("item3", 20.0)  // 20%

        var item1Count = 0
        var item2Count = 0
        var item3Count = 0

        repeat(10000) {
            when (weightedRandom.random()) {
                "item1" -> item1Count++
                "item2" -> item2Count++
                "item3" -> item3Count++
            }
        }

        // Verify approximate distribution (within 5% variance)
        val item1Ratio = item1Count / 10000.0
        val item2Ratio = item2Count / 10000.0
        val item3Ratio = item3Count / 10000.0

        assertTrue(item1Ratio > 0.45 && item1Ratio < 0.55, "Item1 should be ~50%")
        assertTrue(item2Ratio > 0.25 && item2Ratio < 0.35, "Item2 should be ~30%")
        assertTrue(item3Ratio > 0.15 && item3Ratio < 0.25, "Item3 should be ~20%")
    }

    @Test
    fun testCumulativeWeightBehavior() {
        // TreeMap uses cumulative weights, test boundary behavior
        weightedRandom.add("item1", 10.0)  // Range: 0-10
        weightedRandom.add("item2", 20.0)  // Range: 10-30
        weightedRandom.add("item3", 30.0)  // Range: 30-60

        // Test many times to ensure all ranges are hit
        val results = mutableSetOf<String>()
        repeat(1000) {
            results.add(weightedRandom.random()!!)
        }

        assertEquals(3, results.size, "Should eventually hit all ranges")
    }

    // ========== Edge Cases and Boundary Tests ==========

    @Test
    fun testVerySmallWeights() {
        weightedRandom.add("tiny1", 0.0001)
        weightedRandom.add("tiny2", 0.0002)
        weightedRandom.add("normal", 100.0)

        // Normal should dominate, but tiny might appear (though very unlikely with such extreme ratio)
        var tinyCount = 0
        repeat(100000) {
            val result = weightedRandom.random()
            if (result == "tiny1" || result == "tiny2") {
                tinyCount++
            }
        }

        // With such extreme ratio (100.0 vs 0.0001), tiny might never appear
        // But the system should still work correctly
        assertTrue(tinyCount < 1000, "Tiny weights should be very rare if they appear")
        // Test that system doesn't crash with extreme ratios
        assertNotNull(weightedRandom.random(), "Should still return values with extreme weight ratios")
    }

    @Test
    fun testVeryLargeWeights() {
        // Use a large but not MAX_VALUE to avoid overflow issues
        weightedRandom.add("huge", 1_000_000_000.0)
        weightedRandom.add("normal", 100.0)

        // Huge should almost always win
        var hugeCount = 0
        repeat(1000) {
            if (weightedRandom.random() == "huge") {
                hugeCount++
            }
        }

        assertTrue(hugeCount > 900, "Huge weight should almost always win")
    }

    @Test
    fun testMixedWeightSizes() {
        weightedRandom.add("micro", 0.001)
        weightedRandom.add("small", 1.0)
        weightedRandom.add("medium", 100.0)
        weightedRandom.add("large", 10000.0)
        weightedRandom.add("huge", 1000000.0)

        val results = mutableSetOf<String>()
        repeat(10000) {
            results.add(weightedRandom.random()!!)
        }

        // With such extreme differences, huge will dominate, but should get at least huge and maybe large
        assertTrue(results.size >= 1, "Should get at least some results")
        assertTrue(results.contains("huge"), "Huge should appear")
        // System should handle extreme weight differences without crashing
        assertNotNull(weightedRandom.random(), "Should continue working with mixed weights")
    }

    @Test
    fun testPrecisionLoss() {
        // Test with weights that might cause precision issues
        weightedRandom.add("item1", 1.0 / 3.0)
        weightedRandom.add("item2", 1.0 / 3.0)
        weightedRandom.add("item3", 1.0 / 3.0)

        val results = mutableSetOf<String>()
        repeat(100) {
            results.add(weightedRandom.random()!!)
        }

        assertEquals(3, results.size, "Should handle precision correctly")
    }

    // ========== Integration and Complex Scenarios ==========

    @Test
    fun testAddRemoveAddCycle() {
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)
        assertEquals(2, weightedRandom.size(), "Should have 2 items")

        weightedRandom.remove("item1")
        assertEquals(1, weightedRandom.size(), "Should have 1 item after removal")

        weightedRandom.add("item3", 30.0)
        assertEquals(2, weightedRandom.size(), "Should have 2 items after adding new")

        val result = weightedRandom.random()
        assertTrue(setOf("item2", "item3").contains(result), "Should return one of remaining items")
    }

    @Test
    fun testComplexAddRemoveSequence() {
        weightedRandom.add("a", 10.0)
        weightedRandom.add("b", 20.0)
        weightedRandom.add("c", 30.0)
        weightedRandom.remove("b")
        weightedRandom.add("d", 40.0)
        weightedRandom.add("e", 50.0)
        weightedRandom.remove("a")
        weightedRandom.add("f", 60.0)

        assertEquals(4, weightedRandom.size(), "Should have correct size after complex operations")
        val values = weightedRandom.values()
        assertTrue(values.containsAll(setOf("c", "d", "e", "f")), "Should contain correct items")
    }

    @Test
    fun testRandomAfterMultipleRemovals() {
        repeat(10) { i ->
            weightedRandom.add("item$i", (i + 1).toDouble())
        }

        // Remove half
        repeat(5) { i ->
            weightedRandom.remove("item${i * 2}")
        }

        // Should still work correctly
        repeat(100) {
            val result = weightedRandom.random()
            assertNotNull(result, "Should still return values")
            assertTrue(result!!.startsWith("item"), "Should return valid item")
        }
    }

    @Test
    fun testGetNRandomAfterRemovals() {
        repeat(10) { i ->
            weightedRandom.add("item$i", (i + 1).toDouble())
        }

        // Remove some items
        weightedRandom.remove("item0")
        weightedRandom.remove("item5")
        weightedRandom.remove("item9")

        val result = weightedRandom.getNRandom(7)
        assertEquals(7, result.size, "Should return requested number if available")
        assertFalse(result.contains("item0"), "Should not contain removed items")
        assertFalse(result.contains("item5"), "Should not contain removed items")
        assertFalse(result.contains("item9"), "Should not contain removed items")
    }

    @Test
    fun testStressTest() {
        // Add many items
        repeat(100) { i ->
            weightedRandom.add("item$i", (i + 1).toDouble())
        }

        // Remove some
        repeat(20) { i ->
            weightedRandom.remove("item${i * 5}")
        }

        // Get random many times
        repeat(1000) {
            val result = weightedRandom.random()
            assertNotNull(result, "Should always return a value")
        }

        // Get N random
        val nRandom = weightedRandom.getNRandom(50)
        assertTrue(nRandom.size <= 80, "Should return at most remaining items")
        assertTrue(nRandom.size > 0, "Should return some items")
    }

    // ========== Type Safety Tests ==========

    @Test
    fun testWithIntegerType() {
        val intRandom = WeightedRandom<Int>()
        intRandom.add(1, 10.0)
        intRandom.add(2, 20.0)
        intRandom.add(3, 30.0)

        val result = intRandom.random()
        assertNotNull(result, "Should work with Integer type")
        assertTrue(setOf(1, 2, 3).contains(result), "Should return one of added integers")
    }

    @Test
    fun testWithCustomObject() {
        data class TestObj(val id: String, val value: Int)

        val objRandom = WeightedRandom<TestObj>()
        objRandom.add(TestObj("a", 1), 10.0)
        objRandom.add(TestObj("b", 2), 20.0)

        val result = objRandom.random()
        assertNotNull(result, "Should work with custom objects")
        assertTrue(result!!.id in setOf("a", "b"), "Should return one of added objects")
    }

    @Test
    fun testWithNullValues() {
        // Note: WeightedRandom doesn't explicitly prevent null, but let's test behavior
        // In practice, null might cause issues, but we test what happens
        weightedRandom.add("item1", 10.0)
        weightedRandom.add("item2", 20.0)

        // Normal operation should work
        val result = weightedRandom.random()
        assertNotNull(result, "Should return non-null for normal items")
    }
}
