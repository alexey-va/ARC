package ru.arc.rtp

import io.github.dailystruggle.rtp.api.RTPAPI
import io.github.dailystruggle.rtp.api.RTPResult
import io.github.dailystruggle.rtp.api.RtpTarget
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.ARC
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BiFunction

class LeafRtpTransferTest :
    FreeSpec({
        "submits the selected region once and reports asynchronous provider failures" {
            val player = mockk<Player>()
            val world = mockk<World>()
            val plugin = mockk<ARC>()
            val playerId = UUID.randomUUID()
            val pending = CompletableFuture<RTPResult>()
            var target: RtpTarget? = null
            var calls = 0
            var failure: String? = null
            every { player.uniqueId } returns playerId
            every { player.name } returns "Steve"
            every { player.isOnline } returns true
            every { player.hasPermission("rtp.region") } returns true
            every { world.name } returns "vanilla"
            every { plugin.whenCompleteOnMain<RTPResult>(any(), any()) } answers {
                val stage = firstArg<CompletionStage<RTPResult>>()
                val callback = secondArg<(RTPResult?, Throwable?) -> Unit>()
                stage.whenComplete { result, error -> callback(result, error) }
                Unit
            }
            val previousPlugin = ARC.plugin
            val previousDelegate = RTPAPI.teleportDelegate
            try {
                ARC.plugin = plugin
                RTPAPI.teleportDelegate = BiFunction { id, requested ->
                    id shouldBe playerId
                    calls++
                    target = requested
                    pending
                }

                val result = LeafRtpTransfer.start(player, world) { failure = it }
                result shouldBe FirstRtpResult.Started(RtpProvider.LEAFRTP, "LeafRTP API")
                calls shouldBe 1
                target?.kind() shouldBe RtpTarget.Kind.REGION
                target?.name() shouldBe "vanilla"
                failure shouldBe null

                pending.complete(RTPResult.failure(RTPResult.Reason.COOLDOWN, "cooldown"))
                failure shouldBe "ещё действует задержка между телепортациями"
                calls shouldBe 1
            } finally {
                RTPAPI.teleportDelegate = previousDelegate
                ARC.plugin = previousPlugin
            }
        }
    })
