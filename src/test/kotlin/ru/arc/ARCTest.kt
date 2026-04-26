package ru.arc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ARCTest : TestBase() {

    @Test
    fun testPluginLoad() {
        assertNotNull(plugin, "Plugin should be loaded")
        assertEquals(plugin, ARC.instance, "Static plugin reference should be set")
    }

    @Test
    fun testPluginEnable() {
        assertTrue(plugin.isEnabled, "Plugin should be enabled")
    }

    @Test
    fun testServerName() {
        assertEquals("test-server", ARC.serverName, "Server name should be set")
    }

    @Test
    fun testServerMock() {
        assertNotNull(server, "Server mock should be created")
    }
}

