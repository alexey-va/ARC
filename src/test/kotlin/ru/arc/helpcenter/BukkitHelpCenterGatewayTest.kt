package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import ru.arc.xserver.playerlist.PlayerManager
import java.util.UUID

class BukkitHelpCenterGatewayTest : StringSpec({
    afterTest { PlayerManager.readMessage("[]") }

    "reads proxy-wide players with their server from the ARC player manager" {
        val survivalPlayer = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val spawnPlayer = UUID.fromString("00000000-0000-0000-0000-000000000022")
        PlayerManager.readMessage(
            """[
                {"username":"Limonka","server":"survival","uuid":"$survivalPlayer","joinTime":1},
                {"username":"Steve","server":"spawn","uuid":"$spawnPlayer","joinTime":2}
            ]""".trimIndent(),
        )

        BukkitHelpCenterGateway().onlinePlayers()
            .sortedBy { it.name }
            .shouldContainExactly(
                HelpCenterPlayer(survivalPlayer, "Limonka", "survival"),
                HelpCenterPlayer(spawnPlayer, "Steve", "spawn"),
            )
    }
})
