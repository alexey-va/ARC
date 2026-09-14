package ru.arc.travelanchors

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.util.Common
import java.util.concurrent.TimeUnit

class TravelAnchorNetworkStoreTest : FunSpec({
    test("network snapshots are stored by server") {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val store = TravelAnchorNetworkStore(redis, "survival", Runnable::run, {}, { _, _ -> })
        val snapshot = TravelAnchorNetworkSnapshot(
            server = "survival",
            anchors = listOf(TravelAnchorNetworkEntry("survival", "world", 1, 64, 2, "GrocerMC", "Дом", true)),
        )

        store.publish(snapshot).get(2, TimeUnit.SECONDS)

        val stored = redis.getHash(TravelAnchorNetworkStore.ANCHORS_HASH).getValue("survival")
        Common.gson.fromJson(stored, TravelAnchorNetworkSnapshot::class.java) shouldBe snapshot
    }

    test("shared anchor snapshots are accepted without an owner") {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val store = TravelAnchorNetworkStore(redis, "survival", Runnable::run, {}, { _, _ -> })
        val snapshot = TravelAnchorNetworkSnapshot(
            server = "survival",
            anchors = listOf(TravelAnchorNetworkEntry("survival", "world", 4, 70, 9, "", "Общий", false, true)),
        )

        store.publish(snapshot).get(2, TimeUnit.SECONDS)

        Common.gson.fromJson(
            redis.getHash(TravelAnchorNetworkStore.ANCHORS_HASH).getValue("survival"),
            TravelAnchorNetworkSnapshot::class.java,
        ) shouldBe snapshot
    }

    test("public access grants merge instead of overwriting existing players") {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        redis.setHash(
            TravelAnchorNetworkStore.ACCESS_HASH,
            mapOf("grocermc" to Common.gson.toJson(mapOf("players" to setOf("friend")))),
        )
        val applied = mutableSetOf<String>()
        val store = TravelAnchorNetworkStore(redis, "survival", Runnable::run, {}, { _, players -> applied.addAll(players) })

        val result = store.grantAccess("GrocerMC", "Visitor").get(2, TimeUnit.SECONDS)

        result.shouldContainAll("friend", "visitor")
        applied.shouldContainAll("friend", "visitor")
    }
})
