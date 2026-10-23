package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.EliteMobDamagedEvent
import com.magmaguy.elitemobs.api.EliteMobDeathEvent
import com.magmaguy.elitemobs.api.EliteMobRemoveEvent
import com.magmaguy.elitemobs.api.EliteMobSpawnEvent
import com.magmaguy.elitemobs.api.ElitePhaseSwitchEvent
import com.magmaguy.elitemobs.api.internal.RemovalReason
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.mobconstructor.custombosses.PhaseBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.transitiveblocks.TransitiveBossBlock
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.entity.AnimalTamer
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageEvent
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

class PrimisEncounterGuardTest : StringSpec({
    "both damage boundaries and phase materialization run before the native phase and block handlers" {
        for ((name, event) in listOf("onBukkitDamage" to EntityDamageEvent::class.java,
            "onEliteDamage" to EliteMobDamagedEvent::class.java, "onSpawn" to EliteMobSpawnEvent::class.java)) {
            val annotation = PrimisEncounterGuard::class.java.getDeclaredMethod(name, event).getAnnotation(EventHandler::class.java)
            annotation.priority shouldBe EventPriority.LOWEST
            annotation.ignoreCancelled shouldBe true
        }
        val nativeBlocks = TransitiveBossBlock::class.java.getDeclaredMethod("onBossSpawn", EliteMobSpawnEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        nativeBlocks.priority shouldBe EventPriority.NORMAL
        nativeBlocks.ignoreCancelled shouldBe true
    }
    "the reported outside magic player and a projectile or pet owner cannot initiate" {
        PrimisGuardFixture().use { f ->
            val projectile = mockk<Projectile> { every { shooter } returns f.outside }
            val pet = mockk<Tameable> { every { owner } returns f.outside }
            for (source in listOf(f.outside, projectile, pet)) {
                val damage = f.damage(source)
                f.guard.onBukkitDamage(damage)
                damage.isCancelled shouldBe true
                val native = EliteMobDamagedEvent(f.boss, damage, 5.0)
                f.guard.onEliteDamage(native)
                native.isCancelled shouldBe true
            }
            f.feedback.first() shouldBe PrimisAdmissionDecision.ENTER_ROOM
            f.heals shouldBe 0
        }
    }
    "a known offline pet owner stays outside rather than becoming unowned ongoing damage" {
        PrimisGuardFixture().use { f ->
            f.admit()
            val absent = mockk<AnimalTamer> { every { uniqueId } returns UUID.randomUUID() }
            val pet = mockk<Tameable> { every { owner } returns absent }
            val hit = f.damage(pet)
            f.guard.onBukkitDamage(hit)
            hit.isCancelled shouldBe true
        }
    }
    "unowned damage cannot start but continues only while an admitted inside participant remains" {
        PrimisGuardFixture().use { f ->
            f.damage().also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe true }
            f.admit()
            f.damage().also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe false }
            f.player.teleport(f.outside.location)
            f.damage().also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe true }
        }
    }
    "outside new spells stay blocked while native periodic damage from an earlier cast may continue with an inside ally" {
        PrimisGuardFixture().use { f ->
            for (cause in listOf(EntityDamageEvent.DamageCause.POISON, EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.WITHER)) {
                f.damage(f.outside, cause).also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe true }
            }
            f.admit()
            f.damage(f.outside).also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe true }
            for (cause in listOf(EntityDamageEvent.DamageCause.POISON, EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.WITHER)) {
                f.damage(f.outside, cause).also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe false }
            }
            f.player.teleport(f.outside.location)
            f.damage(f.outside, EntityDamageEvent.DamageCause.POISON).also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe true }
        }
    }
    "a later cancelled inside hit never admits unrelated damage" {
        PrimisGuardFixture().use { f ->
            val hit = f.damage(f.player)
            f.guard.onBukkitDamage(hit)
            hit.isCancelled shouldBe false
            hit.isCancelled = true; f.guard.onDamageOutcome(hit)
            f.damage().also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe true }
            f.scheduler.tick(24)
            (f.heals > 0) shouldBe true
        }
    }
    "any player in the passage vetoes the first hit and a later deferred closing before native blocks" {
        PrimisGuardFixture().use { f ->
            f.outside.teleport(Location(f.world, 1104.1, 37.0, 573.5))
            val blocked = f.damage(f.player)
            f.guard.onBukkitDamage(blocked)
            blocked.isCancelled shouldBe true
            f.feedback.last() shouldBe PrimisAdmissionDecision.PASSAGE_OCCUPIED
            f.outside.teleport(Location(f.world, 1106.0, 37.0, 573.5))
            val allowed = f.damage(f.player)
            f.guard.onBukkitDamage(allowed)
            allowed.isCancelled shouldBe false
            f.first = false
            f.outside.teleport(Location(f.world, 1104.1, 37.0, 573.5))
            val spawn = EliteMobSpawnEvent(f.boss)
            f.guard.onSpawn(spawn)
            spawn.isCancelled shouldBe true
            f.guard.onSpawnOutcome(spawn)
            f.materialized = false // Native commit discards the cancelled, unregistered body.
            f.cancelNextBaseSpawn = true
            f.scheduler.tick(2)
            f.first shouldBe true
            f.materialized shouldBe false
            f.damage(f.player).also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe true }
            f.scheduler.tick(23)
            f.spawns shouldBe 1
            f.materialized shouldBe true
            f.outside.teleport(Location(f.world, 1106.0, 37.0, 573.5))
            f.damage(f.player).also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe false }
        }
    }
    "all participants leaving or becoming spectator triggers the scoped native abort after grace" {
        for (mode in listOf(GameMode.SPECTATOR, GameMode.CREATIVE)) PrimisGuardFixture().use { f ->
            f.admit()
            f.player.gameMode = mode
            f.scheduler.tick(19)
            f.heals shouldBe 0
            f.scheduler.tick(13)
            (f.heals > 0) shouldBe true
            f.first shouldBe true
        }
    }
    "reload adopts a real active inside fight and aborts an active empty room" {
        for (inside in listOf(true, false)) PrimisGuardFixture(initialActive = true, insidePresent = inside).use { f ->
            f.scheduler.tick(3)
            if (inside) {
                f.damage().also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe false }
                f.heals shouldBe 0
            } else (f.heals > 0) shouldBe true
        }
    }
    "death and victory removal invalidate a queued abort without healing spawning or restoring quest walls" {
        for (death in listOf(true, false)) PrimisGuardFixture().use { f ->
            f.admit()
            val spawn = EliteMobSpawnEvent(f.boss)
            f.outside.teleport(Location(f.world, 1104.1, 37.0, 573.5))
            f.guard.onSpawn(spawn)
            spawn.isCancelled shouldBe true
            if (death) f.guard.onDeath(EliteMobDeathEvent(f.boss))
            else f.guard.onRemove(EliteMobRemoveEvent(f.boss, RemovalReason.DEATH))
            f.health = 0.0
            val questWall = f.world.getBlockAt(1095, 38, 583)
            questWall.type = Material.AIR
            f.scheduler.tick(40)
            f.heals shouldBe 0
            f.spawns shouldBe 0
            questWall.type shouldBe Material.AIR
        }
    }
    "missing passage chunks keep damage blocked and readback never forces a chunk load" {
        PrimisGuardFixture().use { f ->
            mockkObject(f.world)
            try {
                every { f.world.isChunkLoaded(any(), any()) } returns false
                val hit = f.damage(f.player)
                f.guard.onBukkitDamage(hit)
                hit.isCancelled shouldBe true
                f.scheduler.tick(15)
                verify(exactly = 0) { f.world.getChunkAt(any<Int>(), any<Int>()) }
            } finally { unmockkObject(f.world) }
        }
    }
    "close finishes only its cancelled phase body before reopening and invalidates its old queued reset" {
        PrimisGuardFixture().use { f ->
            f.admit()
            f.outside.teleport(Location(f.world, 1104.1, 37.0, 573.5))
            f.guard.onSpawn(EliteMobSpawnEvent(f.boss))
            f.materialized = false
            f.guard.close()
            f.first shouldBe true
            f.materialized shouldBe true
            val healed = f.heals
            (healed > 0) shouldBe true
            f.scheduler.tick(40)
            f.heals shouldBe healed
            f.reopen()
            f.outside.teleport(Location(f.world, 1106.0, 37.0, 573.5))
            f.damage(f.player).also { f.guard.onBukkitDamage(it); it.isCancelled shouldBe false }
        }
    }
    "replacement regional ownership invalidates pending reset and terminal handoff" {
        PrimisGuardFixture().use { f ->
            f.admit()
            f.outside.teleport(Location(f.world, 1104.1, 37.0, 573.5))
            f.guard.onSpawn(EliteMobSpawnEvent(f.boss))
            f.ownerCurrent = false
            f.guard.close()
            f.scheduler.tick(40)
            f.heals shouldBe 0
            f.spawns shouldBe 0
        }
    }
})

