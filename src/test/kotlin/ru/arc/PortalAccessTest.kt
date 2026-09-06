package ru.arc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PortalAccessTest {
    @Test
    fun `personal callback never runs for a visitor`() {
        val owner = java.util.UUID.randomUUID()
        val visitor = io.mockk.mockk<org.bukkit.entity.Player>()
        io.mockk.every { visitor.uniqueId } returns java.util.UUID.randomUUID()
        var calls = 0
        val data = PortalData(ownerAction = { calls++ })
        assertEquals(false, data.accepts(owner, visitor))
        data.executeOwnerAction(owner, visitor)
        assertEquals(0, calls)
        io.mockk.every { visitor.uniqueId } returns owner
        assertEquals(true, data.accepts(owner, visitor))
        data.executeOwnerAction(owner, visitor)
        assertEquals(1, calls)
    }

    @Test
    fun `owner can always enter own portal`() {
        assertEquals(
            PortalAccess.ALLOWED,
            evaluatePortalAccess(
                isOwner = true,
                visitorAllowsForeignPortals = false,
                ownerAllowsVisitors = false,
            ),
        )
    }

    @Test
    fun `visitor must allow foreign portals`() {
        assertEquals(
            PortalAccess.VISITOR_DENIED,
            evaluatePortalAccess(
                isOwner = false,
                visitorAllowsForeignPortals = false,
                ownerAllowsVisitors = true,
            ),
        )
    }

    @Test
    fun `owner must allow visitors`() {
        assertEquals(
            PortalAccess.OWNER_DENIED,
            evaluatePortalAccess(
                isOwner = false,
                visitorAllowsForeignPortals = true,
                ownerAllowsVisitors = false,
            ),
        )
    }

    @Test
    fun `visitor enters when both permissions allow it`() {
        assertEquals(
            PortalAccess.ALLOWED,
            evaluatePortalAccess(
                isOwner = false,
                visitorAllowsForeignPortals = true,
                ownerAllowsVisitors = true,
            ),
        )
    }
}
