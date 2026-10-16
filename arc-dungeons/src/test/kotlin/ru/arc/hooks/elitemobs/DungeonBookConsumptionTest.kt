package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.SkillsConfig
import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.EliteMobDeathEvent
import com.magmaguy.elitemobs.api.EliteSkillXpGainEvent
import com.magmaguy.elitemobs.instanced.MatchInstance.InstancedRegionState
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.mobconstructor.EliteEntity
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.CombatLevelDisplay
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.SkillXPBar
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.bukkit.entity.Player
import org.bukkit.World
import org.bukkit.Location
import org.bukkit.GameMode
import org.bukkit.event.player.PlayerQuitEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextColor
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.sync.DungeonXpBookRecord
import ru.arc.sync.EmSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DungeonBookConsumptionTest : FreeSpec({
    "native completion before final kill XP seals at outer death monitor and issues once" {
        BookFixture().use { f ->
            f.library = emptyList()
            f.owner.completed(DungeonCompleteEvent(f.instance))
            f.state = InstancedRegionState.COMPLETED_VICTORY
            f.owner.earned(f.award(100))
            f.xp[SkillType.WANDS] = 200 // Native commits after the nested XP event returns.
            f.owner.deathFinished(f.death())
            f.library.single().xp shouldBe mapOf("WANDS" to 10L)
            f.owner.completed(DungeonCompleteEvent(f.instance))
            f.owner.deathFinished(f.death())
            f.scheduler.tick()
            f.library.size shouldBe 1
            verify(exactly = 1) { f.sync.addSkillBook(f.id, any()) }
            val message = slot<Component>()
            every { f.player.sendMessage(capture(message)) } just Runs
            f.saved.complete(null)
            f.scheduler.executeImmediate()
            message.captured shouldBe Component.text(
                "Книга прохождения получена · Развитие навыков ›", TextColor.color(0xC4ABFF),
            ).clickEvent(ClickEvent.runCommand("/dungeon skills"))
        }
    }

    "quit flushes a non-kill completion before EmSync detaches even if player is offline" {
        BookFixture().use { f ->
            f.library = emptyList()
            f.owner.earned(f.award(100))
            f.owner.completed(DungeonCompleteEvent(f.instance))
            f.online = false
            f.owner.quitting(PlayerQuitEvent(f.player, Component.empty()))
            f.ready = false // EmSync's later LOW-priority listener detaches the player.
            f.scheduler.tick()
            f.library.single().xp shouldBe mapOf("WANDS" to 10L)
        }
    }

    "shutdown flush preserves a completed book before synchronization is detached" {
        BookFixture().use { f ->
            f.library = emptyList()
            f.owner.earned(f.award(100))
            f.owner.completed(DungeonCompleteEvent(f.instance))
            f.owner.flushCompletions()
            f.ready = false
            f.owner.close()
            f.saved.complete(null)
            f.scheduler.tick()
            f.library.single().xp shouldBe mapOf("WANDS" to 10L)
        }
    }

    "cancelled zero capped and foreign-world source XP cannot create a completion book" {
        BookFixture().use { f ->
            f.library = emptyList()
            f.owner.earned(f.award(100).also { it.isCancelled = true })
            f.owner.earned(f.award(0))
            f.mobWorld = mockk<World>()
            f.owner.earned(f.award(1000))
            f.mobWorld = f.world
            f.xp[SkillType.WANDS] = Long.MAX_VALUE - 1
            f.owner.earned(f.award(100))
            f.owner.completed(DungeonCompleteEvent(f.instance))
            f.owner.deathFinished(f.death())
            f.library shouldBe emptyList()
        }
    }

    "delayed persistence grants each recorded branch once and blocks a double click" {
        BookFixture().use { f ->
            val results = mutableListOf<DungeonProgressionBookResult>()
            f.owner.consume(f.player, f.book.id, results::add)
            f.xp[SkillType.WANDS] shouldBe 107L
            f.xp[SkillType.STAVES] shouldBe 203L
            f.library shouldBe emptyList()
            results shouldBe emptyList()
            f.owner.consume(f.player, f.book.id, results::add)
            results shouldBe listOf(DungeonProgressionBookResult.PENDING)
            f.saved.complete(null)
            f.scheduler.executeImmediate()
            results.last() shouldBe DungeonProgressionBookResult.APPLIED
            f.owner.consume(f.player, f.book.id, results::add)
            results.last() shouldBe DungeonProgressionBookResult.MISSING
            verify(exactly = 2) { PlayerData.addSkillXP(any(), any(), any()) }
        }
    }

    "unknown save outcome never restores the entitlement or repeats XP" {
        BookFixture().use { f ->
            val results = mutableListOf<DungeonProgressionBookResult>()
            f.owner.consume(f.player, f.book.id, results::add)
            f.saved.completeExceptionally(IllegalStateException("uncertain network write"))
            f.scheduler.executeImmediate()
            results shouldBe listOf(DungeonProgressionBookResult.FAILED)
            f.owner.consume(f.player, f.book.id, results::add)
            results.last() shouldBe DungeonProgressionBookResult.MISSING
            f.library shouldBe emptyList()
            f.xp.values.sum() shouldBe 310L
        }
    }

    "loading missing foreign books and a closed owner cannot change native XP" {
        BookFixture().use { f ->
            var result: DungeonProgressionBookResult? = null
            f.owner.consume(f.player, "foreign", { result = it })
            result shouldBe DungeonProgressionBookResult.MISSING
            f.ready = false
            f.owner.consume(f.player, f.book.id) { result = it }
            result shouldBe DungeonProgressionBookResult.LOADING
            f.ready = true
            f.owner.close()
            f.owner.consume(f.player, f.book.id) { result = it }
            result shouldBe DungeonProgressionBookResult.LOADING
            f.library.size shouldBe 1
            verify(exactly = 0) { PlayerData.addSkillXP(any(), any(), any()) }
        }
    }

    "late save completion after owner close does not reopen a stale menu" {
        BookFixture().use { f ->
            val results = mutableListOf<DungeonProgressionBookResult>()
            f.owner.consume(f.player, f.book.id, results::add)
            f.owner.close()
            f.saved.complete(null)
            f.scheduler.executeImmediate()
            results shouldBe emptyList()
            f.xp.values.sum() shouldBe 310L
        }
    }
})

