package ru.arc.commands.arc.subcommands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.rtp.BackendRtpRequest
import ru.arc.rtp.NetworkRtpMode
import java.util.UUID

class RtpSubCommandTest :
    FreeSpec({
        "sends one normalized backend request for a player" {
            val player = mockk<Player>(relaxed = true)
            val playerId = UUID.randomUUID()
            val sent = mutableListOf<BackendRtpRequest>()
            var rejections = 0

            every { player.uniqueId } returns playerId

            RtpCommandHandler(
                send = { _, request -> sent += request },
                reject = { rejections++ },
            ).execute(player, arrayOf(" Mining "))

            sent shouldBe listOf(BackendRtpRequest.create(playerId, "mining"))
            rejections shouldBe 0
        }

        "defaults a missing world to survival" {
            val player = mockk<Player>(relaxed = true)
            val playerId = UUID.randomUUID()
            val sent = mutableListOf<BackendRtpRequest>()

            every { player.uniqueId } returns playerId

            RtpCommandHandler(
                send = { _, request -> sent += request },
                reject = {},
            ).execute(player, emptyArray())

            sent shouldBe listOf(BackendRtpRequest.create(playerId, "survival"))
        }

        "preserves first-entry behavior only behind the explicit flag" {
            val player = mockk<Player>(relaxed = true)
            val playerId = UUID.randomUUID()
            val sent = mutableListOf<BackendRtpRequest>()

            every { player.uniqueId } returns playerId

            RtpCommandHandler(
                send = { _, request -> sent += request },
                reject = {},
            ).execute(player, arrayOf("mining", "--only-if-first"))

            sent shouldBe
                listOf(
                    BackendRtpRequest.create(
                        playerId,
                        "mining",
                        NetworkRtpMode.FIRST_ENTRY,
                    ),
                )
        }

        "rejects console, missing, extra, and unsafe arguments" {
            val console = mockk<CommandSender>(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            var sends = 0
            var rejections = 0
            val handler =
                RtpCommandHandler(
                    send = { _, _ -> sends++ },
                    reject = { rejections++ },
                )

            handler.execute(console, arrayOf("mining"))
            handler.execute(player, arrayOf("mining", "extra"))
            handler.execute(player, arrayOf("../world"))

            sends shouldBe 0
            rejections shouldBe 3
        }

        "offers the three public worlds for tab completion" {
            RtpSubCommand.tabComplete(
                mockk(relaxed = true),
                arrayOf("v"),
            ) shouldBe listOf("vanilla")
        }

        "offers the first-entry flag as the second argument" {
            RtpSubCommand.tabComplete(
                mockk(relaxed = true),
                arrayOf("vanilla", ""),
            ) shouldBe listOf("--only-if-first")
        }
    })
