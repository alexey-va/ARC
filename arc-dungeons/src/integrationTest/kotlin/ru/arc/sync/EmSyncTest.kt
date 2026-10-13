package ru.arc.sync

import com.google.gson.Gson
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import ru.arc.core.TestTaskScheduler
import ru.arc.dungeons.api.ArcPlayerSaveService
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.testing.containers.RedisTestService
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

@Tag("integration")
@Tags("integration")
class EmSyncTest : FreeSpec({
    "ArcRedisService lease preserves the EmData hash payload and receipt across server identities" {
        RedisTestService.start().use { redis ->
            MockBukkitTestRuntime.open().use { paper ->
                val host = paper.createSimplePlugin("ArcHost")
                val provider = PaperRedisServiceProvider(host)
                var source: EmSync? = null
                var receiver: EmSync? = null
                try {
                    provider.configurePrimary(
                        ArcRedisSettings(redis.endpoint.host, redis.endpoint.port, null, null),
                        "test-network",
                    )
                    val service = checkNotNull(host.server.servicesManager.load(ArcRedisService::class.java))
                    val player = paper.addPlayer("EmSyncRedis")
                    val playerId = player.uniqueId

                    val sourceState = TestEmSyncPlayerData().apply {
                        available += playerId
                        currency = 1234.56
                        xp = longArrayOf(100L, 200L, 300L, 400L)
                    }
                    val sourceScheduler = TestTaskScheduler()
                    val sourceSync = EmSync(
                        paper.createSimplePlugin("ArcDungeonsSource"),
                        "spawn",
                        service,
                        sourceScheduler,
                        sourceState,
                        NoopArcPlayerSaveService,
                    )
                    source = sourceSync
                    sourceSync.start()
                    sourceScheduler.tick(5)
                    waitForReady(sourceSync, sourceScheduler, playerId)
                    sourceSync.markLootCredit(playerId, "lost-loot-credit-42")
                    sourceSync.persist(player).get(5, TimeUnit.SECONDS)

                    val receiverState = TestEmSyncPlayerData().apply { available += playerId }
                    val receiverScheduler = TestTaskScheduler()
                    val receiverSync = EmSync(
                        paper.createSimplePlugin("ArcDungeonsReceiver"),
                        "survival",
                        service,
                        receiverScheduler,
                        receiverState,
                        NoopArcPlayerSaveService,
                    )
                    receiver = receiverSync
                    receiverSync.start()
                    receiverScheduler.tick(5)
                    waitForReady(receiverSync, receiverScheduler, playerId)

                    receiverState.applied.size shouldBe 1
                    val loaded = receiverState.applied.single()
                    loaded.srv shouldBe "spawn"
                    loaded.currency shouldBe 1234.56
                    loaded.skillXP?.toList() shouldBe listOf(100L, 200L, 300L, 400L)
                    loaded.lostLootCreditReceipt shouldBe "lost-loot-credit-42"
                    receiverSync.lootCreditReceipt(playerId) shouldBe "lost-loot-credit-42"
                } finally {
                    receiver?.close()
                    source?.close()
                    listOfNotNull(receiver, source).forEach { sync ->
                        runCatching { sync.closeCompletion().get(5, TimeUnit.SECONDS) }
                    }
                    provider.close()
                }
            }
        }
    }
})

private fun waitForReady(sync: EmSync, scheduler: TestTaskScheduler, playerId: UUID) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!sync.isReady(playerId) && System.nanoTime() < deadline) {
        scheduler.executeImmediate()
        Thread.sleep(10)
    }
    scheduler.executeImmediate()
    sync.isReady(playerId) shouldBe true
}

private class TestEmSyncPlayerData : EmSyncPlayerData {
    val available = mutableSetOf<UUID>()
    val applied = mutableListOf<EmSync.EmDataDTO>()
    var currency = 0.0
    var xp = longArrayOf()

    override fun isAvailable(uuid: UUID): Boolean = uuid in available

    override fun snapshot(uuid: UUID, serverName: String, receipt: String?): EmSync.EmDataDTO? =
        if (!isAvailable(uuid)) null else EmSync.EmDataDTO(
            ts = System.currentTimeMillis(),
            srv = serverName,
            id = uuid,
            currency = currency,
            skillXP = xp,
            lostLootCreditReceipt = receipt,
        )

    override fun apply(data: EmSync.EmDataDTO) {
        applied += data
        currency = data.currency
        xp = data.skillXP ?: xp
    }
}

private object NoopArcPlayerSaveService : ArcPlayerSaveService {
    override fun register(owner: org.bukkit.plugin.Plugin, snapshot: Consumer<org.bukkit.entity.Player>): AutoCloseable =
        AutoCloseable { }
}
