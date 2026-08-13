package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class OpsAuthTest : FreeSpec({

    "OpsAuth" - {
        "should accept Bearer token" {
            val headers = mapOf("Authorization" to "Bearer secret-token")
            OpsAuth.isAuthorized(headers, "secret-token") shouldBe true
        }

        "should accept X-ARC-Ops-Token header" {
            val headers = mapOf("X-ARC-Ops-Token" to "secret-token")
            OpsAuth.isAuthorized(headers, "secret-token") shouldBe true
        }

        "should reject missing token" {
            OpsAuth.isAuthorized(emptyMap(), "secret-token") shouldBe false
        }

        "should reject wrong token" {
            val headers = mapOf("Authorization" to "Bearer wrong")
            OpsAuth.isAuthorized(headers, "secret-token") shouldBe false
        }

        "should reject blank configured token" {
            val headers = mapOf("Authorization" to "Bearer anything")
            OpsAuth.isAuthorized(headers, "") shouldBe false
        }
    }
})

class OpsLogBufferTest : FreeSpec({

    "OpsLogBuffer" - {
        "should keep only recent entries within capacity" {
            OpsLogBuffer.clear()
            OpsLogBuffer.resize(50)
            for (i in 1..52) {
                OpsLogBuffer.append("WARN", "msg$i")
            }

            val recent = OpsLogBuffer.recent(50)
            recent shouldHaveSize 50
            recent.first().message shouldBe "msg3"
            recent.last().message shouldBe "msg52"
        }
    }
})

