package ru.arc.landsui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.Enabled
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.land.enums.LandType
import me.angeschossen.lands.api.limits.Limit
import me.angeschossen.lands.api.player.LandPlayer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

class LandsClaimExpansionQuoteTest : StringSpec({
    "quotes only new chunks through the native calculator without loading terrain" {
        val f = ClaimQuoteFixture()
        var calls = 0
        val quote = f.preview { player, land, additional ->
            player shouldBe f.landPlayer
            land shouldBe f.land
            additional shouldBe 1
            calls++
            1234.5
        }
        every { f.integration.getLandByUnloadedChunk(f.world, any<Int>(), any<Int>()) } answers {
            if (secondArg<Int>() == 0 && thirdArg<Int>() == 0) null else f.land
        }

        quote.quote(f.player, f.target, 1) shouldBe 1234.5
        calls shouldBe 1
        verify(exactly = 9) { f.integration.getLandByUnloadedChunk(f.world, any<Int>(), any<Int>()) }
        verify(exactly = 0) { f.world.getChunkAt(any<Int>(), any<Int>()) }
    }

    "uses all 81 radius-four chunks and passes no land for a new settlement" {
        val f = ClaimQuoteFixture()
        every { f.landPlayer.getEditLand(false) } returns null
        val quote = f.preview { _, land, additional ->
            land shouldBe null
            additional shouldBe 81
            0.0
        }

        quote.quote(f.player, f.target, 4) shouldBe 0.0
        verify(exactly = 81) { f.integration.getLandByUnloadedChunk(f.world, any<Int>(), any<Int>()) }
    }

    "refreshes on size or quota change and expires the advisory cached fee" {
        val f = ClaimQuoteFixture()
        var amount = 1000.0
        var calls = 0
        val quote = f.preview { _, _, _ -> calls++; amount }

        quote.quote(f.player, f.target, 0) shouldBe 1000.0
        amount = 1100.0
        quote.quote(f.player, f.target, 0) shouldBe 1000.0
        calls shouldBe 1
        f.landChunks++
        quote.quote(f.player, f.target, 0) shouldBe 1100.0
        amount = 1200.0
        f.freeLimit++
        quote.quote(f.player, f.target, 0) shouldBe 1200.0
        amount = 1300.0
        f.now = 1_000_000_000L
        quote.quote(f.player, f.target, 0) shouldBe 1300.0
        calls shouldBe 4
    }

    "hides foreign overlaps and active native selections without a price call" {
        val f = ClaimQuoteFixture()
        var calls = 0
        val quote = f.preview { _, _, _ -> calls++; 1000.0 }
        val foreign = mockk<Land>()
        every { foreign.ulid } returns mockk<ULID>()
        every { f.integration.getLandByUnloadedChunk(f.world, any<Int>(), any<Int>()) } returns foreign

        quote.quote(f.player, f.target, 0) shouldBe null
        every { f.landPlayer.selection } returns mockk()
        quote.quote(f.player, f.target, 0) shouldBe null
        calls shouldBe 0
    }

    "preserves zero fees and disables only the preview after one native failure" {
        val f = ClaimQuoteFixture()
        var calls = 0
        val failures = mutableListOf<Throwable>()
        val failure = IllegalStateException("changed native ABI")
        val quote = LandsClaimExpansionQuote(f.integration, { _, _, _ ->
            calls++
            if (calls == 1) 0.0 else throw failure
        }, { f.now }, { failures += it })

        quote.quote(f.player, f.target, 0) shouldBe 0.0
        f.now = 1_000_000_000L
        quote.quote(f.player, f.target, 0) shouldBe null
        quote.quote(f.player, f.target, 0) shouldBe null
        calls shouldBe 2
        failures shouldBe listOf(failure)
    }

    "rejects an unverified native calculator version before loading implementation classes" {
        val loader = Land::class.java.classLoader
        shouldThrow<IllegalArgumentException> { nativeLandsClaimQuote(loader, "unverified") }
    }

    "binds the inspected Lands 8.6.6 calculator signatures".config(enabledOrReasonIf = {
        if (runCatching {
            Class.forName("me.angeschossen.lands.cKfg.RLHXh.RLHXh", false, Land::class.java.classLoader)
        }.isSuccess) Enabled.enabled
        else Enabled.disabled("Exact Lands compatibility check requires -PlandsJar=/path/to/Lands-8.6.6.jar")
    }) {
        nativeLandsClaimQuote(Land::class.java.classLoader, "8.6.6").shouldNotBeNull()
    }
})

private class ClaimQuoteFixture {
    val integration = mockk<LandsIntegration>()
    val landPlayer = mockk<LandPlayer>()
    val land = mockk<Land>()
    val world = mockk<World>()
    val player = mockk<Player>()
    val target = Location(world, 8.0, 64.0, 8.0)
    var landChunks = 4
    var freeLimit = 16
    var now = 0L

    init {
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        every { player.world } returns world
        every { player.isOnline } returns true
        every { integration.getWorld(world) } returns mockk()
        every { integration.getLandPlayer(id) } returns landPlayer
        every { integration.getLandByUnloadedChunk(world, any<Int>(), any<Int>()) } returns null
        every { landPlayer.selection } returns null
        every { landPlayer.getEditLand(false) } returns land
        every { landPlayer.getLimit(Limit.PLAYER_CHUNKS_FREE) } answers { freeLimit }
        every { land.exists() } returns true
        every { land.isTrusted(id) } returns true
        every { land.landType } returns LandType.LAND
        every { land.chunksAmount } answers { landChunks }
        every { land.ulid } returns mockk<ULID>()
    }

    fun preview(calculate: (LandPlayer, Land?, Int) -> Double) =
        LandsClaimExpansionQuote(integration, calculate, { now })
}
