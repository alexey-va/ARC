package ru.arc.hooks.elitemobs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class DungeonBestiaryProgressTest : FreeSpec({
    "unlocks each boss once and persists isolated player hashes across store instances" {
        val redis = InMemoryRedis()
        val player = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val otherPlayer = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val store = DungeonBestiaryProgressStore(redis)

        store.discover(player, "crypt_keeper.yml").join() shouldBe true
        store.discover(player, "crypt_keeper.yml").join() shouldBe false
        store.discover(player, "bone_matriarch.yml").join() shouldBe true

        val freshStore = DungeonBestiaryProgressStore(redis)
        freshStore.load(player).join() shouldBe setOf("crypt_keeper.yml", "bone_matriarch.yml")
        freshStore.load(otherPlayer).join() shouldBe emptySet()
        redis.getHash("arc.dungeon_bestiary.$player") shouldBe mapOf(
            "crypt_keeper.yml" to "1",
            "bone_matriarch.yml" to "1",
        )
    }

    "discovery waits for Redis and returns the atomic first-write result" {
        val player = UUID.randomUUID()
        val redis = mockk<RedisOperations>()
        val stored = CompletableFuture<Boolean>()
        every {
            redis.compareAndSetMapEntry("arc.dungeon_bestiary.$player", "hydra.yml", null, "1")
        } returns stored

        val result = DungeonBestiaryProgressStore(redis).discover(player, "hydra.yml")
        result.isDone shouldBe false

        stored.complete(true)
        result.join() shouldBe true
        verify(exactly = 1) {
            redis.compareAndSetMapEntry("arc.dungeon_bestiary.$player", "hydra.yml", null, "1")
        }
    }

    "ignores non-canonical hash fields but fails on corrupt progress for a canonical filename" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val key = "arc.dungeon_bestiary.$player"
        redis.setHash(key, mapOf("crypt_keeper.yml" to "1", "old-field" to "1"))
        DungeonBestiaryProgressStore(redis).load(player).join() shouldBe setOf("crypt_keeper.yml")

        redis.setHash(key, mapOf("crypt_keeper.yml" to "0"))
        shouldThrow<CompletionException> { DungeonBestiaryProgressStore(redis).load(player).join() }
        shouldThrow<CompletionException> { DungeonBestiaryProgressStore(redis).discover(player, "crypt_keeper.yml").join() }
        redis.getHash(key) shouldBe mapOf("crypt_keeper.yml" to "0")
    }

    "rejects malformed filenames before touching Redis" {
        val redis = mockk<RedisOperations>()
        shouldThrow<IllegalArgumentException> {
            DungeonBestiaryProgressStore(redis).discover(UUID.randomUUID(), "../hydra.yml")
        }
        verify { redis wasNot Called }
    }

    "propagates Redis failures instead of reporting an unlock" {
        val redis = mockk<RedisOperations>()
        val failure = IllegalStateException("Redis unavailable")
        every {
            redis.compareAndSetMapEntry(any(), "hydra.yml", null, "1")
        } returns CompletableFuture.failedFuture(failure)

        val result = DungeonBestiaryProgressStore(redis).discover(UUID.randomUUID(), "hydra.yml")
        result.isCompletedExceptionally shouldBe true
        shouldThrow<CompletionException> { result.join() }.cause shouldBe failure
    }

    "rejects oversized progress hashes" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        redis.setHash(
            "arc.dungeon_bestiary.$player",
            (0..20_000).associate { "boss-$it.yml" to "1" },
        )

        shouldThrow<CompletionException> { DungeonBestiaryProgressStore(redis).load(player).join() }
    }
})