class OpsHttpServerTest : FreeSpec({

    "OpsHttpServer" - {
        val testConfig =
            TestOpsHttpConfig(
                enabled = true,
                token = "unit-test-token",
                bindHost = "127.0.0.1",
                bindPort = 0,
                consoleEnabled = true,
            )

        beforeSpec {
            OpsHttpConfig.loadForTest(testConfig)
        }

        "should reject requests without token" {
            val server = OpsHttpServer { testConfig }
            server.start()
            try {
                val conn = open("http://127.0.0.1:${server.actualPort}/ops/health")
                conn.responseCode shouldBe 401
                readBody(conn) shouldContain "Unauthorized"
            } finally {
                server.stop()
            }
        }

        "should shut down its worker executor on stop" {
            val executor = Executors.newSingleThreadExecutor()
            val server = OpsHttpServer({ executor }, { testConfig })
            server.start()

            server.stop()

            executor.isShutdown shouldBe true
        }

        "should replace and stop the previous executor when started again" {
            val executors = CopyOnWriteArrayList<java.util.concurrent.ExecutorService>()
            val server =
                OpsHttpServer(
                    executorFactory = {
                        Executors.newSingleThreadExecutor().also(executors::add)
                    },
                    configProvider = { testConfig },
                )
            server.start()
            try {
                server.start()

                executors shouldHaveSize 2
                executors.first().isShutdown shouldBe true
                executors.last().isShutdown shouldBe false
            } finally {
                server.stop()
            }
            executors.last().isShutdown shouldBe true
        }

        "should accept authorized requests to index" {
            val server = OpsHttpServer { testConfig }
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/",
                        token = testConfig.token,
                    )
                conn.responseCode shouldBe 200
                readBody(conn) shouldContain "\"routes\""
            } finally {
                server.stop()
            }
        }

        "should expose the economy audit route only when its read gate is enabled" {
            val enabled = testConfig.copy(economyAuditReadEnabled = true)
            val disabled = testConfig.copy(economyAuditReadEnabled = false)

            listOf(enabled to true, disabled to false).forEach { (config, expected) ->
                val server = OpsHttpServer { config }
                server.start()
                try {
                    val conn = open("http://127.0.0.1:${server.actualPort}/ops/", token = config.token)
                    readBody(conn).contains("/ops/economy/audit") shouldBe expected
                } finally {
                    server.stop()
                }
            }
        }

        "should validate economy audit query before reading the ledger" {
            val enabled = testConfig.copy(economyAuditReadEnabled = true)
            val server = OpsHttpServer { enabled }
            server.start()
            try {
                val conn = open("http://127.0.0.1:${server.actualPort}/ops/economy/audit?hours=0", token = enabled.token)
                conn.responseCode shouldBe 400
                readBody(conn) shouldContain "hours must be 1..744"
            } finally {
                server.stop()
            }
        }

        "should reject mixed or invalid absolute economy audit windows" {
            val enabled = testConfig.copy(economyAuditReadEnabled = true)
            val server = OpsHttpServer { enabled }
            server.start()
            try {
                val mixed =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/economy/audit?hours=24&since_epoch_ms=1",
                        token = enabled.token,
                    )
                mixed.responseCode shouldBe 400
                readBody(mixed) shouldContain "mutually exclusive"

                val expired =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/economy/audit?since_epoch_ms=1",
                        token = enabled.token,
                    )
                expired.responseCode shouldBe 400
                readBody(expired) shouldContain "within the last 31 days"
            } finally {
                server.stop()
            }
        }

        "should expose contract reconciliation reads and hide apply behind its own gate" {
            val readsOnly =
                testConfig.copy(
                    contractReconciliationReadEnabled = true,
                    contractReconciliationWriteEnabled = false,
                )
            val server = OpsHttpServer { readsOnly }
            server.start()
            try {
                val index = open("http://127.0.0.1:${server.actualPort}/ops/", token = readsOnly.token)
                val routes = readBody(index)
                routes shouldContain "GET /ops/economy/contracts/reconciliations"
                routes shouldContain "POST /ops/economy/contracts/reconciliations/preview"
                routes.contains("POST /ops/economy/contracts/reconciliations/apply") shouldBe false

                val apply =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/economy/contracts/reconciliations/apply",
                        method = "POST",
                        token = readsOnly.token,
                        body = "{}",
                    )
                apply.responseCode shouldBe 403
                readBody(apply) shouldContain "apply disabled"
            } finally {
                server.stop()
            }
        }

        "should reject malformed reconciliation preview before touching journal state" {
            val readsOnly =
                testConfig.copy(
                    contractReconciliationReadEnabled = true,
                    contractReconciliationWriteEnabled = false,
                )
            val server = OpsHttpServer { readsOnly }
            server.start()
            try {
                val preview =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/economy/contracts/reconciliations/preview",
                        method = "POST",
                        token = readsOnly.token,
                        body = """{"submissionId":"unsafe","expectedRevision":0,"resolution":"payment_confirmed","operatorId":"ops","operatorEvidence":"checked","idempotencyKey":"reconcile-safe","rawPath":"/ops/console"}""",
                    )
                preview.responseCode shouldBe 409
                readBody(preview) shouldContain "unknown fields: rawPath"
            } finally {
                server.stop()
            }
        }

        "should discover LuckPerms routes and hide disabled writes" {
            val readsOnly =
                testConfig.copy(
                    luckpermsGroupsReadEnabled = true,
                    luckpermsUsersReadEnabled = true,
                    luckpermsGroupsWriteEnabled = false,
                    luckpermsUsersWriteEnabled = false,
                    luckpermsMigrationsEnabled = false,
                )
            val server = OpsHttpServer { readsOnly }
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/",
                        token = readsOnly.token,
                    )
                val body = readBody(conn)
                body shouldContain "GET /ops/luckperms/groups"
                body shouldContain "POST /ops/luckperms/check"
                body.contains("/ops/luckperms/migrations/apply") shouldBe false
            } finally {
                server.stop()
            }
        }

        "should reject LuckPerms point preview when write gate is disabled" {
            val readsOnly =
                testConfig.copy(
                    luckpermsGroupsWriteEnabled = false,
                    luckpermsUsersWriteEnabled = false,
                )
            val server = OpsHttpServer { readsOnly }
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/luckperms/subjects/group/builder/preview",
                        method = "POST",
                        token = readsOnly.token,
                        body = """{"version":1,"reason":"test","operations":[{"op":"set_permission","permission":"example.node"}]}""",
                    )
                conn.responseCode shouldBe 403
                readBody(conn) shouldContain "enabled only on spawn"
            } finally {
                server.stop()
            }
        }

        "should reject oversized request bodies" {
            val server = OpsHttpServer { testConfig }
            server.start()
            try {
                val oversizedBody = "x".repeat(OPS_MAX_REQUEST_BODY_BYTES + 1)
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/console",
                        method = "POST",
                        token = testConfig.token,
                        body = oversizedBody,
                    )

                conn.responseCode shouldBe 413
                readBody(conn) shouldContain "Request body too large"
            } finally {
                server.stop()
            }
        }

        "should block console when disabled in config" {
            val locked = testConfig.copy(consoleEnabled = false)
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/console",
                        method = "POST",
                        token = locked.token,
                        body = """{"command":"say hi"}""",
                    )
                conn.responseCode shouldBe 403
            } finally {
                server.stop()
            }
        }

        "should block message when disabled in config" {
            val locked = testConfig.copy(messagesEnabled = false)
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/message",
                        method = "POST",
                        token = locked.token,
                        body = """{"channel":"broadcast","text":"hi"}""",
                    )
                conn.responseCode shouldBe 403
            } finally {
                server.stop()
            }
        }

        "should block run-as when disabled in config" {
            val locked = testConfig.copy(runAsEnabled = false)
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/run-as",
                        method = "POST",
                        token = locked.token,
                        body = """{"player":"Steve","command":"spawn"}""",
                    )
                conn.responseCode shouldBe 403
            } finally {
                server.stop()
            }
        }

        "should block CMI kit writes when disabled in config" {
            val locked = testConfig.copy(cmiKitsWriteEnabled = false)
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/cmi/kits/menu",
                        method = "PUT",
                        token = locked.token,
                        body =
                            """
                            {
                              "display":"Menu",
                              "icon":{"material":"CLOCK"},
                              "commands":["say test"]
                            }
                            """.trimIndent(),
                    )
                conn.responseCode shouldBe 403
                readBody(conn) shouldContain "CMI kit writes disabled"
            } finally {
                server.stop()
            }
        }

        "should gate CMI hologram reads and writes independently" {
            val locked =
                testConfig.copy(
                    cmiHologramsReadEnabled = false,
                    cmiHologramsWriteEnabled = false,
                )
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/cmi/holograms",
                        token = locked.token,
                    )
                read.responseCode shouldBe 403
                readBody(read) shouldContain "CMI hologram read endpoints disabled"

                val preview =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/cmi/holograms/preview",
                        method = "POST",
                        token = locked.token,
                        body = """{"name":"test","lines":["hello"]}""",
                    )
                preview.responseCode shouldBe 403
                readBody(preview) shouldContain "CMI hologram preview disabled"

                val upsert =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/cmi/holograms/test",
                        method = "PUT",
                        token = locked.token,
                        body = """{"lines":["hello"]}""",
                    )
                upsert.responseCode shouldBe 403
                readBody(upsert) shouldContain "CMI hologram writes disabled"

                val delete =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/cmi/holograms/test",
                        method = "DELETE",
                        token = locked.token,
                    )
                delete.responseCode shouldBe 403
                readBody(delete) shouldContain "CMI hologram writes disabled"
            } finally {
                server.stop()
            }
        }

        "should block scheduled command writes when disabled in config" {
            val locked = testConfig.copy(scheduledCommandsWriteEnabled = false)
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val put =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/scheduled-commands/weekend",
                        method = "PUT",
                        token = locked.token,
                        body =
                            """
                            {
                              "command":"say weekend",
                              "servers":["spawn"],
                              "schedule":{"type":"interval","every":"30m"}
                            }
                            """.trimIndent(),
                    )
                put.responseCode shouldBe 403
                readBody(put) shouldContain "Scheduled command writes disabled"

                val delete =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/scheduled-commands/weekend",
                        method = "DELETE",
                        token = locked.token,
                    )
                delete.responseCode shouldBe 403
                readBody(delete) shouldContain "Scheduled command writes disabled"
            } finally {
                server.stop()
            }
        }

        "should block scheduled command reads when disabled in config" {
            val locked = testConfig.copy(scheduledCommandsReadEnabled = false)
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val conn =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/scheduled-commands",
                        token = locked.token,
                    )
                conn.responseCode shouldBe 403
                readBody(conn) shouldContain "Scheduled command read endpoints disabled"
            } finally {
                server.stop()
            }
        }

        "should reject unsafe scheduled command ids before config access" {
            val enabled =
                testConfig.copy(
                    scheduledCommandsReadEnabled = true,
                    scheduledCommandsWriteEnabled = true,
                )
            val server = OpsHttpServer { enabled }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/scheduled-commands/bad.id",
                        token = enabled.token,
                    )
                read.responseCode shouldBe 400
                readBody(read) shouldContain "ID:"

                val delete =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/scheduled-commands/bad.id",
                        method = "DELETE",
                        token = enabled.token,
                    )
                delete.responseCode shouldBe 400
                readBody(delete) shouldContain "ID:"
            } finally {
                server.stop()
            }
        }

        "should reject a non-string scheduled command preview id" {
            val enabled = testConfig.copy(scheduledCommandsReadEnabled = true)
            val server = OpsHttpServer { enabled }
            server.start()
            try {
                val preview =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/scheduled-commands/preview",
                        method = "POST",
                        token = enabled.token,
                        body =
                            """
                            {
                              "id":{"nested":"bad"},
                              "command":"say test",
                              "schedule":{"type":"interval","every":"30m"}
                            }
                            """.trimIndent(),
                    )
                preview.responseCode shouldBe 400
                readBody(preview) shouldContain "id string"

                val unsafe =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/scheduled-commands/preview",
                        method = "POST",
                        token = enabled.token,
                        body =
                            """
                            {
                              "id":"bad.id",
                              "command":"say test",
                              "schedule":{"type":"interval","every":"30m"}
                            }
                            """.trimIndent(),
                    )
                unsafe.responseCode shouldBe 400
                readBody(unsafe) shouldContain "ID:"
            } finally {
                server.stop()
            }
        }

        "should gate location pool reads and writes independently" {
            val locked =
                testConfig.copy(
                    locationPoolsReadEnabled = false,
                    locationPoolsWriteEnabled = false,
                )
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/location-pools",
                        token = locked.token,
                    )
                read.responseCode shouldBe 403
                readBody(read) shouldContain "Location pool read endpoints disabled"

                val write =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/location-pools/event_spawn",
                        method = "PUT",
                        token = locked.token,
                        body =
                            """
                            {
                              "locations":[
                                {"server":"spawn","world":"spawn","x":1,"y":64,"z":1}
                              ]
                            }
                            """.trimIndent(),
                    )
                write.responseCode shouldBe 403
                readBody(write) shouldContain "Location pool writes disabled"
            } finally {
                server.stop()
            }
        }

        "should gate item preset reads and writes independently" {
            val locked =
                testConfig.copy(
                    itemPresetsReadEnabled = false,
                    itemPresetsWriteEnabled = false,
                )
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/item-presets",
                        token = locked.token,
                    )
                read.responseCode shouldBe 403
                readBody(read) shouldContain "Item preset read endpoints disabled"

                val write =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/item-presets/content_reward",
                        method = "PUT",
                        token = locked.token,
                        body = """{"type":"preset","item":{"material":"DIAMOND"}}""",
                    )
                write.responseCode shouldBe 403
                readBody(write) shouldContain "Item preset writes disabled"
            } finally {
                server.stop()
            }
        }

        "should reject unsafe item preset ids before config access" {
            val enabled =
                testConfig.copy(
                    itemPresetsReadEnabled = true,
                    itemPresetsWriteEnabled = true,
                )
            val server = OpsHttpServer { enabled }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/item-presets/bad.id",
                        token = enabled.token,
                    )
                read.responseCode shouldBe 400
                readBody(read) shouldContain "Item preset ID"

                val delete =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/item-presets/bad.id",
                        method = "DELETE",
                        token = enabled.token,
                    )
                delete.responseCode shouldBe 400
                readBody(delete) shouldContain "Item preset ID"
            } finally {
                server.stop()
            }
        }

        "should gate native preset giving with the item mutation flag" {
            val locked = testConfig.copy(itemsGiveEnabled = false)
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val give =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/player/Steve/give-preset",
                        method = "POST",
                        token = locked.token,
                        body = """{"preset":"golden_apple","amount":2}""",
                    )
                give.responseCode shouldBe 403
                readBody(give) shouldContain "Item give endpoint disabled"
            } finally {
                server.stop()
            }
        }

        "should gate treasure pool reads and writes independently" {
            val locked =
                testConfig.copy(
                    treasurePoolsReadEnabled = false,
                    treasurePoolsWriteEnabled = false,
                )
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/treasure-pools",
                        token = locked.token,
                    )
                read.responseCode shouldBe 403
                readBody(read) shouldContain "Treasure pool read endpoints disabled"

                val write =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/treasure-pools/event_rewards",
                        method = "PUT",
                        token = locked.token,
                        body = """{"treasures":[]}""",
                    )
                write.responseCode shouldBe 403
                readBody(write) shouldContain "Treasure pool writes disabled"
            } finally {
                server.stop()
            }
        }

        "should reject unsafe treasure pool ids before native catalog access" {
            val enabled =
                testConfig.copy(
                    treasurePoolsReadEnabled = true,
                    treasurePoolsWriteEnabled = true,
                )
            val server = OpsHttpServer { enabled }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/treasure-pools/bad.id",
                        token = enabled.token,
                    )
                read.responseCode shouldBe 400
                readBody(read) shouldContain "Treasure pool ID"

                val delete =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/treasure-pools/bad.id",
                        method = "DELETE",
                        token = enabled.token,
                    )
                delete.responseCode shouldBe 400
                readBody(delete) shouldContain "Treasure pool ID"
            } finally {
                server.stop()
            }
        }

        "should reject unsafe location pool ids before Bukkit access" {
            val enabled =
                testConfig.copy(
                    locationPoolsReadEnabled = true,
                    locationPoolsWriteEnabled = true,
                )
            val server = OpsHttpServer { enabled }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/location-pools/bad.id",
                        token = enabled.token,
                    )
                read.responseCode shouldBe 400
                readBody(read) shouldContain "Location pool ID"

                val delete =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/location-pools/bad.id",
                        method = "DELETE",
                        token = enabled.token,
                    )
                delete.responseCode shouldBe 400
                readBody(delete) shouldContain "Location pool ID"
            } finally {
                server.stop()
            }
        }

        "should gate NPC reads and writes independently" {
            val locked =
                testConfig.copy(
                    npcsReadEnabled = false,
                    npcsWriteEnabled = false,
                )
            val server = OpsHttpServer { locked }
            server.start()
            try {
                val read =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/npcs",
                        token = locked.token,
                    )
                read.responseCode shouldBe 403
                readBody(read) shouldContain "NPC read endpoints disabled"

                val preview =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/npcs/preview",
                        method = "POST",
                        token = locked.token,
                        body = """{"world":"spawn","x":0,"z":0}""",
                    )
                preview.responseCode shouldBe 403
                readBody(preview) shouldContain "NPC read endpoints disabled"

                val upsert =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/npcs",
                        method = "PUT",
                        token = locked.token,
                        body = """{"name":"Test","location":{"world":"spawn","x":0,"z":0}}""",
                    )
                upsert.responseCode shouldBe 403
                readBody(upsert) shouldContain "NPC writes disabled"

                val delete =
                    open(
                        "http://127.0.0.1:${server.actualPort}/ops/npcs/42",
                        method = "DELETE",
                        token = locked.token,
                    )
                delete.responseCode shouldBe 403
                readBody(delete) shouldContain "NPC writes disabled"
            } finally {
                server.stop()
            }
        }

    }
})

