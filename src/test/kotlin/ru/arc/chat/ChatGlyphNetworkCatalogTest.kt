package ru.arc.chat

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.util.Common

class ChatGlyphNetworkCatalogTest : FunSpec({
    test("spawn publishes bounded glyph metadata and parkour can read its supplementary glyph and permission") {
        val redis = InMemoryRedis()
        val now = 1_700_000_000_000L
        val publisher = ChatGlyphNetworkCatalog(redis) { now }
        val reader = ChatGlyphNetworkCatalog(redis) { now }
        val definitions = listOf(
            ChatGlyphDefinition(
                id = "crystal:star",
                unicode = String(Character.toChars(0xF0000)),
                permission = "premium.stars",
                technical = false,
            ),
        )

        publisher.publish(definitions).join()

        redis.getHash(CATALOG_HASH).keys shouldBe setOf("spawn")
        val envelope = JsonParser.parseString(redis.getHash(CATALOG_HASH).getValue("spawn")).asJsonObject
        envelope.get("schemaVersion").asInt shouldBe 1
        envelope.get("serverId").asString shouldBe "spawn"
        envelope.has("players") shouldBe false
        reader.current() shouldBe null

        reader.refresh().join()

        reader.current() shouldBe definitions
        (reader.current() === reader.current()) shouldBe true
        envelope.getAsJsonArray("definitions").single().asJsonObject.get("permission").asString shouldBe "premium.stars"
        publisher.close()
        reader.close()
    }

    test("empty snapshot clears the active glyph readiness") {
        val redis = InMemoryRedis()
        var now = 1_700_000_000_000L
        val publisher = ChatGlyphNetworkCatalog(redis) { now }
        val reader = ChatGlyphNetworkCatalog(redis) { now }
        val definition = ChatGlyphDefinition("crystal:star", "★", "premium.stars", technical = false)

        publisher.publish(listOf(definition)).join()
        reader.refresh().join()
        reader.current() shouldBe listOf(definition)

        now += 1L
        publisher.publish(emptyList()).join()
        reader.refresh().join()
        reader.current() shouldBe null

        publisher.close()
        reader.close()
    }

    test("malformed, foreign, stale, and too-future catalogs do not become active") {
        val now = 1_700_000_000_000L

        fun currentFrom(hashField: String, raw: String, clock: Long = now): List<ChatGlyphDefinition>? {
            val redis = InMemoryRedis().apply { setHash(CATALOG_HASH, mapOf(hashField to raw)) }
            val catalog = ChatGlyphNetworkCatalog(redis) { clock }
            catalog.refresh().join()
            return catalog.current().also { catalog.close() }
        }

        val validSpawn = catalogJson(serverId = "spawn", publishedAtMillis = now)
        currentFrom(
            "spawn",
            catalogJson(
                serverId = "spawn",
                publishedAtMillis = now,
                permissionValue = 7,
            ),
        ) shouldBe null
        currentFrom("survival", catalogJson(serverId = "survival", publishedAtMillis = now)) shouldBe null
        currentFrom("spawn", catalogJson(serverId = "spawn", publishedAtMillis = now - LEASE_MILLIS)) shouldBe null
        currentFrom("spawn", catalogJson(serverId = "spawn", publishedAtMillis = now + FUTURE_SKEW_MILLIS + 1L)) shouldBe null
        currentFrom("spawn", "{malformed") shouldBe null
        currentFrom("spawn", validSpawn.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) shouldBe null
    }

    test("closed catalog fences reads and writes") {
        val catalog = ChatGlyphNetworkCatalog(InMemoryRedis()) { 1_700_000_000_000L }
        catalog.close()
        catalog.close()

        catalog.current() shouldBe null
        runCatching { catalog.refresh().join() }.isFailure shouldBe true
        runCatching { catalog.publish(emptyList()).join() }.isFailure shouldBe true
    }

    test("multi-scalar definitions fail before being written") {
        val redis = InMemoryRedis()
        val catalog = ChatGlyphNetworkCatalog(redis) { 1_700_000_000_000L }

        runCatching {
            catalog.publish(listOf(ChatGlyphDefinition("crystal:star", "ab", null, technical = false))).join()
        }.isFailure shouldBe true
        redis.getHash(CATALOG_HASH) shouldBe emptyMap()

        catalog.close()
    }
})

private fun catalogJson(
    serverId: String,
    publishedAtMillis: Long,
    permissionValue: Any = "premium.stars",
): String = Common.gson.toJson(
    mapOf(
        "schemaVersion" to 1,
        "serverId" to serverId,
        "publishedAtMillis" to publishedAtMillis,
        "definitions" to listOf(
            mapOf(
                "id" to "crystal:star",
                "unicode" to "★",
                "permission" to permissionValue,
                "technical" to false,
            ),
        ),
    ),
)

private const val CATALOG_HASH = "arc:chat-glyphs:v1:catalog"
private const val LEASE_MILLIS = 120_000L
private const val FUTURE_SKEW_MILLIS = 10_000L
