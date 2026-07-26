package ru.arc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PortalAccessTest {
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
