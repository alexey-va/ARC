@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.network

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import ru.arc.ARC
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration tests for RedisManager using Testcontainers.
 *
 * These tests require Docker to be running:
 * - Windows: Docker Desktop (auto-detected)
 * - macOS/Linux: Colima or Docker Desktop (auto-detected)
 *
 * Platform-specific setup:
 *
 * Windows (Docker Desktop):
 *   - Install and start Docker Desktop
 *   - Run: test-redis-windows.bat
 *   - Or: gradlew.bat test --tests 'RedisManagerIntegrationTest'
 *
 * macOS/Linux (Colima):
 *   - Use helper script: ./test-redis.sh
 *   - Or manually configure:
 *     docker context use colima
 *     export DOCKER_HOST="unix:///${HOME}/.colima/docker.sock"
 *     export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=${HOME}/.colima/docker.sock
 *     export TESTCONTAINERS_RYUK_DISABLED=true
 *     ./gradlew test --tests 'RedisManagerIntegrationTest'
 *
 * The test will auto-detect the Docker environment if env vars are not set.
 * See README-TESTCONTAINERS.md for detailed setup instructions.
 */
@Testcontainers
class RedisManagerIntegrationTest {

    companion object {
        init {
            // Auto-detect Docker environment (Colima for macOS/Linux, Docker Desktop for Windows)
            val os = System.getProperty("os.name").lowercase()
            val isWindows = os.contains("windows")
            val isMacOrLinux = os.contains("mac") || os.contains("linux")

            if (isMacOrLinux) {
                // Colima detection for macOS/Linux
                val home = System.getProperty("user.home")
                val colimaSocket = File(home, ".colima/docker.sock")
                val colimaSocketAlt = File(home, ".colima/default/docker.sock")

                val socket = when {
                    colimaSocket.exists() -> colimaSocket
                    colimaSocketAlt.exists() -> colimaSocketAlt
                    else -> null
                }

                if (socket != null) {
                    val socketPath = socket.absolutePath
                    if (System.getenv("DOCKER_HOST") == null) {
                        System.setProperty("DOCKER_HOST", "unix://$socketPath")
                    }
                    if (System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == null) {
                        System.setProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", socketPath)
                    }
                    if (System.getenv("TESTCONTAINERS_RYUK_DISABLED") == null) {
                        System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true")
                    }
                    println("Testcontainers: Auto-configured for Colima at $socketPath")
                } else {
                    println("Testcontainers: Using default Docker configuration (Docker Desktop or system Docker)")
                }
            } else if (isWindows) {
                // Docker Desktop on Windows uses named pipe: \\.\pipe\docker_engine
                // Testcontainers should auto-detect this, but we can explicitly set it
                val dockerHost = System.getenv("DOCKER_HOST")
                if (dockerHost == null) {
                    // Docker Desktop on Windows typically uses tcp://localhost:2375 or named pipe
                    // Testcontainers will auto-detect, but we can help with Ryuk
                    if (System.getenv("TESTCONTAINERS_RYUK_DISABLED") == null) {
                        // Ryuk usually works fine on Windows, but can be disabled if needed
                        // System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true")
                    }
                    println("Testcontainers: Using Docker Desktop on Windows (auto-detected)")
                } else {
                    println("Testcontainers: Using custom DOCKER_HOST: $dockerHost")
                }
            } else {
                println("Testcontainers: Using default Docker configuration")
            }
        }

        @Container
        @JvmStatic
        val redisContainer: GenericContainer<*> = GenericContainer(
            DockerImageName.parse("redis:7-alpine")
        ).apply {
            withExposedPorts(6379)
            withReuse(true) // Reuse container across test runs
        }
    }

    private lateinit var redisManager: RedisManager
    private val testServerName = "test-server"

    @BeforeEach
    fun setup() {
        ARC.serverName = testServerName
        val host = redisContainer.host
        val port = redisContainer.getMappedPort(6379)

        redisManager = RedisManager(host, port, null, null)

        // Wait for connection to be ready
        Thread.sleep(500)
    }

    @AfterEach
    fun teardown() {
        redisManager.close()
    }

    @Test
    fun `saveMap and loadMap work correctly`() {
        val map = mapOf(
            "field1" to "value1",
            "field2" to "value2",
            "field3" to "value3"
        )

        // saveMap is now async but doesn't return a future, so we wait a bit
        redisManager.saveMap("test:map", map)
        Thread.sleep(200) // Give async operation time to complete

        val loaded = redisManager.loadMap("test:map").get(2, TimeUnit.SECONDS)

        assertEquals(map, loaded)
    }

    @Test
    fun `saveMapEntries saves and deletes correctly`() {
        redisManager.saveMapEntries(
            "test:entries",
            "key1", "value1",
            "key2", "value2"
        ).get(2, TimeUnit.SECONDS)

        var loaded = redisManager.loadMap("test:entries").get(2, TimeUnit.SECONDS)
        assertEquals("value1", loaded["key1"])
        assertEquals("value2", loaded["key2"])

        // Delete key2
        redisManager.saveMapEntries("test:entries", "key2", null).get(2, TimeUnit.SECONDS)

        loaded = redisManager.loadMap("test:entries").get(2, TimeUnit.SECONDS)
        assertEquals("value1", loaded["key1"])
        assertNull(loaded["key2"])
    }

    @Test
    fun `pub sub works correctly`() {
        val received = AtomicReference<String>()
        val receivedOrigin = AtomicReference<String>()
        val latch = CountDownLatch(1)

        val listener = ChannelListener { channel, message, originServer ->
            received.set(message)
            receivedOrigin.set(originServer)
            latch.countDown()
        }

        redisManager.registerChannelUnique("test:pubsub", listener)
        redisManager.init()

        // Wait for subscription to start:
        // - INIT_DELAY_MS (1000ms) delay in init()
        // - Plus time for thread submission and subscribe() call
        // - onSubscribe is called synchronously during subscribe()
        Thread.sleep(1500)

        // Publish message
        redisManager.publish("test:pubsub", "test message")
        Thread.sleep(100) // Give publish time to complete

        // Wait for message to be received
        val receivedMessage = latch.await(5, TimeUnit.SECONDS)

        assertTrue(receivedMessage, "Message not received within timeout")
        assertEquals("test message", received.get(), "Received message doesn't match")
        assertEquals(testServerName, receivedOrigin.get(), "Origin server doesn't match")
    }

    @Test
    fun `concurrent operations are safe`() {
        val futures = (1..50).map { i ->
            redisManager.saveMapEntries(
                "test:concurrent",
                "key$i", "value$i"
            )
        }

        CompletableFuture.allOf(*futures.toTypedArray()).get(5, TimeUnit.SECONDS)

        val loaded = redisManager.loadMap("test:concurrent").get(2, TimeUnit.SECONDS)
        assertEquals(50, loaded.size)
    }
}

