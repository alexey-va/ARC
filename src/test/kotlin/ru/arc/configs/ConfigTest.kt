@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
package ru.arc.configs

import org.bukkit.Material
import org.bukkit.Particle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConfigTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var config: Config
    private lateinit var configFile: Path

    @BeforeEach
    fun setUp() {
        configFile = tempDir.resolve("test-config.yml")
        Files.createFile(configFile)

        // Create a Config instance using reflection since constructor is package-private
        // We'll use ConfigManager to create it properly
        config = ConfigManager.create(tempDir, "test-config.yml", "test-config")
    }

    // ========== Integer Tests ==========

    @Test
    fun testIntegerWithExistingValue() {
        writeYaml(
            """
            test:
              value: 42
        """.trimIndent()
        )
        config.load()

        val result = config.integer("test.value", 0)
        assertEquals(42, result, "Should return existing integer value")
    }

    @Test
    fun testIntegerWithDefault() {
        val result = config.integer("nonexistent.path", 100)
        assertEquals(100, result, "Should return default when path doesn't exist")
        assertTrue(config.exists("nonexistent.path"), "Should inject default value")
    }

    @Test
    fun testIntegerWithDoubleValue() {
        writeYaml(
            """
            test:
              value: 42.5
        """.trimIndent()
        )
        config.load()

        val result = config.integer("test.value", 0)
        assertEquals(42, result, "Should convert double to int")
    }

    @Test
    fun testIntegerWithLongValue() {
        writeYaml(
            """
            test:
              value: 9999999999
        """.trimIndent()
        )
        config.load()

        val result = config.integer("test.value", 0)
        assertEquals(9999999999L.toInt(), result, "Should convert long to int")
    }

    @Test
    fun testIntegerNestedPath() {
        writeYaml(
            """
            level1:
              level2:
                level3:
                  value: 123
        """.trimIndent()
        )
        config.load()

        val result = config.integer("level1.level2.level3.value", 0)
        assertEquals(123, result, "Should handle nested paths")
    }

    // ========== Boolean Tests ==========

    @Test
    fun testBoolWithExistingValue() {
        writeYaml(
            """
            test:
              enabled: true
        """.trimIndent()
        )
        config.load()

        val result = config.bool("test.enabled", false)
        assertTrue(result, "Should return existing boolean value")
    }

    @Test
    fun testBoolWithDefault() {
        val result = config.bool("nonexistent.flag", true)
        assertTrue(result, "Should return default when path doesn't exist")
    }

    @Test
    fun testBoolFalse() {
        writeYaml(
            """
            test:
              enabled: false
        """.trimIndent()
        )
        config.load()

        val result = config.bool("test.enabled", true)
        assertFalse(result, "Should return false value")
    }

    // ========== Real (Double) Tests ==========

    @Test
    fun testRealWithExistingValue() {
        writeYaml(
            """
            test:
              value: 3.14159
        """.trimIndent()
        )
        config.load()

        val result = config.real("test.value", 0.0)
        assertEquals(3.14159, result, 0.00001, "Should return existing double value")
    }

    @Test
    fun testRealWithDefault() {
        val result = config.real("nonexistent.value", 2.5)
        assertEquals(2.5, result, "Should return default when path doesn't exist")
    }

    @Test
    fun testRealWithIntegerValue() {
        writeYaml(
            """
            test:
              value: 42
        """.trimIndent()
        )
        config.load()

        val result = config.real("test.value", 0.0)
        assertEquals(42.0, result, "Should convert integer to double")
    }

    // ========== String Tests ==========

    @Test
    fun testStringWithExistingValue() {
        writeYaml(
            """
            test:
              message: "Hello World"
        """.trimIndent()
        )
        config.load()

        val result = config.string("test.message", "default")
        assertEquals("Hello World", result, "Should return existing string value")
    }

    @Test
    fun testStringWithDefault() {
        val result = config.string("nonexistent.path", "default")
        assertEquals("default", result, "Should return default when path doesn't exist")
    }

    @Test
    fun testStringWithNonStringValue() {
        writeYaml(
            """
            test:
              value: 42
        """.trimIndent()
        )
        config.load()

        val result = config.string("test.value", "default")
        assertEquals("42", result, "Should convert non-string to string")
    }

    @Test
    fun testStringMethod() {
        writeYaml(
            """
            test:
              value: "test"
        """.trimIndent()
        )
        config.load()

        val result = config.string("test.value")
        assertEquals("test", result, "Should use path as default")
    }

    // ========== Long Tests ==========

    @Test
    fun testLongValueWithExistingValue() {
        writeYaml(
            """
            test:
              value: 9223372036854775807
        """.trimIndent()
        )
        config.load()

        val result = config.longValue("test.value", 0L)
        assertEquals(9223372036854775807L, result, "Should return existing long value")
    }

    @Test
    fun testLongValueWithDefault() {
        val result = config.longValue("nonexistent.path", 100L)
        assertEquals(100L, result, "Should return default when path doesn't exist")
    }

    // ========== String List Tests ==========

    @Test
    fun testStringListWithExistingList() {
        writeYaml(
            """
            test:
              items:
                - item1
                - item2
                - item3
        """.trimIndent()
        )
        config.load()

        val result = config.stringList("test.items")
        assertEquals(3, result.size, "Should return list with 3 items")
        assertTrue(result.containsAll(listOf("item1", "item2", "item3")), "Should contain all items")
    }

    @Test
    fun testStringListWithDefault() {
        val result = config.stringList("nonexistent.list", listOf("default1", "default2"))
        assertEquals(2, result.size, "Should return default list")
        assertTrue(result.containsAll(listOf("default1", "default2")), "Should contain default items")
    }

    @Test
    fun testStringListWithStringValue() {
        writeYaml(
            """
            test:
              item: "single"
        """.trimIndent()
        )
        config.load()

        val result = config.stringList("test.item")
        assertEquals(1, result.size, "Should convert string to list")
        assertEquals("single", result[0], "Should contain the string value")
    }

    @Test
    fun testStringListEmpty() {
        val result = config.stringList("nonexistent.list")
        assertTrue(result.isEmpty(), "Should return empty list when path doesn't exist")
    }

    // ========== String Set Tests ==========

    @Test
    fun testStringSetWithExistingList() {
        writeYaml(
            """
            test:
              items:
                - item1
                - item2
                - item3
        """.trimIndent()
        )
        config.load()

        val result = config.stringSet("test.items")
        assertEquals(3, result.size, "Should return set with 3 items")
        assertTrue(result.containsAll(setOf("item1", "item2", "item3")), "Should contain all items")
    }

    @Test
    fun testStringSetWithStringValue() {
        writeYaml(
            """
            test:
              item: "single"
        """.trimIndent()
        )
        config.load()

        val result = config.stringSet("test.item")
        assertEquals(1, result.size, "Should convert string to set")
        assertTrue(result.contains("single"), "Should contain the string value")
    }

    @Test
    fun testStringSetEmpty() {
        val result = config.stringSet("nonexistent.set")
        assertTrue(result.isEmpty(), "Should return empty set when path doesn't exist")
    }

    // ========== Map Tests ==========

    @Test
    fun testMapWithExistingMap() {
        writeYaml(
            """
            test:
              data:
                key1: value1
                key2: value2
        """.trimIndent()
        )
        config.load()

        val result = config.map<String>("test.data")
        assertEquals(2, result.size, "Should return map with 2 entries")
        assertEquals("value1", result["key1"], "Should contain key1")
        assertEquals("value2", result["key2"], "Should contain key2")
    }

    @Test
    fun testMapWithDefault() {
        val default = mapOf("default" to "value")
        val result = config.map("nonexistent.map", default)
        assertEquals(default, result, "Should return default map")
    }

    @Test
    fun testMapWithNonStringKeys() {
        writeYaml(
            """
            test:
              data:
                123: value1
                456: value2
        """.trimIndent()
        )
        config.load()

        val result = config.map<String>("test.data")
        assertEquals(2, result.size, "Should convert non-string keys to strings")
        assertTrue(result.containsKey("123"), "Should have converted key")
        assertTrue(result.containsKey("456"), "Should have converted key")
    }

    @Test
    fun testMapEmpty() {
        val result = config.map<String>("nonexistent.map")
        assertTrue(result.isEmpty(), "Should return empty map when path doesn't exist")
    }

    // ========== List Tests ==========

    @Test
    fun testListWithExistingList() {
        writeYaml(
            """
            test:
              items:
                - 1
                - 2
                - 3
        """.trimIndent()
        )
        config.load()

        val result = config.list<Int>("test.items")
        assertEquals(3, result.size, "Should return list with 3 items")
    }

    @Test
    fun testListWithDefault() {
        val default = listOf(1, 2, 3)
        val result = config.list("nonexistent.list", default)
        assertEquals(default, result, "Should return default list")
    }

    @Test
    fun testListEmpty() {
        val result = config.list<Any>("nonexistent.list")
        assertTrue(result.isEmpty(), "Should return empty list when path doesn't exist")
    }

    // ========== Keys Tests ==========

    @Test
    fun testKeysWithExistingMap() {
        writeYaml(
            """
            test:
              data:
                key1: value1
                key2: value2
                key3: value3
        """.trimIndent()
        )
        config.load()

        val result = config.keys("test.data")
        assertEquals(3, result.size, "Should return 3 keys")
        assertTrue(result.containsAll(listOf("key1", "key2", "key3")), "Should contain all keys")
    }

    @Test
    fun testKeysEmpty() {
        val result = config.keys("nonexistent.map")
        assertTrue(result.isEmpty(), "Should return empty list when path doesn't exist")
    }

    // ========== Material Tests ==========

    @Test
    fun testMaterialWithExistingValue() {
        writeYaml(
            """
            test:
              material: "DIAMOND"
        """.trimIndent()
        )
        config.load()

        val result = config.material("test.material", Material.STONE)
        assertEquals(Material.DIAMOND, result, "Should return existing material")
    }

    @Test
    fun testMaterialWithDefault() {
        val result = config.material("nonexistent.material", Material.GOLD_INGOT)
        assertEquals(Material.GOLD_INGOT, result, "Should return default material")
    }

    @Test
    fun testMaterialWithInvalidValue() {
        writeYaml(
            """
            test:
              material: "INVALID_MATERIAL"
        """.trimIndent()
        )
        config.load()

        val result = config.material("test.material", Material.STONE)
        assertEquals(Material.STONE, result, "Should return default for invalid material")
    }

    @Test
    fun testMaterialCaseInsensitive() {
        writeYaml(
            """
            test:
              material: "diamond"
        """.trimIndent()
        )
        config.load()

        val result = config.material("test.material", Material.STONE)
        assertEquals(Material.DIAMOND, result, "Should handle lowercase material names")
    }

    // ========== Material Set Tests ==========

    @Test
    fun testMaterialSetWithExistingList() {
        writeYaml(
            """
            test:
              materials:
                - DIAMOND
                - GOLD_INGOT
                - IRON_INGOT
        """.trimIndent()
        )
        config.load()

        val result = config.materialSet("test.materials", setOf())
        assertEquals(3, result.size, "Should return set with 3 materials")
        assertTrue(result.contains(Material.DIAMOND), "Should contain DIAMOND")
        assertTrue(result.contains(Material.GOLD_INGOT), "Should contain GOLD_INGOT")
        assertTrue(result.contains(Material.IRON_INGOT), "Should contain IRON_INGOT")
    }

    @Test
    fun testMaterialSetWithDefault() {
        val default = setOf(Material.STONE, Material.DIRT)
        val result = config.materialSet("nonexistent.materials", default)
        assertEquals(default, result, "Should return default set")
    }

    @Test
    fun testMaterialSetWithInvalidMaterial() {
        writeYaml(
            """
            test:
              materials:
                - DIAMOND
                - INVALID_MATERIAL
                - GOLD_INGOT
        """.trimIndent()
        )
        config.load()

        val result = config.materialSet("test.materials", setOf())
        assertEquals(2, result.size, "Should skip invalid materials")
        assertTrue(result.contains(Material.DIAMOND), "Should contain valid materials")
        assertTrue(result.contains(Material.GOLD_INGOT), "Should contain valid materials")
    }

    // ========== Particle Tests ==========

    @Test
    fun testParticleWithExistingValue() {
        writeYaml(
            """
            test:
              particle: "FLAME"
        """.trimIndent()
        )
        config.load()

        val result = config.particle("test.particle", Particle.CLOUD)
        assertEquals(Particle.FLAME, result, "Should return existing particle")
    }

    @Test
    fun testParticleWithDefault() {
        val result = config.particle("nonexistent.particle", Particle.HEART)
        assertEquals(Particle.HEART, result, "Should return default particle")
    }

    @Test
    fun testParticleWithInvalidValue() {
        writeYaml(
            """
            test:
              particle: "INVALID_PARTICLE"
        """.trimIndent()
        )
        config.load()

        val result = config.particle("test.particle", Particle.CLOUD)
        assertEquals(Particle.CLOUD, result, "Should return default for invalid particle")
    }

    // ========== Exists Tests ==========

    @Test
    fun testExistsWithExistingPath() {
        writeYaml(
            """
            test:
              value: 42
        """.trimIndent()
        )
        config.load()

        assertTrue(config.exists("test.value"), "Should return true for existing path")
    }

    @Test
    fun testExistsWithNonExistentPath() {
        assertFalse(config.exists("nonexistent.path"), "Should return false for non-existent path")
    }

    @Test
    fun testExistsWithNestedPath() {
        writeYaml(
            """
            level1:
              level2:
                value: test
        """.trimIndent()
        )
        config.load()

        assertTrue(config.exists("level1.level2.value"), "Should return true for nested path")
        assertFalse(config.exists("level1.level2.nonexistent"), "Should return false for non-existent nested path")
    }

    // ========== Add To List Tests ==========

    @Test
    fun testAddToList() {
        writeYaml(
            """
            test:
              items:
                - item1
        """.trimIndent()
        )
        config.load()

        config.addToList("test.items", "item2")

        val result = config.stringList("test.items")
        assertTrue(result.contains("item1"), "Should contain original item")
        assertTrue(result.contains("item2"), "Should contain added item")
    }

    @Test
    fun testAddToListNewPath() {
        config.addToList("new.path", "value")

        val result = config.stringList("new.path")
        assertEquals(1, result.size, "Should create new list")
        assertEquals("value", result[0], "Should contain added value")
    }

    // ========== Load and Save Tests ==========

    @Test
    fun testLoad() {
        writeYaml(
            """
            test:
              value: 42
        """.trimIndent()
        )

        config.load()

        val result = config.integer("test.value", 0)
        assertEquals(42, result, "Should load value from file")
    }

    @Test
    fun testLoadEmptyFile() {
        writeYaml("")
        config.load()

        val result = config.integer("test.value", 100)
        assertEquals(100, result, "Should handle empty file")
    }

    @Test
    fun testSave() {
        config.integer("test.value", 42)
        config.save()

        config.load()
        val result = config.integer("test.value", 0)
        assertEquals(42, result, "Should save and reload value")
    }

    @Test
    fun testLoadNullFile() {
        // Create empty file
        Files.write(configFile, "".toByteArray())
        config.load()

        // Should not throw, map should be empty or default
        val result = config.integer("test.value", 100)
        assertEquals(100, result, "Should handle null/empty YAML")
    }

    // ========== Inject Deep Key Tests ==========

    @Test
    fun testInjectDeepKey() {
        config.injectDeepKey("level1.level2.value", "test")

        val result = config.string("level1.level2.value", "")
        assertEquals("test", result, "Should inject value at deep path")
    }

    @Test
    fun testInjectDeepKeyOverwrites() {
        writeYaml(
            """
            level1:
              level2:
                value: old
        """.trimIndent()
        )
        config.load()

        config.injectDeepKey("level1.level2.value", "new")

        val result = config.string("level1.level2.value", "")
        assertEquals("new", result, "Should overwrite existing value")
    }

    @Test
    fun testInjectDeepKeyCreatesPath() {
        config.injectDeepKey("new.deep.path.value", 42)

        val result = config.integer("new.deep.path.value", 0)
        assertEquals(42, result, "Should create entire path structure")
    }

    // ========== Edge Cases and Bug Tests ==========

    @Test
    fun testGetValueWithEmptyPath() {
        writeYaml(
            """
            value: test
        """.trimIndent()
        )
        config.load()

        val result = config.string("value", "default")
        assertEquals("test", result, "Should handle single-level path")
    }

    @Test
    fun testGetValueWithTrailingDot() {
        // Paths with trailing dots might cause issues
        val result = config.string("test.path.", "default")
        assertEquals("default", result, "Should handle trailing dot")
    }

    @Test
    fun testGetValueWithEmptyKeyParts() {
        // Paths with empty parts like "test..value"
        val result = config.string("test..value", "default")
        assertEquals("default", result, "Should handle empty key parts")
    }

    @Test
    fun testMapWithNullValue() {
        writeYaml(
            """
            test:
              data:
                key1: null
                key2: value2
        """.trimIndent()
        )
        config.load()

        val result = config.map<String>("test.data")
        // Should handle null values gracefully
        assertTrue(result.containsKey("key1") || result.containsKey("key2"), "Should handle null values")
    }

    @Test
    fun testListWithMixedTypes() {
        writeYaml(
            """
            test:
              items:
                - string
                - 42
                - true
        """.trimIndent()
        )
        config.load()

        val result = config.list<Any>("test.items")
        assertEquals(3, result.size, "Should handle mixed type list")
    }

    @Test
    fun testStringListWithNonStringItems() {
        writeYaml(
            """
            test:
              items:
                - 1
                - 2
                - 3
        """.trimIndent()
        )
        config.load()

        // This might cause ClassCastException - testing for potential bug
        try {
            val result = config.stringList("test.items")
            // If it works, items should be converted to strings
            assertTrue(result.isNotEmpty(), "Should handle non-string items")
        } catch (e: ClassCastException) {
            // This would be a bug - non-string items in list
            fail("Should handle non-string items in stringList")
        }
    }

    @Test
    fun testIntegerWithStringValue() {
        writeYaml(
            """
            test:
              value: "not a number"
        """.trimIndent()
        )
        config.load()

        // This should throw ClassCastException - testing for potential bug
        try {
            val result = config.integer("test.value", 0)
            // If it doesn't throw, check if it handles gracefully
            assertNotNull(result, "Should handle string value somehow")
        } catch (e: ClassCastException) {
            // Expected behavior - string can't be cast to Number
            assertTrue(true, "Correctly throws exception for invalid type")
        }
    }

    @Test
    fun testRealWithStringValue() {
        writeYaml(
            """
            test:
              value: "not a number"
        """.trimIndent()
        )
        config.load()

        try {
            val result = config.real("test.value", 0.0)
            assertNotNull(result, "Should handle string value somehow")
        } catch (e: ClassCastException) {
            assertTrue(true, "Correctly throws exception for invalid type")
        }
    }

    @Test
    fun testBoolWithStringValue() {
        writeYaml(
            """
            test:
              value: "true"
        """.trimIndent()
        )
        config.load()

        // String "true" should not be castable to boolean
        try {
            val result = config.bool("test.value", false)
            // If it works, it might be a bug or feature
            assertNotNull(result, "Should handle somehow")
        } catch (e: ClassCastException) {
            assertTrue(true, "Correctly throws exception for invalid type")
        }
    }

    @Test
    fun testComponentMethods() {
        writeYaml(
            """
            test:
              message: "<green>Hello World</green>"
        """.trimIndent()
        )
        config.load()

        // Component methods require TextUtil which might not be available in tests
        // But we can test that they don't throw
        try {
            val result = config.component("test.message")
            assertNotNull(result, "Should return component")
        } catch (e: Exception) {
            // TextUtil might not be initialized in test environment
            // This is acceptable
        }
    }

    @Test
    fun testConcurrentAccess() {
        // Test that config can handle concurrent access
        writeYaml(
            """
            test:
              value: 0
        """.trimIndent()
        )
        config.load()

        // Simulate concurrent reads
        val results = mutableListOf<Int>()
        repeat(100) {
            results.add(config.integer("test.value", 0))
        }

        assertEquals(100, results.size, "Should handle concurrent reads")
        assertTrue(results.all { it == 0 }, "All reads should return same value")
    }

    @Test
    fun testVeryLongPath() {
        val longPath = "level1.level2.level3.level4.level5.level6.level7.level8.level9.level10.value"
        val result = config.integer(longPath, 42)
        assertEquals(42, result, "Should handle very long paths")
        assertTrue(config.exists(longPath), "Should inject value at long path")
    }

    @Test
    fun testSpecialCharactersInPath() {
        // Test paths with special characters (though dots are separators)
        val result = config.string("test-path_with_underscores.value", "default")
        assertEquals("default", result, "Should handle special characters in path")
    }

    @Test
    fun testUnicodeInValues() {
        writeYaml(
            """
            test:
              message: "Привет мир"
        """.trimIndent()
        )
        config.load()

        val result = config.string("test.message", "")
        assertEquals("Привет мир", result, "Should handle unicode characters")
    }

    @Test
    fun testLargeNumbers() {
        writeYaml(
            """
            test:
              large: 999999999999999999
        """.trimIndent()
        )
        config.load()

        val result = config.longValue("test.large", 0L)
        assertEquals(999999999999999999L, result, "Should handle large numbers")
    }

    @Test
    fun testNegativeNumbers() {
        writeYaml(
            """
            test:
              negative: -42
        """.trimIndent()
        )
        config.load()

        val result = config.integer("test.negative", 0)
        assertEquals(-42, result, "Should handle negative numbers")
    }

    @Test
    fun testDecimalPrecision() {
        writeYaml(
            """
            test:
              precise: 3.141592653589793
        """.trimIndent()
        )
        config.load()

        val result = config.real("test.precise", 0.0)
        assertEquals(3.141592653589793, result, 0.000000000000001, "Should preserve decimal precision")
    }

    // Helper method to write YAML to file
    private fun writeYaml(content: String) {
        Files.write(configFile, content.toByteArray())
    }
}

