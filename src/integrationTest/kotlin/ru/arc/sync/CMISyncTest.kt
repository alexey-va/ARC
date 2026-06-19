package ru.arc.sync

import com.google.gson.Gson
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FreeSpec
import org.junit.jupiter.api.Tag
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

@Tag("integration")
@Tags("integration")
class CMISyncTest : FreeSpec() {

    private val redis: GenericContainer<*> by lazy { startRedis() }
    private lateinit var redisManager: RedisManager

    init {
        beforeSpec {
            ARC.serverName = "server-cmi-a"
            redisManager = RedisManager(redis.host, redis.getMappedPort(6379), null, null)
            Thread.sleep(500)
        }

        afterSpec { redisManager.close() }

        "CMIDataDTO" - {

            "should serialize using compact @SerializedName keys" {
                val gson = Gson()
                val dto =
                    CMISync.CMIDataDTO(
                        ts = 1L,
                        srv = "s",
                        id = UUID.randomUUID(),
                        prefix = "&aAdmin",
                        suffix = null,
                        rank = "admin",
                        nick = "Cool",
                        god = true,
                        glowColor = 'a',
                        noPay = false,
                        shift = false,
                        totem = true,
                        pm = true,
                        vanish = false,
                        mail = listOf(CMISync.CMIDataDTO.Mail(sender = "Alice", time = 1000L, message = "Hi")),
                        kitUsage = mapOf("starter" to CMISync.CMIDataDTO.KitUsage(uses = 2, lastUse = 5000L)),
                    )

                val json = gson.toJson(dto)

                json shouldContain "\"ts\":"
                json shouldContain "\"s\":"
                json shouldContain "\"u\":"
                json shouldContain "\"p\":"
                json shouldContain "\"r\":"
                json shouldContain "\"n\":"
                json shouldContain "\"g\":"
                json shouldContain "\"gc\":"
                json shouldContain "\"v\":"
                json shouldContain "\"m\":"
                json shouldContain "\"ku\":"

                json shouldNotContain "\"prefix\""
                json shouldNotContain "\"rank\""
                json shouldNotContain "\"nickName\""
                json shouldNotContain "\"glowColor\""
                json shouldNotContain "\"kitUsage\""
            }

            "should round-trip all fields via Gson" {
                val gson = Gson()
                val uuid = UUID.randomUUID()
                val original =
                    CMISync.CMIDataDTO(
                        ts = 99L,
                        srv = "server1",
                        id = uuid,
                        prefix = "&aAdmin",
                        suffix = "&7[VIP]",
                        rank = "owner",
                        nick = "Shadow",
                        god = true,
                        glowColor = 'b',
                        noPay = true,
                        shift = false,
                        totem = true,
                        pm = false,
                        vanish = true,
                        mail =
                            listOf(
                                CMISync.CMIDataDTO.Mail(sender = "Bob", time = 12345L, message = "Hello!"),
                                CMISync.CMIDataDTO.Mail(sender = "Eve", time = 67890L, message = "Bye"),
                            ),
                        kitUsage =
                            mapOf(
                                "starter" to CMISync.CMIDataDTO.KitUsage(uses = 3, lastUse = 9999L),
                                "vip" to CMISync.CMIDataDTO.KitUsage(uses = 1, lastUse = 1234L),
                            ),
                    )

                val back = gson.fromJson(gson.toJson(original), CMISync.CMIDataDTO::class.java)

                back.ts shouldBe original.ts
                back.srv shouldBe original.srv
                back.id shouldBe original.id
                back.prefix shouldBe original.prefix
                back.suffix shouldBe original.suffix
                back.rank shouldBe original.rank
                back.nick shouldBe original.nick
                back.god shouldBe original.god
                back.glowColor shouldBe original.glowColor
                back.noPay shouldBe original.noPay
                back.shift shouldBe original.shift
                back.totem shouldBe original.totem
                back.pm shouldBe original.pm
                back.vanish shouldBe original.vanish
                back.mail?.size shouldBe 2
                back.mail?.get(0)?.sender shouldBe "Bob"
                back.mail?.get(1)?.message shouldBe "Bye"
                back.kitUsage?.get("starter")?.uses shouldBe 3
                back.kitUsage?.get("vip")?.lastUse shouldBe 1234L
            }

            "should handle null optional fields gracefully" {
                val gson = Gson()
                val dto = CMISync.CMIDataDTO(id = UUID.randomUUID())
                val back = gson.fromJson(gson.toJson(dto), CMISync.CMIDataDTO::class.java)
                back.prefix shouldBe null
                back.suffix shouldBe null
                back.rank shouldBe null
                back.nick shouldBe null
                back.glowColor shouldBe null
                back.mail shouldBe null
                back.kitUsage shouldBe null
            }

            "SyncData interface methods delegate to backing fields" {
                val uuid = UUID.randomUUID()
                val dto = CMISync.CMIDataDTO(ts = 77L, srv = "test", id = uuid)
                dto.timestamp() shouldBe 77L
                dto.server() shouldBe "test"
                dto.uuid() shouldBe uuid
            }

            "Mail nested DTO round-trips via Gson" {
                val gson = Gson()
                val mail = CMISync.CMIDataDTO.Mail(sender = "Alice", time = 99999L, message = "Test message")
                val back = gson.fromJson(gson.toJson(mail), CMISync.CMIDataDTO.Mail::class.java)
                back.sender shouldBe "Alice"
                back.time shouldBe 99999L
                back.message shouldBe "Test message"
            }

            "KitUsage nested DTO round-trips via Gson" {
                val gson = Gson()
                val kit = CMISync.CMIDataDTO.KitUsage(uses = 5, lastUse = 77777L)
                val back = gson.fromJson(gson.toJson(kit), CMISync.CMIDataDTO.KitUsage::class.java)
                back.uses shouldBe 5
                back.lastUse shouldBe 77777L
            }
        }

        "CMISync Redis integration" - {

            "save to Redis and load back delivers full DTO to applier" {
                val received = AtomicReference<CMISync.CMIDataDTO?>()
                val uuid = UUID.randomUUID()

                val repo =
                    SyncRepo
                        .builder(CMISync.CMIDataDTO::class.java)
                        .key("test.cmi_sync.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            CMISync.CMIDataDTO(
                                ts = System.currentTimeMillis(),
                                srv = "server-cmi-a",
                                id = id,
                                prefix = "&aAdmin",
                                rank = "moderator",
                                nick = "Moder",
                                god = false,
                                vanish = false,
                                mail = listOf(CMISync.CMIDataDTO.Mail(sender = "System", time = 1L, message = "Welcome")),
                                kitUsage = mapOf("starter" to CMISync.CMIDataDTO.KitUsage(uses = 1, lastUse = 0L)),
                            )
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                ARC.serverName = "server-cmi-b"
                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                val dto = received.get().shouldNotBeNull()
                dto.prefix shouldBe "&aAdmin"
                dto.rank shouldBe "moderator"
                dto.nick shouldBe "Moder"
                dto.mail?.size shouldBe 1
                dto.mail?.first()?.sender shouldBe "System"
                dto.kitUsage?.get("starter")?.uses shouldBe 1
            }

            "applier NOT called when data is from same server" {
                val received = AtomicReference<CMISync.CMIDataDTO?>()
                val uuid = UUID.randomUUID()

                ARC.serverName = "server-cmi-self"
                val repo =
                    SyncRepo
                        .builder(CMISync.CMIDataDTO::class.java)
                        .key("test.cmi_sync_self.$uuid")
                        .redisManager(redisManager)
                        .dataProducer { ctx ->
                            val id: UUID = ctx.get("uuid")
                            CMISync.CMIDataDTO(ts = 1L, srv = "server-cmi-self", id = id, prefix = "&aAdmin")
                        }.dataApplier { received.set(it) }
                        .build()

                val ctx = Context().also { it.put("uuid", uuid) }
                repo.saveAndPersistData(ctx, false).get(2, TimeUnit.SECONDS)
                Thread.sleep(300)

                repo.loadAndApplyData(uuid, true).get(2, TimeUnit.SECONDS)
                Thread.sleep(200)

                received.get() shouldBe null
            }
        }
    }

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
