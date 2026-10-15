package ru.arc.graves

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.redis.network.RedisRequestTimeoutHandle
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import java.util.UUID
import java.util.concurrent.TimeUnit

class GravesNetworkTest : FreeSpec({
    val playerId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    val graveId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    val otherGraveId = UUID.fromString("33333333-3333-4333-8333-333333333333")
    val noTimeout = RedisRequestTimeoutScheduler { _, _ -> RedisRequestTimeoutHandle {} }

    "publishes complete snapshots and exposes only fresh remote graves for the owner" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        GravesNetwork(redis, "spawn", { _, _, _ -> false }, nowMillis = { 10_000L }, scheduler = TestTaskScheduler())
            .use { network ->
                network.publish(listOf(grave(graveId, playerId, "spawn"))).get(2, TimeUnit.SECONDS)
                redis.saveMap(
                    GravesNetworkWire.PRESENCE_HASH,
                    mapOf(
                        "survival" to GravesNetworkWire.snapshotCodec.encode(
                            GraveServerSnapshot(
                                serverId = "survival",
                                publishedAtMillis = 10_000L,
                                sequence = 1L,
                                graves = listOf(grave(otherGraveId, playerId, "survival").toNetwork()),
                            ),
                        ),
                    ),
                )

                network.refresh().get(2, TimeUnit.SECONDS)

                network.views(playerId).map(GraveView::id) shouldContainExactly listOf(otherGraveId)
                network.knownServers() shouldBe setOf("spawn", "survival")

                network.publish(emptyList()).get(2, TimeUnit.SECONDS)
                GravesNetworkWire.snapshotCodec.decode(
                    redis.getHash(GravesNetworkWire.PRESENCE_HASH).getValue("spawn"),
                ).graves shouldBe emptyList()
            }
    }

    "publishes full GravesX inventories and preserves styled Russian names beyond the old 512-summary cap" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val scheduler = TestTaskScheduler()
        GravesNetwork(redis, "spawn", { _, _, _ -> false }, nowMillis = { 10_000L }, scheduler = scheduler)
            .use { network ->
                val styledRussianName = """{"text":"Зачарованный меч с древним узором","color":"gold","italic":false}"""
                val graves = (0 until 512).map { graveIndex ->
                    val id = UUID.nameUUIDFromBytes("grave-$graveIndex".toByteArray())
                    grave(id, playerId, "spawn").copy(
                        items = List(54) {
                            GraveItem(styledRussianName, amount = 64)
                        },
                    )
                }

                network.publish(graves).get(2, TimeUnit.SECONDS)

                val snapshot = GravesNetworkWire.snapshotCodec.decode(
                    redis.getHash(GravesNetworkWire.PRESENCE_HASH).getValue("spawn"),
                )
                snapshot.graves.size shouldBe 512
                snapshot.graves.sumOf { it.items.size } shouldBe 27_648
                snapshot.graves.first().items.first().nameJson shouldBe styledRussianName
            }
    }

    "keeps the grave discoverable when one item has oversized display data or stack count" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        GravesNetwork(
            redis = redis,
            localServer = "spawn",
            acceptTransfer = { _, _, _ -> false },
            nowMillis = { 10_000L },
            scheduler = TestTaskScheduler(),
        ).use { network ->
            val id = UUID.nameUUIDFromBytes("grave-with-oversized-item".toByteArray())
            val view = grave(id, playerId, "spawn").copy(
                items = listOf(GraveItem("{\"text\":\"${"x".repeat(10_000)}\"}", Int.MAX_VALUE)),
            )

            network.publish(listOf(view)).get(2, TimeUnit.SECONDS)

            val published = GravesNetworkWire.snapshotCodec.decode(
                redis.getHash(GravesNetworkWire.PRESENCE_HASH).getValue("spawn"),
            ).graves.single()
            published.id shouldBe id.toString()
            published.items.single().nameJson shouldBe "{\"text\":\"Предмет\"}"
            published.items.single().amount shouldBe Int.MAX_VALUE
        }
    }

    "prepares a fresh inbound transfer on the server scheduler and replies with its exact correlation" {
        val scheduler = TestTaskScheduler()
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        var accepted = false
        GravesNetwork(
            redis = redis,
            localServer = "survival",
            acceptTransfer = { actualPlayer, actualGrave, quote ->
                actualPlayer shouldBe playerId
                actualGrave shouldBe graveId
                quote shouldBe 12.5
                accepted = true
                true
            },
            nowMillis = { 20_000L },
            scheduler = scheduler,
            timeoutScheduler = noTimeout,
        ).use {
            val request = travelRequest(createdAtMillis = 20_000L)
            redis.simulateExternalMessage(
                GravesNetworkWire.TRAVEL_CHANNEL,
                GravesNetworkWire.travelCodec.encode(request),
                originServer = "spawn",
            )

            accepted shouldBe false
            scheduler.executeImmediate()
            accepted shouldBe true
            redis.getPublishedMessages() shouldBe emptyList()

            scheduler.executeImmediate()
            val reply = GravesNetworkWire.travelCodec.decode(redis.getPublishedMessages().single().message)
            reply.type shouldBe "PREPARED"
            reply.replyTo shouldBe request.id
            reply.playerId shouldBe request.playerId
            reply.graveId shouldBe request.graveId
            reply.originServer shouldBe "survival"
            reply.targetServer shouldBe "spawn"
            reply.quotedPrice shouldBe request.quotedPrice
            reply.createdAtMillis shouldBe request.createdAtMillis
        }
    }

    "rejects expired inbound preparation and ignores replies with mismatched grave identity" {
        val scheduler = TestTaskScheduler()
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        var accepted = false
        GravesNetwork(
            redis = redis,
            localServer = "survival",
            acceptTransfer = { _, _, _ -> accepted = true; true },
            nowMillis = { 40_001L },
            scheduler = scheduler,
            timeoutScheduler = noTimeout,
        ).use {
            redis.simulateExternalMessage(
                GravesNetworkWire.TRAVEL_CHANNEL,
                GravesNetworkWire.travelCodec.encode(travelRequest(createdAtMillis = 30_000L)),
                originServer = "spawn",
            )
            scheduler.executeImmediate()
            accepted shouldBe false
            scheduler.executeImmediate()
            redis.getPublishedMessages() shouldBe emptyList()
        }

        val sourceScheduler = TestTaskScheduler()
        val sourceRedis = InMemoryRedis(ServerIdentity { "spawn" })
        GravesNetwork(
            redis = sourceRedis,
            localServer = "spawn",
            acceptTransfer = { _, _, _ -> false },
            nowMillis = { 50_000L },
            scheduler = sourceScheduler,
            timeoutScheduler = noTimeout,
        ).use {
            val prepared = it.prepare(playerId, graveId, "survival", 12.5)
            sourceScheduler.executeImmediate()
            sourceScheduler.executeImmediate()
            val request = GravesNetworkWire.travelCodec.decode(sourceRedis.getPublishedMessages().single().message)

            sourceRedis.simulateExternalMessage(
                GravesNetworkWire.TRAVEL_CHANNEL,
                GravesNetworkWire.travelCodec.encode(
                    request.reply(
                        playerId = playerId.toString(),
                        graveId = otherGraveId.toString(),
                    ),
                ),
                originServer = "survival",
            )
            prepared.isDone shouldBe false

            sourceRedis.simulateExternalMessage(
                GravesNetworkWire.TRAVEL_CHANNEL,
                GravesNetworkWire.travelCodec.encode(request.reply()),
                originServer = "survival",
            )
            sourceScheduler.executeImmediate()
            prepared.get(2, TimeUnit.SECONDS) shouldBe true
        }
    }

    "rejects expired and malformed wire messages" {
        val request = travelRequest(createdAtMillis = 1L)
        GravesNetworkWire.isValidInboundRequest(request, "spawn", "survival", 10_002L) shouldBe false
        runCatching { GravesNetworkWire.travelCodec.decode("""{"id":"bad"}""") }.isFailure shouldBe true
        runCatching {
            GravesNetworkWire.travelCodec.encode(request.copy(quotedPrice = Double.NaN))
        }.isFailure shouldBe true
    }
})

