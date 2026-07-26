package ru.arc.common

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.ARC
import ru.arc.TestBase

class ServerLocationTest : TestBase() {

    private lateinit var world: WorldMock

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        world = server.addSimpleWorld("test-world")
    }

    @Test
    fun `of rejects null location`() {
        assertThrows<IllegalArgumentException> {
            ServerLocation.of(null)
        }
    }

    @Test
    fun `of captures server world coordinates and rotation`() {
        val result = ServerLocation.of(Location(world, 10.5, -20.0, 30.5, 45.0f, 90.0f))

        assertEquals(
            ServerLocation(
                server = "test-server",
                world = "test-world",
                x = 10.5,
                y = -20.0,
                z = 30.5,
                yaw = 45.0f,
                pitch = 90.0f,
            ),
            result,
        )
    }

    @Test
    fun `toLocation restores a known world and all values`() {
        val result = ServerLocation(
            server = "test-server",
            world = "test-world",
            x = 10.5,
            y = 20.0,
            z = 30.5,
            yaw = 45.0f,
            pitch = 90.0f,
        ).toLocation()

        requireNotNull(result)
        assertEquals(world, result.world)
        assertEquals(10.5, result.x)
        assertEquals(20.0, result.y)
        assertEquals(30.5, result.z)
        assertEquals(45.0f, result.yaw)
        assertEquals(90.0f, result.pitch)
    }

    @Test
    fun `toLocation returns null without a resolvable world`() {
        assertNull(ServerLocation(world = null).toLocation())
        assertNull(ServerLocation(world = "missing-world").toLocation())
    }

    @Test
    fun `distance uses all three dimensions`() {
        val origin = ServerLocation(server = "test-server", world = "test-world")

        assertEquals(5.0, origin.distance(Location(world, 3.0, 4.0, 0.0)))
    }

    @Test
    fun `distance is unavailable for another server or world`() {
        assertNull(
            ServerLocation(server = "survival", world = "test-world")
                .distance(Location(world, 1.0, 2.0, 3.0)),
        )
        assertNull(
            ServerLocation(server = "test-server", world = "another-world")
                .distance(Location(world, 1.0, 2.0, 3.0)),
        )
        assertNull(
            ServerLocation(server = "test-server", world = "test-world")
                .distance(Location(null, 1.0, 2.0, 3.0)),
        )
    }

    @Test
    fun `server comparison is case insensitive`() {
        ARC.serverName = "Spawn"

        assertTrue(ServerLocation(server = "spawn").isSameServer())
        assertTrue(ServerLocation(server = "SPAWN").isSameServer())
    }

    @Test
    fun `missing blank or different server does not match`() {
        ARC.serverName = "spawn"

        assertFalse(ServerLocation(server = null).isSameServer())
        assertFalse(ServerLocation(server = "").isSameServer())
        assertFalse(ServerLocation(server = "survival").isSameServer())
    }
}
