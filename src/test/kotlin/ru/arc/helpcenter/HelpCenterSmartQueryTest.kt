package ru.arc.helpcenter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class HelpCenterSmartQueryTest {
    private val homes = listOf(
        HelpCenterHome("база", "survival", "world", 1, 64, 2),
        HelpCenterHome("склад", "survival", "world", 3, 70, 4),
    )
    private val players = listOf(
        HelpCenterPlayer(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Foll", "survival"),
        HelpCenterPlayer(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Alex", "classic"),
    )

    @Test
    fun `extracts destructive home action only for exact known home`() {
        assertEquals(
            HelpCenterResolvedQuery.Home(homes[0], HelpCenterHomeAction.DELETE),
            HelpCenterSmartQuery.resolve("удалить дом база", homes, players),
        )
        assertNull(HelpCenterSmartQuery.resolve("удалить дом неизвестный", homes, players))
    }

    @Test
    fun `extracts home relocation and teleport`() {
        assertEquals(
            HelpCenterResolvedQuery.Home(homes[1], HelpCenterHomeAction.RELOCATE),
            HelpCenterSmartQuery.resolve("перенести дом склад", homes, players),
        )
        assertEquals(
            HelpCenterResolvedQuery.Home(homes[0], HelpCenterHomeAction.TELEPORT),
            HelpCenterSmartQuery.resolve("дом база", homes, players),
        )
    }

    @Test
    fun `extracts exact network player and bounded payment`() {
        assertEquals(
            HelpCenterResolvedQuery.Player(players[0], HelpCenterPlayerAction.INVITE),
            HelpCenterSmartQuery.resolve("позвать Foll в приват", homes, players),
        )
        assertEquals(
            HelpCenterResolvedQuery.Player(players[1], HelpCenterPlayerAction.TELEPORT_TO),
            HelpCenterSmartQuery.resolve("телепортироваться к Alex", homes, players),
        )
        assertEquals(
            HelpCenterResolvedQuery.Player(players[0], HelpCenterPlayerAction.PAY, "500"),
            HelpCenterSmartQuery.resolve("перевести Foll 500", homes, players),
        )
        assertNull(HelpCenterSmartQuery.resolve("перевести Foll 12.345", homes, players))
    }

    @Test
    fun `does not guess ambiguous or partial entity`() {
        val ambiguous = players + HelpCenterPlayer(UUID.randomUUID(), "Folly", "classic")
        assertNull(HelpCenterSmartQuery.resolve("позвать Fol", homes, ambiguous))
        assertNull(HelpCenterSmartQuery.resolve("удалить дом", homes, players))
    }

    @Test
    fun `recognizes direct destination sections`() {
        assertEquals(
            HelpCenterResolvedQuery.Page(HelpCenterPage.ACTIVITIES, "dungeons"),
            HelpCenterSmartQuery.resolve("открыть данжи", homes, players),
        )
    }
}
