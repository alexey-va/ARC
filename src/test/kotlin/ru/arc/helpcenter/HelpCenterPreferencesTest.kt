package ru.arc.helpcenter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.google.gson.Gson
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

class HelpCenterPreferencesTest {
    @Test
    fun `favorites are unique ordered and capped at four`() {
        var value = HelpCenterPreferences.empty()
        listOf("jobs", "skills", "auction", "rtp", "events").forEach { value = value.toggleFavorite(it) }

        assertEquals(listOf("jobs", "skills", "auction", "rtp"), value.favorites)
        value = value.toggleFavorite("auction")
        assertFalse("auction" in value.favorites)
    }

    @Test
    fun `recent actions are unique newest first and capped at six`() {
        var value = HelpCenterPreferences.empty()
        listOf("jobs", "skills", "auction", "rtp", "events", "vote", "jobs").forEach { value = value.recordRecent(it) }

        assertEquals(listOf("jobs", "vote", "events", "rtp", "auction", "skills"), value.recent)
    }

    @Test
    fun `wire validation rejects command strings and unbounded entries`() {
        assertThrows(IllegalArgumentException::class.java) {
            HelpCenterPreferences(listOf("pay Foll 500"), emptyList()).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HelpCenterPreferences(List(5) { "action$it" }, emptyList()).validated()
        }
        assertTrue(HelpCenterPreferences(listOf("battle-pass"), listOf("chat-global")).validated().favorites.isNotEmpty())
    }

    @Test
    fun `redis store round trips favorites and recents through bounded CAS`() {
        val redis = MapRedisOperations()
        val store = RedisHelpCenterPreferenceStore(redis, Gson())
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000123")

        assertEquals(HelpCenterPreferences.empty(), store.load(playerId).join())
        store.toggleFavorite(playerId, "jobs").join()
        store.recordRecent(playerId, "rtp").join()

        assertEquals(HelpCenterPreferences(listOf("jobs"), listOf("rtp")), store.load(playerId).join())
    }
}

private class MapRedisOperations : RedisOperations {
    private val hashes = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> =
        CompletableFuture.completedFuture(mapKeys.map { mapKey -> hashes[key]?.get(mapKey) })

    override fun compareAndSetMapEntry(
        key: String,
        mapKey: String,
        expectedValue: String?,
        replacementValue: String?,
    ): CompletableFuture<Boolean> {
        val values = hashes.computeIfAbsent(key) { ConcurrentHashMap() }
        synchronized(values) {
            if (values[mapKey] != expectedValue) return CompletableFuture.completedFuture(false)
            if (replacementValue == null) values.remove(mapKey) else values[mapKey] = replacementValue
            return CompletableFuture.completedFuture(true)
        }
    }

    override fun publish(channel: String, message: String) = Unit
    override fun saveMap(key: String, map: Map<String, String>) { hashes[key] = ConcurrentHashMap(map) }
    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> =
        CompletableFuture.completedFuture(null)
    override fun loadMap(key: String): CompletableFuture<Map<String, String>> =
        CompletableFuture.completedFuture(hashes[key]?.toMap().orEmpty())
    override fun registerChannelUnique(channel: String, listener: ChannelListener) = Unit
    override fun unregisterChannel(channel: String, listener: ChannelListener) = Unit
    override fun init() = Unit
    override fun close() = Unit
}
