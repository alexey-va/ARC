package ru.arc.ops

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

class OpsStaffCommandHttpTest : FreeSpec({
    fun request(port: Int, path: String, token: String? = "test-token", body: ByteArray = "{}".toByteArray()): Int {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.doOutput = true
        connection.outputStream.use { it.write(body) }
        return try { connection.responseCode } finally { connection.disconnect() }
    }

    "private listener exposes only exact staff route with both bearer and source address checks" {
        val config = TestOpsHttpConfig(
            staffCommandsEnabled = true, staffCommandBindHost = "127.0.0.1", staffCommandAllowedAddress = "127.0.0.1",
        )
        val server = OpsHttpServer(configProvider = { config })
        try {
            server.start()
            val port = requireNotNull(server.actualStaffCommandPort)
            request(port, "/ops") shouldBe 404
            request(port, "/ops/console") shouldBe 404
            request(port, "/ops/staff-command/") shouldBe 404
            request(port, "/ops/staff-command", token = null) shouldBe 401
            request(port, "/ops/staff-command") shouldBe 400
            request(port, "/ops/staff-command?extra=true") shouldBe 400
            request(port, "/ops/staff-command", body = byteArrayOf(0x22, 0xc3.toByte(), 0x28, 0x22)) shouldBe 400
            request(port, "/ops/staff-command", body = ByteArray(4097) { 0x20 }) shouldBe 413
        } finally {
            server.stop()
            server.stop()
        }
        val wrongSource = OpsHttpServer(configProvider = {
            TestOpsHttpConfig(staffCommandsEnabled = true, staffCommandBindHost = "127.0.0.1", staffCommandAllowedAddress = "127.0.0.2")
        })
        try {
            wrongSource.start()
            request(requireNotNull(wrongSource.actualStaffCommandPort), "/ops/staff-command") shouldBe 403
        } finally {
            wrongSource.stop()
        }
    }

    "second bind failure closes the first listener and missing source fails closed" {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 50, loopback).use { occupied ->
            val primaryPort = ServerSocket(0, 50, loopback).use { it.localPort }
            val server = OpsHttpServer(configProvider = {
                TestOpsHttpConfig(
                    bindPort = primaryPort, staffCommandsEnabled = true, staffCommandBindHost = "127.0.0.1",
                    staffCommandBindPort = occupied.localPort, staffCommandAllowedAddress = "127.0.0.1",
                )
            })
            shouldThrow<java.net.BindException> { server.start() }
            ServerSocket(primaryPort, 50, loopback).use { it.isBound shouldBe true }
            server.actualStaffCommandPort shouldBe null
            server.stop()
        }
        val missingSource = OpsHttpServer(configProvider = {
            TestOpsHttpConfig(staffCommandsEnabled = true, staffCommandBindHost = "127.0.0.1")
        })
        shouldThrow<IllegalArgumentException> { missingSource.start() }
        missingSource.actualStaffCommandPort shouldBe null
        missingSource.stop()
    }
})