private class BookFixture : AutoCloseable {
    val id = UUID.randomUUID()
    var ready = true
    var online = true
    val world = mockk<World>()
    var mobWorld = world
    var state = InstancedRegionState.ONGOING
    val book = DungeonXpBookRecord("book-1", "Храм", 1L, mapOf("WANDS" to 7L, "STAVES" to 3L))
    var library = listOf(book)
    val xp = mutableMapOf(SkillType.WANDS to 100L, SkillType.STAVES to 200L)
    val scheduler = TestTaskScheduler()
    val saved = CompletableFuture<Void>()
    val player = mockk<Player> { every { uniqueId } returns id; every { isOnline } answers { online } }
    val instance = mockk<DungeonInstance>()
    val elite = mockk<EliteEntity>()
    val sync = mockk<EmSync>()
    val adventure = mockk<DungeonAdventureService> { every { skills(any()) } returns emptyList() }
    val owner: DungeonProgression

    init {
        mockkStatic(SkillsConfig::class, PlayerData::class, SkillXPBar::class, CombatLevelDisplay::class, DungeonInstance::class)
        every { DungeonInstance.getDungeonInstances() } returns setOf(instance)
        every { player.world } returns world
        every { player.gameMode } returns GameMode.SURVIVAL
        every { instance.world } returns world
        every { instance.participants } returns hashSetOf(player)
        every { instance.isCancelled } returns false
        every { instance.state } answers { state }
        every { instance.contentPackagesConfigFields.name } returns "Храм"
        every { elite.location } answers { Location(mobWorld, 0.0, 0.0, 0.0) }
        every { SkillsConfig.isSkillSystemEnabled() } returns true
        every { PlayerData.isDataLoaded(id) } returns true
        every { PlayerData.getSkillXP(id, any()) } answers { xp[secondArg<SkillType>()] ?: 0 }
        every { PlayerData.addSkillXP(id, any(), any()) } answers {
            val skill = secondArg<SkillType>()
            (xp.getValue(skill) + thirdArg<Long>()).also { xp[skill] = it }
        }
        every { SkillXPBar.showXPGain(any(), any(), any(), any(), any()) } just Runs
        every { CombatLevelDisplay.updateDisplay(any<Player>()) } just Runs
        every { sync.isReady(id) } answers { ready }
        every { sync.skillBooks(id) } answers { library }
        every { sync.addSkillBook(id, any()) } answers {
            val candidate = secondArg<DungeonXpBookRecord>()
            if (library.any { it.id == candidate.id }) false else true.also { library = library + candidate }
        }
        every { sync.removeSkillBook(id, any()) } answers {
            library.firstOrNull { it.id == secondArg<String>() }?.also { selected -> library = library.filterNot { it.id == selected.id } }
        }
        every { sync.persist(player) } returns saved
        owner = DungeonProgression({ sync }, adventure, LifecycleTaskScope(scheduler))
    }

    fun award(xp: Long) = EliteSkillXpGainEvent(player, SkillType.WANDS, elite, xp)
    fun death() = mockk<EliteMobDeathEvent> { every { eliteEntity } returns elite }

    override fun close() {
        owner.close()
        unmockkStatic(SkillsConfig::class, PlayerData::class, SkillXPBar::class, CombatLevelDisplay::class, DungeonInstance::class)
    }
}