private fun grave(id: UUID, ownerId: UUID, server: String) = GraveView(
    id = id,
    ownerId = ownerId,
    server = server,
    world = "world",
    x = 10,
    y = 64,
    z = 20,
    createdAt = 1_000L,
    expiresAt = 30_000L,
    experience = 4,
    items = listOf(GraveItem("{\"text\":\"Железо\"}", 2)),
)

private fun GraveView.toNetwork() = GraveNetworkView(
    id = id.toString(),
    ownerId = ownerId.toString(),
    server = server,
    world = world,
    x = x,
    y = y,
    z = z,
    createdAt = createdAt,
    expiresAt = expiresAt,
    experience = experience,
    items = items.map { GraveNetworkItem(it.nameJson, it.amount) },
)

private fun travelRequest(createdAtMillis: Long) = GraveTravelMessage(
    id = UUID.randomUUID().toString(),
    type = "PREPARE",
    originServer = "spawn",
    targetServer = "survival",
    playerId = "11111111-1111-4111-8111-111111111111",
    graveId = "22222222-2222-4222-8222-222222222222",
    quotedPrice = 12.5,
    createdAtMillis = createdAtMillis,
)

private fun GraveTravelMessage.reply(
    playerId: String = this.playerId,
    graveId: String = this.graveId,
) = copy(
    id = UUID.randomUUID().toString(),
    replyTo = this.id,
    type = "PREPARED",
    originServer = targetServer,
    targetServer = originServer,
    playerId = playerId,
    graveId = graveId,
)