class OpsRequestBodyTest : FreeSpec({

    "readOpsRequestBody" - {
        "should accept a body exactly at the configured limit" {
            readOpsRequestBody(
                ByteArrayInputStream("1234".toByteArray(StandardCharsets.UTF_8)),
                maxBytes = 4,
            ) shouldBe "1234"
        }

        "should reject a body over the configured limit" {
            shouldThrow<OpsRequestBodyTooLargeException> {
                readOpsRequestBody(
                    ByteArrayInputStream("12345".toByteArray(StandardCharsets.UTF_8)),
                    maxBytes = 4,
                )
            }
        }
    }
})

class OpsStartupLogTapTest : FreeSpec({

    "OpsStartupLogTap" - {
        "should match plugin startup error messages" {
            OpsStartupLogTap.matches("Could not load plugin 'Foo' in folder 'plugins'") shouldBe true
            OpsStartupLogTap.matches("Error occurred while enabling Foo") shouldBe true
            OpsStartupLogTap.matches("Player joined") shouldBe false
        }
    }
})

private fun open(
    url: String,
    method: String = "GET",
    token: String? = null,
    body: String? = null,
): HttpURLConnection {
    val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = 5_000
    conn.readTimeout = 5_000
    token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
    if (body != null) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
    }
    return conn
}

private fun readBody(conn: HttpURLConnection): String {
    val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
    return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
}
