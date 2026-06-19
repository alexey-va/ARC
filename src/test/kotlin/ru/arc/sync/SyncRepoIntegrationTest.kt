package ru.arc.sync

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import ru.arc.ARC
import ru.arc.network.RedisManager
import ru.arc.sync.base.Context
import ru.arc.sync.base.SyncData
import ru.arc.sync.base.SyncRepo
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration tests for SyncRepo verifying the full Redis round-trip:
 * produce → save to Redis → load from Redis → apply.
 *
 * Requires Docker (Colima or Docker Desktop).
 */
class SyncRepoIntegrationTest : FreeSpec({

    val redis = startRedis()
    lateinit var redisManager: RedisManager

    beforeSpec {
        ARC.serverName = "server-A"
        val host = redis.host
        val port = redis.getMappedPort(6379)
        redisManager = RedisManager(host, port, null, null)
        Thread.sleep(500)
    }

    afterSpec {
        redisManager.close()
    }

    "SyncRepo" - {

        "save and load round-trip delivers DTO to applier" {
            val received = AtomicReference<TestDto?>()
            val uuid = UUID.randomUUID()

            val repo = buildRepo(redisManager, uuid, received)

            val ctx = Context().also { it.put("uuid", uuid) }
            repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
            Thread.sleep(300)

            ARC.serverName = "server-B"
            repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
            Thread.sleep(200)

            val dto = received.get().shouldNotBeNull()
            dto.value shouldBe "synced-$uuid"
            dto.id shouldBe uuid
        }

        "applier is skipped when data originates from same server" {
            val received = AtomicReference<TestDto?>()
            val uuid = UUID.randomUUID()

            ARC.serverName = "same-server"
            val repo = buildRepo(redisManager, uuid, received, server = "same-server")

            val ctx = Context().also { it.put("uuid", uuid) }
            repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
            Thread.sleep(300)

            repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
            Thread.sleep(200)

            received.get() shouldBe null
        }

        "load returns nothing when key is absent" {
            val received = AtomicReference<TestDto?>()
            val uuid = UUID.randomUUID()
            val repo = buildRepo(redisManager, uuid, received)

            ARC.serverName = "other-server"
            repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
            Thread.sleep(200)

            received.get() shouldBe null
        }

        "multiple saves overwrite with latest value" {
            val received = AtomicReference<TestDto?>()
            val uuid = UUID.randomUUID()

            var counter = 0
            val repo = SyncRepo.builder(TestDto::class.java)
                .key("test.overwrite")
                .redisManager(redisManager)
                .dataProducer { ctx ->
                    counter++
                    val id: UUID = ctx.get("uuid")
                    TestDto(ts = System.currentTimeMillis(), srv = "server-A", id = id, value = "v$counter")
                }
                .dataApplier { received.set(it) }
                .build()

            val ctx = Context().also { it.put("uuid", uuid) }
            repeat(3) {
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(100)
            }

            ARC.serverName = "server-B"
            repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
            Thread.sleep(200)

            received.get()?.value shouldBe "v3"
        }

        "Gson round-trip preserves all fields including nulls" {
            val gson = Gson()
            val dto = TestDto(
                ts = 999L,
                srv = "srv",
                id = UUID.randomUUID(),
                value = "hello",
            )
            val json = gson.toJson(dto)
            val back = gson.fromJson(json, TestDto::class.java)

            back.ts shouldBe dto.ts
            back.srv shouldBe dto.srv
            back.id shouldBe dto.id
            back.value shouldBe dto.value
        }
    }
}) {
    companion object {
        private val sharedRedis: GenericContainer<*> by lazy {
            configureTestcontainers()
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .apply { withExposedPorts(6379); withReuse(true) }
                .also { it.start() }
        }

        fun startRedis(): GenericContainer<*> = sharedRedis

        private fun configureTestcontainers() {
            val home = System.getProperty("user.home")
            val colimaSocket = File(home, ".colima/docker.sock").takeIf { it.exists() }
                ?: File(home, ".colima/default/docker.sock").takeIf { it.exists() }

            if (colimaSocket != null) {
                val path = colimaSocket.absolutePath
                if (System.getenv("DOCKER_HOST") == null)
                    System.setProperty("DOCKER_HOST", "unix://$path")
                if (System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == null)
                    System.setProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", path)
                if (System.getenv("TESTCONTAINERS_RYUK_DISABLED") == null)
                    System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true")
            }
        }

        private fun buildRepo(
            redisManager: RedisManager,
            uuid: UUID,
            received: AtomicReference<TestDto?>,
            server: String = "server-A",
        ): SyncRepo<TestDto> = SyncRepo.builder(TestDto::class.java)
            .key("test.sync_repo.${uuid}")
            .redisManager(redisManager)
            .dataProducer { ctx ->
                val id: UUID = ctx.get("uuid")
                TestDto(ts = System.currentTimeMillis(), srv = server, id = id, value = "synced-$id")
            }
            .dataApplier { received.set(it) }
            .build()
    }

    data class TestDto(
        val ts: Long = 0L,
        val srv: String = "",
        val id: UUID? = null,
        val value: String = "",
    ) : SyncData {
        override fun timestamp(): Long = ts
        override fun server(): String = srv
        override fun uuid(): UUID? = id
    }
}
