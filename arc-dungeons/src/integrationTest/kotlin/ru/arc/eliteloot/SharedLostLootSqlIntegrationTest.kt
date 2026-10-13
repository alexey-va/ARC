package ru.arc.eliteloot

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.paper.api.ArcSqlSslMode
import ru.arc.paper.sql.PaperSqlServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.util.UUID
import java.util.concurrent.TimeUnit

@Tag("integration")
@Tags("integration")
class SharedLostLootSqlIntegrationTest : StringSpec({
    "two SQL leases share persisted lost loot and arbitrate one reservation" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_dungeons_shared_lost_loot_test")).use { mysql ->
            MockBukkitTestRuntime.open().use { paper ->
                val provider = PaperSqlServiceProvider(paper.createSimplePlugin("ArcDungeonsSqlHost"))
                val service = checkNotNull(paper.server.servicesManager.load(ArcSqlService::class.java))
                val endpoint = mysql.endpoint
                val settings = ArcSqlSettings(
                    host = endpoint.host,
                    port = endpoint.port,
                    database = endpoint.database,
                    username = endpoint.username,
                    password = endpoint.password,
                    sslMode = ArcSqlSslMode.DISABLED,
                    minimumIdle = 0,
                    maximumPoolSize = 2,
                    connectionTimeoutMs = 5_000,
                    socketTimeoutMs = 5_000,
                    validationTimeoutMs = 1_000,
                    maxLifetimeMs = 1_800_000,
                    failFast = false,
                )
                var first: MySqlSharedLostLootRepository? = null
                var second: MySqlSharedLostLootRepository? = null
                try {
                    val firstRepository = MySqlSharedLostLootRepository.open(settings).also { first = it }
                    val owner = UUID.randomUUID()
                    val record = LostLootRecord(
                        id = UUID.randomUUID().toString(),
                        owner = owner.toString(),
                        item = "serialized-lost-loot-payload",
                        capturedAt = System.currentTimeMillis(),
                    )
                    firstRepository.publish(record, "spawn").get(10, TimeUnit.SECONDS)

                    val secondRepository = MySqlSharedLostLootRepository.open(settings).also { second = it }
                    secondRepository.list(owner).get(10, TimeUnit.SECONDS).map { it.id } shouldBe listOf(record.id)
                    provider.snapshot().single().borrowers shouldBe 2

                    val firstToken = UUID.randomUUID()
                    val secondToken = UUID.randomUUID()
                    val firstReservation = firstRepository.reserve(
                        record.id, owner, SharedLootState.CLAIMING, firstToken, "spawn", null,
                    )
                    val secondReservation = secondRepository.reserve(
                        record.id, owner, SharedLootState.CLAIMING, secondToken, "survival", null,
                    )
                    val firstResult = firstReservation.get(10, TimeUnit.SECONDS)
                    val secondResult = secondReservation.get(10, TimeUnit.SECONDS)
                    listOf(firstResult, secondResult).count { it != null } shouldBe 1
                    val reserved = requireNotNull(firstResult ?: secondResult)
                    val winningToken = if (firstResult != null) firstToken else secondToken
                    reserved.state shouldBe SharedLootState.CLAIMING
                    reserved.token shouldBe winningToken.toString()
                    secondRepository.nextPending(owner).get(10, TimeUnit.SECONDS) shouldBe reserved

                    firstRepository.close()
                    first = null
                    provider.snapshot().single().borrowers shouldBe 1

                    secondRepository.list(owner).get(10, TimeUnit.SECONDS).single() shouldBe reserved
                    secondRepository.release(
                        record.id, owner, UUID.fromString(requireNotNull(reserved.token)), SharedLootState.CLAIMING,
                    ).get(10, TimeUnit.SECONDS) shouldBe true
                    secondRepository.list(owner).get(10, TimeUnit.SECONDS).single().state shouldBe SharedLootState.AVAILABLE

                    secondRepository.close()
                    second = null
                } finally {
                    first?.close()
                    second?.close()
                    provider.close()
                }
                provider.snapshot() shouldBe emptyList()
                service.snapshot() shouldBe emptyList()
            }
        }
    }
})
