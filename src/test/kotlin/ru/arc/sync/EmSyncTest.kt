package ru.arc.sync

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import ru.arc.ARC
import ru.arc.network.RedisManager
import ru.arc.sync.base.Context
import ru.arc.sync.base.SyncRepo
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EmSyncTest :
    FreeSpec({

        val redis = startRedis()
        lateinit var redisManager: RedisManager

        beforeSpec {
            ARC.serverName = "server-em-a"
            val host = redis.host
            val port = redis.getMappedPort(6379)
            redisManager = RedisManager(host, port, null, null)
            Thread.sleep(500)
        }

        afterSpec { redisManager.close() }

        "EmDataDTO" - {

            "should serialize using compact @SerializedName keys" {
                val gson = Gson()
                val dto =
                    EmSync.EmDataDTO(
                        ts = 1000L,
                        srv = "srv",
                        id = UUID.randomUUID(),
                        currency = 500.0,
                        score = 10,
                        kills = 5,
                        deaths = 2,
                        playerLevel = 3,
                        skillXP = longArrayOf(100L, 200L, 300L, 0L, 0L, 0L, 0L, 0L, 0L),
                        skillBonusSelections = "bonus1",
                        gamblingDebt = 0.0,
                        questsCompleted = 7,
                    )

                val json = gson.toJson(dto)

                // Should use compact keys
                json shouldContain "\"t\":"
                json shouldContain "\"s\":"
                json shouldContain "\"u\":"
                json shouldContain "\"c\":"
                json shouldContain "\"sc\":"
                json shouldContain "\"k\":"
                json shouldContain "\"de\":"
                json shouldContain "\"pl\":"
                json shouldContain "\"sx\":"
                json shouldContain "\"sb\":"
                json shouldContain "\"qc\":"

                // Should NOT use verbose keys
                json shouldNotContain "\"timestamp\""
                json shouldNotContain "\"server\""
                json shouldNotContain "\"currency\""
                json shouldNotContain "\"playerLevel\""
                json shouldNotContain "\"skillBonusSelections\""
            }

            "should round-trip all fields via Gson" {
                val gson = Gson()
                val uuid = UUID.randomUUID()
                val original =
                    EmSync.EmDataDTO(
                        ts = 12345L,
                        srv = "test-server",
                        id = uuid,
                        currency = 999.99,
                        highestLevelKilled = 42,
                        useBookMenu = true,
                        dismissEmStatus = false,
                        score = 100,
                        kills = 20,
                        deaths = 5,
                        playerLevel = 7,
                        skillXP = longArrayOf(1000L, 2000L, 3000L, 4000L, 5000L, 6000L, 7000L, 8000L, 9000L),
                        skillBonusSelections = "sword:crit,armor:tough",
                        gamblingDebt = 250.5,
                        questsCompleted = 15,
                    )

                val back = gson.fromJson(gson.toJson(original), EmSync.EmDataDTO::class.java)

                back.ts shouldBe original.ts
                back.srv shouldBe original.srv
                back.id shouldBe original.id
                back.currency shouldBe original.currency
                back.highestLevelKilled shouldBe original.highestLevelKilled
                back.useBookMenu shouldBe original.useBookMenu
                back.dismissEmStatus shouldBe original.dismissEmStatus
                back.score shouldBe original.score
                back.kills shouldBe original.kills
                back.deaths shouldBe original.deaths
                back.playerLevel shouldBe original.playerLevel
                back.skillXP?.toList() shouldBe original.skillXP?.toList()
                back.skillBonusSelections shouldBe original.skillBonusSelections
                back.gamblingDebt shouldBe original.gamblingDebt
                back.questsCompleted shouldBe original.questsCompleted
            }

            "should default all fields to safe zero values" {
                val dto = EmSync.EmDataDTO()

                dto.ts shouldBe 0L
                dto.srv shouldBe ""
                dto.id shouldBe null
                dto.currency shouldBe 0.0
                dto.highestLevelKilled shouldBe 0
                dto.useBookMenu shouldBe false
                dto.dismissEmStatus shouldBe false
                dto.score shouldBe 0
                dto.kills shouldBe 0
                dto.deaths shouldBe 0
                dto.playerLevel shouldBe 0
                dto.skillXP shouldBe null
                dto.skillBonusSelections shouldBe null
                dto.gamblingDebt shouldBe 0.0
                dto.questsCompleted shouldBe 0
            }

            "SyncData interface methods delegate to backing fields" {
                val uuid = UUID.randomUUID()
                val dto = EmSync.EmDataDTO(ts = 42L, srv = "s1", id = uuid)

                dto.timestamp() shouldBe 42L
                dto.server() shouldBe "s1"
                dto.uuid() shouldBe uuid
            }

            "trash() returns false (data is never discarded)" {
                EmSync.EmDataDTO().trash() shouldBe false
            }
        }

        "EmSync Redis integration" - {

            "save to Redis and load back delivers DTO to applier" {
                val received = AtomicReference<EmSync.EmDataDTO?>()
                val uuid = UUID.randomUUID()

                val repo =
                    SyncRepo
                        .builder(EmSync.EmDataDTO::class.java)
                        .key("test.em_sync.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            EmSync.EmDataDTO(
                                ts = System.currentTimeMillis(),
                                srv = "server-em-a",
                                id = id,
                                currency = 1234.56,
                                score = 99,
                                kills = 10,
                                deaths = 1,
                                playerLevel = 5,
                                skillXP = longArrayOf(500L, 600L, 700L, 0L, 0L, 0L, 0L, 0L, 0L),
                                skillBonusSelections = "test:bonus",
                                gamblingDebt = 0.0,
                                questsCompleted = 3,
                            )
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                ARC.serverName = "server-em-b"
                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                val dto = received.get().shouldNotBeNull()
                dto.currency shouldBe 1234.56
                dto.score shouldBe 99
                dto.kills shouldBe 10
                dto.playerLevel shouldBe 5
                dto.skillXP?.toList() shouldBe listOf(500L, 600L, 700L, 0L, 0L, 0L, 0L, 0L, 0L)
                dto.skillBonusSelections shouldBe "test:bonus"
                dto.questsCompleted shouldBe 3
            }

            "applier is NOT called when data originates from this server" {
                val received = AtomicReference<EmSync.EmDataDTO?>()
                val uuid = UUID.randomUUID()

                ARC.serverName = "server-em-self"
                val repo =
                    SyncRepo
                        .builder(EmSync.EmDataDTO::class.java)
                        .key("test.em_sync_self.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            EmSync.EmDataDTO(ts = 1L, srv = "server-em-self", id = id, currency = 100.0)
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                received.get() shouldBe null
            }

            "skillXP LongArray serializes and deserializes correctly via Redis" {
                val received = AtomicReference<EmSync.EmDataDTO?>()
                val uuid = UUID.randomUUID()
                val expectedXP = longArrayOf(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L)

                val repo =
                    SyncRepo
                        .builder(EmSync.EmDataDTO::class.java)
                        .key("test.em_skillxp.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            EmSync.EmDataDTO(ts = 1L, srv = "server-xp-a", id = id, skillXP = expectedXP)
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                ARC.serverName = "server-xp-b"
                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                received.get()?.skillXP?.toList() shouldBe expectedXP.toList()
            }
        }
    }) {
    companion object {
        private val sharedRedis: GenericContainer<*> by lazy {
            configureTestcontainers()
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .apply {
                    withExposedPorts(6379)
                    withReuse(true)
                }.also { it.start() }
        }

        fun startRedis(): GenericContainer<*> = sharedRedis

        private fun configureTestcontainers() {
            val home = System.getProperty("user.home")
            val socket =
                File(home, ".colima/docker.sock").takeIf { it.exists() }
                    ?: File(home, ".colima/default/docker.sock").takeIf { it.exists() }
            if (socket != null) {
                val path = socket.absolutePath
                if (System.getenv("DOCKER_HOST") == null) {
                    System.setProperty("DOCKER_HOST", "unix://$path")
                }
                if (System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == null) {
                    System.setProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", path)
                }
                if (System.getenv("TESTCONTAINERS_RYUK_DISABLED") == null) {
                    System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true")
                }
            }
        }
    }
}
