package ru.arc.furniturehitbox

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import java.util.UUID

class FurnitureHitboxHintTest : StringSpec({
    "sneaking resolves the native target for any world and release clears without another query" {
        val fixture = hitboxFixture()
        var queries = 0
        val frames = RecordingHitboxOutline()
        val hint = FurnitureHitboxHint(FurnitureHitboxSource { queries++; fixture.target }, frames) { _, error -> throw error }

        hint.update(fixture.player, true)
        frames.shown shouldBe listOf(fixture.target)
        queries shouldBe 1
        every { fixture.player.isSneaking } returns false
        hint.update(fixture.player, false)
        frames.cleared shouldBe listOf(fixture.player.uniqueId)
        queries shouldBe 1
    }

    "aim away and stale roots clear the old frame instead of leaving a misleading box" {
        val fixture = hitboxFixture()
        var selected: FurnitureHitboxTarget? = fixture.target
        val frames = RecordingHitboxOutline()
        val hint = FurnitureHitboxHint(FurnitureHitboxSource { selected }, frames) { _, error -> throw error }
        hint.update(fixture.player, true)
        selected = null
        hint.update(fixture.player, true)
        every { fixture.root.isValid } returns false
        selected = fixture.target
        hint.update(fixture.player, true)
        frames.shown.size shouldBe 1
        frames.cleared.size shouldBe 2
    }

    "dead spectator and other-world targets never show a frame" {
        val fixture = hitboxFixture()
        val frames = RecordingHitboxOutline()
        var queries = 0
        val hint = FurnitureHitboxHint(FurnitureHitboxSource { queries++; fixture.target }, frames) { _, error -> throw error }
        every { fixture.player.isDead } returns true
        hint.update(fixture.player, true)
        every { fixture.player.isDead } returns false
        every { fixture.player.gameMode } returns GameMode.SPECTATOR
        hint.update(fixture.player, true)
        queries shouldBe 0
        every { fixture.player.gameMode } returns GameMode.SURVIVAL
        every { fixture.root.world } returns mockk<World> { every { uid } returns UUID.randomUUID() }
        hint.update(fixture.player, true)
        frames.shown shouldBe emptyList()
        frames.cleared.size shouldBe 3
    }

    "failed viewer is cleared and isolated until reset while another viewer still works" {
        val first = hitboxFixture()
        val second = hitboxFixture()
        var fail = true
        var failures = 0
        val frames = RecordingHitboxOutline()
        val hint = FurnitureHitboxHint(FurnitureHitboxSource { player ->
            if (player == first.player && fail) error("native target unavailable")
            if (player == first.player) first.target else second.target
        }, frames) { _, _ -> failures++ }
        hint.update(first.player, true)
        hint.update(first.player, true)
        hint.update(second.player, true)
        failures shouldBe 1
        frames.shown shouldBe listOf(second.target)
        fail = false
        hint.reset(first.player)
        hint.update(first.player, true)
        frames.shown shouldBe listOf(second.target, first.target)
        hint.close()
        frames.closed shouldBe true
    }
})

private data class HitboxFixture(val player: Player, val root: Entity, val target: FurnitureHitboxTarget)

private fun hitboxFixture(): HitboxFixture {
    val worldId = UUID.randomUUID()
    val playerId = UUID.randomUUID()
    val world = mockk<World> { every { uid } returns worldId }
    val root = mockk<Entity> {
        every { isValid } returns true
        every { this@mockk.world } returns world
    }
    val player = mockk<Player> {
        every { uniqueId } returns playerId
        every { this@mockk.world } returns world
        every { isOnline } returns true
        every { isSneaking } returns true
        every { isDead } returns false
        every { gameMode } returns GameMode.SURVIVAL
    }
    return HitboxFixture(player, root, FurnitureHitboxTarget(root, BoundingBox(1.0, 2.0, 3.0, 2.0, 4.0, 4.0)))
}

private class RecordingHitboxOutline : FurnitureHitboxOutline {
    val shown = mutableListOf<FurnitureHitboxTarget>()
    val cleared = mutableListOf<UUID>()
    var closed = false
    override fun show(player: Player, target: FurnitureHitboxTarget) { shown += target }
    override fun clear(viewerId: UUID) { cleared += viewerId }
    override fun close() { closed = true }
}
