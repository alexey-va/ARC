package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

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
