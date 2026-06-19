package ru.arc.sync

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
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

class SkillsSyncTest :
    FreeSpec({

        val redis = startRedis()
        lateinit var redisManager: RedisManager

        beforeSpec {
            ARC.serverName = "server-skills-a"
            redisManager = RedisManager(redis.host, redis.getMappedPort(6379), null, null)
            Thread.sleep(500)
        }

        afterSpec { redisManager.close() }

        "UserSkillData DTO" - {

            "should serialize using compact @SerializedName keys" {
                val gson = Gson()
                val dto =
                    SkillsSync.UserSkillData(
                        id = UUID.randomUUID(),
                        skills = listOf(SkillsSync.SkillInfo(id = "auraskills:farming", level = 10, xp = 500.0)),
                        mana = 75.5,
                        ts = 1234L,
                        srv = "lobby-01",
                    )

                val json = gson.toJson(dto)

                json shouldContain "\"u\":"
                json shouldContain "\"sk\":"
                json shouldContain "\"m\":"
                json shouldContain "\"t\":"
                json shouldContain "\"s\":"

                json shouldNotContain "\"uuid\""
                json shouldNotContain "\"skills\""
                json shouldNotContain "\"mana\""
                json shouldNotContain "\"timestamp\""
                json shouldNotContain "\"server\":"
            }

            "should serialize SkillInfo with compact keys" {
                val gson = Gson()
                val info = SkillsSync.SkillInfo(id = "auraskills:farming", level = 10, xp = 500.0)
                val json = gson.toJson(info)

                json shouldContain "\"i\":"
                json shouldContain "\"l\":"
                json shouldContain "\"x\":"

                json shouldNotContain "\"id\""
                json shouldNotContain "\"level\""
                json shouldNotContain "\"xp\""
            }

            "should round-trip all fields via Gson" {
                val gson = Gson()
                val uuid = UUID.randomUUID()
                val original =
                    SkillsSync.UserSkillData(
                        id = uuid,
                        skills =
                            listOf(
                                SkillsSync.SkillInfo(id = "auraskills:farming", level = 15, xp = 1500.75),
                                SkillsSync.SkillInfo(id = "auraskills:mining", level = 8, xp = 800.0),
                                SkillsSync.SkillInfo(id = "auraskills:fighting", level = 20, xp = 2000.0),
                            ),
                        mana = 100.0,
                        ts = 56789L,
                        srv = "lobby",
                    )

                val back = gson.fromJson(gson.toJson(original), SkillsSync.UserSkillData::class.java)

                back.id shouldBe uuid
                back.srv shouldBe "lobby"
                back.ts shouldBe 56789L
                back.mana shouldBe 100.0
                back.skills shouldHaveSize 3
                back.skills[0].id shouldBe "auraskills:farming"
                back.skills[0].level shouldBe 15
                back.skills[0].xp shouldBe 1500.75
                back.skills[1].id shouldBe "auraskills:mining"
                back.skills[2].level shouldBe 20
            }

            "should handle empty skills list" {
                val gson = Gson()
                val dto = SkillsSync.UserSkillData(id = UUID.randomUUID(), skills = emptyList(), mana = 0.0, ts = 0L, srv = "")
                val back = gson.fromJson(gson.toJson(dto), SkillsSync.UserSkillData::class.java)
                back.skills shouldBe emptyList()
            }

            "SyncData interface methods delegate to backing fields" {
                val uuid = UUID.randomUUID()
                val dto = SkillsSync.UserSkillData(ts = 55L, srv = "skill-srv", id = uuid)
                dto.timestamp() shouldBe 55L
                dto.server() shouldBe "skill-srv"
                dto.uuid() shouldBe uuid
            }

            "trash() returns false by default" {
                SkillsSync.UserSkillData().trash() shouldBe false
            }
        }

        "SkillsSync Redis integration" - {

            "save to Redis and load back delivers all skill data to applier" {
                val received = AtomicReference<SkillsSync.UserSkillData?>()
                val uuid = UUID.randomUUID()

                val testSkills =
                    listOf(
                        SkillsSync.SkillInfo(id = "auraskills:farming", level = 12, xp = 1200.0),
                        SkillsSync.SkillInfo(id = "auraskills:foraging", level = 6, xp = 600.0),
                        SkillsSync.SkillInfo(id = "auraskills:fighting", level = 25, xp = 2500.0),
                    )

                val repo =
                    SyncRepo
                        .builder(SkillsSync.UserSkillData::class.java)
                        .key("test.skills_sync.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            SkillsSync.UserSkillData(
                                id = id,
                                skills = testSkills,
                                mana = 87.5,
                                ts = System.currentTimeMillis(),
                                srv = "server-skills-a",
                            )
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                ARC.serverName = "server-skills-b"
                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                val dto = received.get().shouldNotBeNull()
                dto.mana shouldBe 87.5
                dto.skills shouldHaveSize 3
                dto.skills[0].id shouldBe "auraskills:farming"
                dto.skills[0].level shouldBe 12
                dto.skills[0].xp shouldBe 1200.0
                dto.skills[2].id shouldBe "auraskills:fighting"
                dto.skills[2].level shouldBe 25
            }

            "applier NOT called when data is from same server" {
                val received = AtomicReference<SkillsSync.UserSkillData?>()
                val uuid = UUID.randomUUID()

                ARC.serverName = "server-skills-self"
                val repo =
                    SyncRepo
                        .builder(SkillsSync.UserSkillData::class.java)
                        .key("test.skills_sync_self.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            SkillsSync.UserSkillData(ts = 1L, srv = "server-skills-self", id = id, mana = 50.0)
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                received.get() shouldBe null
            }

            "mana value preserved precisely through Redis" {
                val received = AtomicReference<SkillsSync.UserSkillData?>()
                val uuid = UUID.randomUUID()
                val preciseMana = 123.456789

                val repo =
                    SyncRepo
                        .builder(SkillsSync.UserSkillData::class.java)
                        .key("test.skills_mana.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            SkillsSync.UserSkillData(ts = 1L, srv = "server-skills-a", id = id, mana = preciseMana)
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                ARC.serverName = "server-skills-c"
                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                received.get()?.mana shouldBe preciseMana
            }

            "high skill xp values preserved correctly" {
                val received = AtomicReference<SkillsSync.UserSkillData?>()
                val uuid = UUID.randomUUID()
                val bigXp = 99999999.99

                val repo =
                    SyncRepo
                        .builder(SkillsSync.UserSkillData::class.java)
                        .key("test.skills_bigxp.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            SkillsSync.UserSkillData(
                                ts = 1L,
                                srv = "server-skills-a",
                                id = id,
                                skills = listOf(SkillsSync.SkillInfo(id = "auraskills:fighting", level = 100, xp = bigXp)),
                            )
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                ARC.serverName = "server-skills-d"
                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                received
                    .get()
                    ?.skills
                    ?.first()
                    ?.xp shouldBe bigXp
                received
                    .get()
                    ?.skills
                    ?.first()
                    ?.level shouldBe 100
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
