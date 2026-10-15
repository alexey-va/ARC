package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.EliteSkillXpGainEvent
import com.magmaguy.elitemobs.mobconstructor.EliteEntity
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.CombatLevelDisplay
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPBar
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import java.util.UUID

class DungeonSharedSkillXpTest : FreeSpec({
    "weaker skills catch up before the remainder is divided with odd XP kept native" {
        planSharedSkillXp(0, 0, 5) shouldBe SharedSkillSplit(3, 2)
        planSharedSkillXp(100, 0, 40) shouldBe SharedSkillSplit(1, 39)
        planSharedSkillXp(0, 100, 40) shouldBe null
        planSharedSkillXp(100, 0, 150) shouldBe SharedSkillSplit(25, 125)
        planSharedSkillXp(0, 100, 150) shouldBe SharedSkillSplit(125, 25)
        planSharedSkillXp(100, 0, 1) shouldBe null
    }

    "target training ceiling returns the remaining reward to the native skill" {
        planSharedSkillXp(100, 0, 100, 20) shouldBe SharedSkillSplit(80, 20)
        planSharedSkillXp(100, 20, 100, 20) shouldBe null
        planSharedSkillXp(100, 30, 100, 20) shouldBe null
        planSharedSkillXp(100, 0, 100, -1) shouldBe null
    }

    "admitted rewards conserve XP and never decrease or overflow either total" {
        val maximum = Long.MAX_VALUE
        planSharedSkillXp(maximum - 5, maximum - 9, 20) shouldBe SharedSkillSplit(5, 9)
        planSharedSkillXp(maximum, 0, 100) shouldBe null
        planSharedSkillXp(-1, 0, 100) shouldBe null
        planSharedSkillXp(0, -1, 100) shouldBe null
        val totals = listOf(0L, 1L, 99L, maximum - 9, maximum - 1)
        for (source in totals) for (target in totals) for (reward in listOf(2L, 3L, 100L, maximum)) {
            val split = planSharedSkillXp(source, target, reward) ?: continue
            val capacity = (maximum - source).toBigInteger() + (maximum - target).toBigInteger()
            (split.sourceGain.toBigInteger() + split.targetGain.toBigInteger()) shouldBe
                minOf(reward.toBigInteger(), capacity)
            (split.sourceGain > 0 && split.targetGain > 0) shouldBe true
            (source + split.sourceGain >= source) shouldBe true
            (target + split.targetGain >= target) shouldBe true
        }
    }

    "the small allocation matches the closest reachable balance without moving old XP" {
        for (source in 0L..8) for (target in 0L..8) for (reward in 2L..12) {
            for (ceiling in target..12) {
                val expectedTarget = (0L..minOf(reward - 1, ceiling - target)).minWith(
                    compareBy<Long> { kotlin.math.abs(source + reward - it - target - it) }.thenBy { it },
                )
                val actual = planSharedSkillXp(source, target, reward, ceiling)
                (actual?.targetGain ?: 0) shouldBe expectedTarget
                (actual?.sourceGain ?: reward) shouldBe reward - expectedTarget
            }
        }
    }

    "preparation only changes the event and monitor grants the companion exactly once" {
        SharedSkillXpFixture(0, 0).use { fixture ->
            val event = fixture.event(101)
            fixture.listener.prepareShare(event)
            event.xp shouldBe 51
            fixture.total(SkillType.SWORDS) shouldBe 0
            fixture.total(SkillType.AXES) shouldBe 0

            fixture.listener.completeShare(event)
            fixture.total(SkillType.AXES) shouldBe 50
            fixture.listener.completeShare(event)
            fixture.nativeCommit(event)
            fixture.total(SkillType.SWORDS) shouldBe 51
            fixture.total(SkillType.ARMOR) shouldBe 700
            verify(exactly = 1) { PlayerData.addSkillXP(fixture.id, SkillType.AXES, 50) }
        }
    }

    "later cancellation zero and positive replacement invalidate the split and clear its plan" {
        val overrides: List<(EliteSkillXpGainEvent) -> Unit> = listOf(
            { it.isCancelled = true },
            { it.xp = 0 },
            { it.xp = 7 },
        )
        for (change in overrides) SharedSkillXpFixture(0, 0).use { fixture ->
            val event = fixture.event(100)
            fixture.listener.prepareShare(event)
            change(event)
            fixture.listener.completeShare(event)
            fixture.total(SkillType.AXES) shouldBe 0
            fixture.nativeCommit(event)
            fixture.total(SkillType.SWORDS) shouldBe if (event.isCancelled) 0 else event.xp

            event.isCancelled = false
            event.xp = 50
            fixture.listener.completeShare(event)
            verify(exactly = 0) { PlayerData.addSkillXP(any(), any(), any()) }
            verify(exactly = 0) { SkillXPBar.showXPGain(any(), any(), any(), any(), any()) }
        }
    }

    "unloaded data before preparation or finalization cannot receive a share" {
        for (unloadBeforePrepare in listOf(true, false)) SharedSkillXpFixture(0, 0).use { fixture ->
            val event = fixture.event(100)
            if (unloadBeforePrepare) fixture.loaded = false
            fixture.listener.prepareShare(event)
            fixture.loaded = false
            fixture.listener.completeShare(event)
            fixture.nativeCommit(event)
            fixture.total(SkillType.SWORDS) shouldBe 0
            fixture.total(SkillType.AXES) shouldBe 0
            fixture.loaded = true
            fixture.listener.completeShare(event)
            verify(exactly = 0) { PlayerData.addSkillXP(any(), any(), any()) }
        }
    }

    "external progression readiness is required at preparation and finalization" {
        for (unreadyBeforePrepare in listOf(true, false)) SharedSkillXpFixture(0, 0).use { fixture ->
            val event = fixture.event(100)
            if (unreadyBeforePrepare) fixture.ready = false
            fixture.listener.prepareShare(event)
            fixture.ready = false
            fixture.listener.completeShare(event)
            fixture.total(SkillType.AXES) shouldBe 0
            fixture.ready = true
            fixture.listener.completeShare(event)
            verify(exactly = 0) { PlayerData.addSkillXP(any(), any(), any()) }
        }
    }

    "armor cancelled and tiny awards are left native without routing" {
        SharedSkillXpFixture(0, 0).use { fixture ->
            val events = listOf(
                fixture.event(100, SkillType.ARMOR),
                fixture.event(100).also { it.isCancelled = true },
                fixture.event(0),
                fixture.event(1),
            )
            for (event in events) {
                val original = event.xp
                fixture.listener.prepareShare(event)
                fixture.listener.completeShare(event)
                event.xp shouldBe original
            }
            fixture.routingCalls shouldBe 0
            verify(exactly = 0) { PlayerData.addSkillXP(any(), any(), any()) }
        }
    }

    "missing same-skill and armor recipients leave the native award intact" {
        for (target in listOf(null, SharedSkillTarget(SkillType.SWORDS), SharedSkillTarget(SkillType.ARMOR))) {
            SharedSkillXpFixture(0, 0, target).use { fixture ->
                val event = fixture.event(100)
                fixture.listener.prepareShare(event)
                fixture.listener.completeShare(event)
                event.xp shouldBe 100
                verify(exactly = 0) { PlayerData.addSkillXP(any(), any(), any()) }
            }
        }
    }

    "target ceiling is enforced through the native PlayerData increment" {
        SharedSkillXpFixture(100, 0, SharedSkillTarget(SkillType.AXES, 20)).use { fixture ->
            val event = fixture.event(100)
            fixture.listener.prepareShare(event)
            fixture.listener.completeShare(event)
            fixture.nativeCommit(event)
            fixture.total(SkillType.SWORDS) shouldBe 180
            fixture.total(SkillType.AXES) shouldBe 20
        }
    }

    "a companion level increase uses public feedback and refreshes the combat display" {
        SharedSkillXpFixture(7, 7).use { fixture ->
            val event = fixture.event(4)
            fixture.listener.prepareShare(event)
            fixture.listener.completeShare(event)
            verify(exactly = 1) { SkillXPBar.showXPGain(fixture.player, SkillType.AXES, 7, 9, 2) }
            verify(exactly = 1) { CombatLevelDisplay.updateDisplay(fixture.player) }
        }
        SharedSkillXpFixture(0, 0).use { fixture ->
            val event = fixture.event(4)
            fixture.listener.prepareShare(event)
            fixture.listener.completeShare(event)
            verify(exactly = 1) { SkillXPBar.showXPGain(fixture.player, SkillType.AXES, 0, 2, 2) }
            verify(exactly = 0) { CombatLevelDisplay.updateDisplay(any<Player>()) }
        }
    }

    "closing removes pending plans and stops future preparation" {
        SharedSkillXpFixture(0, 0).use { fixture ->
            val pending = fixture.event(100)
            fixture.listener.prepareShare(pending)
            fixture.listener.close()
            fixture.listener.completeShare(pending)
            val next = fixture.event(100)
            fixture.listener.prepareShare(next)
            next.xp shouldBe 100
            fixture.routingCalls shouldBe 1
            verify(exactly = 0) { PlayerData.addSkillXP(any(), any(), any()) }
        }
    }

    "priority contract leaves monitor read-only and always cleans cancelled events" {
        val prepare = DungeonSharedSkillXp::class.java.getDeclaredMethod("prepareShare", EliteSkillXpGainEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        val complete = DungeonSharedSkillXp::class.java.getDeclaredMethod("completeShare", EliteSkillXpGainEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        prepare.priority shouldBe EventPriority.HIGHEST
        prepare.ignoreCancelled shouldBe true
        complete.priority shouldBe EventPriority.MONITOR
        complete.ignoreCancelled shouldBe false
    }
})

private class SharedSkillXpFixture(
    sourceXp: Long,
    targetXp: Long,
    target: SharedSkillTarget? = SharedSkillTarget(SkillType.AXES),
) : AutoCloseable {
    val id: UUID = UUID.randomUUID()
    val player: Player = mockk { every { uniqueId } returns id }
    private val elite = mockk<EliteEntity>()
    private val xp = mutableMapOf(SkillType.SWORDS to sourceXp, SkillType.AXES to targetXp, SkillType.ARMOR to 700L)
    var loaded = true
    var ready = true
    var routingCalls = 0
    val listener = DungeonSharedSkillXp(
        recipient = { _, _ -> routingCalls++; target },
        ready = { ready },
    )

    init {
        mockkStatic(PlayerData::class, SkillXPBar::class, CombatLevelDisplay::class)
        every { PlayerData.isDataLoaded(id) } answers { loaded }
        every { PlayerData.getSkillXP(id, any()) } answers { total(secondArg()) }
        every { PlayerData.addSkillXP(id, any(), any()) } answers {
            val skill = secondArg<SkillType>()
            (total(skill) + thirdArg<Long>()).also { xp[skill] = it }
        }
        every { SkillXPBar.showXPGain(any(), any(), any(), any(), any()) } just Runs
        every { CombatLevelDisplay.updateDisplay(any<Player>()) } just Runs
    }

    fun total(skill: SkillType): Long = xp[skill] ?: 0
    fun event(amount: Long, skill: SkillType = SkillType.SWORDS) = EliteSkillXpGainEvent(player, skill, elite, amount)

    fun nativeCommit(event: EliteSkillXpGainEvent) {
        if (!loaded || event.isCancelled || event.xp == 0L) return
        val before = total(event.skillType)
        xp[event.skillType] = before + minOf(event.xp, Long.MAX_VALUE - before)
    }

    override fun close() {
        listener.close()
        unmockkStatic(CombatLevelDisplay::class, SkillXPBar::class, PlayerData::class)
    }
}
