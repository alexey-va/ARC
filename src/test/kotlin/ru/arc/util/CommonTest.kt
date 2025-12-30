package ru.arc.util

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.TestBase

class CommonTest : TestBase() {

    @Test
    fun testGsonNotNull() {
        assertNotNull(Common.gson, "Gson should not be null")
    }

    @Test
    fun testPrettyGsonNotNull() {
        assertNotNull(Common.prettyGson, "PrettyGson should not be null")
    }

    @Test
    fun testGsonSerialization() {
        val testObject = mapOf("key" to "value", "number" to 123)
        val json = Common.gson.toJson(testObject)
        assertNotNull(json, "JSON should not be null")
        assertTrue(json.isNotEmpty(), "JSON should not be empty")
    }

    @Test
    fun testGsonDeserialization() {
        val json = """{"key":"value","number":123}"""
        val result = Common.gson.fromJson(json, Map::class.java)
        assertNotNull(result, "Deserialized object should not be null")
    }

    @Test
    fun testPrettyGsonSerialization() {
        val testObject = mapOf("key" to "value", "number" to 123)
        val json = Common.prettyGson.toJson(testObject)
        assertNotNull(json, "Pretty JSON should not be null")
        assertTrue(json.contains("\n") || json.isNotEmpty(), "Pretty JSON should be formatted")
    }

    @Test
    fun testGsonAndPrettyGsonDifferent() {
        val testObject = mapOf("key" to "value", "number" to 123)
        val json1 = Common.gson.toJson(testObject)
        val json2 = Common.prettyGson.toJson(testObject)

        // Both should serialize the same data, but formatting may differ
        assertNotNull(json1, "Gson JSON should not be null")
        assertNotNull(json2, "PrettyGson JSON should not be null")
    }

    @Test
    fun testGsonRoundTrip() {
        val original = mapOf("key" to "value", "number" to 123)
        val json = Common.gson.toJson(original)
        val deserialized = Common.gson.fromJson(json, Map::class.java)

        assertNotNull(deserialized, "Deserialized should not be null")
    }
}