private class PrimisGuardFixture(initialActive: Boolean = false, insidePresent: Boolean = true) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = paper.server.addSimpleWorld("em_primis")
    val player = paper.server.addPlayer("PrimisInside")
    val outside = paper.server.addPlayer("PrimisOutside")
    val scheduler = TestTaskScheduler()
    private val nativeScope = LifecycleTaskScope(scheduler)
    private var guardScope = LifecycleTaskScope(scheduler)
    private val queue = BudgetedTaskQueue(nativeScope)
    private val profile = primisEncounterProfiles().first().copy(closureCount = 1)
    private val base = mockk<CustomBossesConfigFields> { every { filename } returns profile.baseFile }
    val phases = mockk<PhaseBossEntity>()
    val boss = mockk<RegionalBossEntity>(relaxed = true)
    private val body = mockk<LivingEntity>(relaxed = true)
    var first = !initialActive
    var materialized = true
    var health = 100.0
    var ownerCurrent = true
    var cancelNextBaseSpawn = false
    var heals = 0
    var spawns = 0
    val feedback = mutableListOf<PrimisAdmissionDecision>()
    var guard: PrimisEncounterGuard
        private set

    init {
        world.getChunkAt(68, 35); world.getChunkAt(69, 35)
        player.teleport(Location(world, if (insidePresent) 1095.5 else 1106.0, 37.0, 573.5))
        outside.teleport(Location(world, 1105.32241682432, 37.0, 573.8900094016826))
        every { phases.phase1Config } returns base
        every { phases.isInFirstPhase } answers { first }
        every { phases.resetToFirstPhase() } answers { first = true }
        every { boss.phaseBossEntity } returns phases
        every { boss.customBossesConfigFields } returns base
        every { boss.worldName } returns world.name
        every { boss.configurationLocationString } returns profile.spawn
        every { boss.livingEntity } answers { if (materialized) body else null }
        every { boss.health } answers { health }
        every { boss.maxHealth } returns 100.0
        every { boss.isRespawning } returns false
        every { boss.isRemoved } returns false
        every { body.isValid } answers { materialized }
        every { body.isDead } answers { health <= 0.0 }
        every { boss.fullHeal() } answers {
            heals++
            if (!first) materializeBase(cancelNextBaseSpawn.also { cancelNextBaseSpawn = false })
            else if (materialized) health = 100.0
        }
        every { boss.spawn(true) } answers { spawns++; materializeBase(false) }
        mockkStatic(RegionalBossEntity::class, EntityTracker::class)
        every { RegionalBossEntity.getRegionalBossEntities(base) } answers { if (ownerCurrent) listOf(boss) else emptyList() }
        every { EntityTracker.getEliteMobEntity(body) } returns boss
        val cell = PrimisPassageCell(1104, 37, 573, Bukkit.createBlockData(Material.AIR))
        guard = PrimisEncounterGuard(queue, Logger.getAnonymousLogger().also { it.level = Level.OFF },
            { _, reason -> feedback += reason }, listOf(PrimisPreparedEncounter(profile, base, listOf(cell))), guardScope)
        guard.start()
        scheduler.tick(4)
    }

    fun damage(source: Entity? = null, cause: EntityDamageEvent.DamageCause = EntityDamageEvent.DamageCause.MAGIC): EntityDamageEvent {
        val native = mockk<DamageSource> {
            every { causingEntity } returns source
            every { directEntity } returns source
        }
        return EntityDamageEvent(body, cause, native, 5.0)
    }

    fun admit() {
        val hit = damage(player)
        guard.onBukkitDamage(hit)
        hit.isCancelled shouldBe false
        first = false
        val spawn = EliteMobSpawnEvent(boss)
        guard.onSpawn(spawn)
        spawn.isCancelled shouldBe false
        world.getBlockAt(1104, 37, 573).type = Material.DARK_OAK_FENCE_GATE
        guard.onPhaseSwitch(ElitePhaseSwitchEvent(boss, phases))
        hit.isCancelled = true // Native phase-switch damage is deliberately cancelled.
        guard.onDamageOutcome(hit)
    }

    fun reopen() {
        guardScope = LifecycleTaskScope(scheduler)
        val cell = PrimisPassageCell(1104, 37, 573, Bukkit.createBlockData(Material.AIR))
        guard = PrimisEncounterGuard(queue, Logger.getAnonymousLogger().also { it.level = Level.OFF },
            { _, reason -> feedback += reason }, listOf(PrimisPreparedEncounter(profile, base, listOf(cell))), guardScope)
        guard.start(); scheduler.tick(4)
    }

    private fun materializeBase(cancel: Boolean) {
        first = true; materialized = true
        val event = EliteMobSpawnEvent(boss)
        guard.onSpawn(event)
        if (cancel) event.isCancelled = true
        guard.onSpawnOutcome(event)
        materialized = !event.isCancelled
        if (materialized) {
            health = 100.0
            world.getBlockAt(1104, 37, 573).type = Material.AIR
            guard.onPhaseSwitch(ElitePhaseSwitchEvent(boss, phases))
        }
    }

    override fun close() {
        guard.close(); queue.close(); nativeScope.close(); guardScope.close()
        unmockkStatic(RegionalBossEntity::class, EntityTracker::class)
        paper.close()
    }
}
