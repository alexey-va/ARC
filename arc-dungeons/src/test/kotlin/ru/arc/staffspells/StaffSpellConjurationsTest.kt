package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID

class StaffSpellConjurationsTest : FreeSpec({
    "Frost Q leaves its existing slow untouched when damage is rejected" {
        withStaffHarness(hit = { _, _ -> false }) { h ->
            val scenes = mutableListOf<CustomConjurationScene>()
            recordConjurationScenes(h, scenes, mutableListOf())
            val world = h.paper.addSimpleWorld("staff-frost-q-rejected-root")
            val player = h.player("staff-frost-q-rejected-root", world, StaffSpell.FROST)
            val target = h.zombie(world, .5, 4.5)
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 1))
            h.controller.conjurations.rememberFrostSlow(player, target, 40)

            h.controller.conjurations.cast(player, StaffSpell.FROST, conjurationCast(player),
                staffSettings().tuning.getValue(StaffSpell.FROST)) shouldBe true

            h.hits shouldBe listOf(target)
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 1
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.duration shouldBe 40
        }
    }

    "MARK anchors to an exposed head and expires only when the whole body is covered" {
        withStaffHarness { h ->
            val scenes = mutableListOf<CustomConjurationScene>()
            val removed = mutableListOf<UUID?>()
            recordConjurationScenes(h, scenes, removed)
            val world = h.paper.addSimpleWorld("staff-mark-partial-cover")
            val player = h.player("staff-mark-partial-cover", world, StaffSpell.MARK)
            val target = h.zombie(world, .5, 8.5)
            target.teleport(Location(world, .5, 63.6, 8.5))
            world.getBlockAt(0, 64, 6).type = Material.STONE
            visible(player.eyeLocation, center(target)) shouldBe false
            visible(player.eyeLocation, target) shouldBe true

            h.controller.conjurations.cast(player, StaffSpell.MARK, conjurationCast(player),
                staffSettings().tuning.getValue(StaffSpell.MARK)) shouldBe true
            h.scheduler.tick(4)
            (scenes.single().id in removed) shouldBe false
            h.hits shouldBe emptyList()

            world.getBlockAt(0, 65, 6).type = Material.STONE
            h.scheduler.tick(2)
            (scenes.single().id in removed) shouldBe true
            h.hits shouldBe emptyList()
        }
    }

    "empty MARK FROST and TIDE Q casts keep a visible bounded scene without damage or roots" {
        for (spell in listOf(StaffSpell.MARK, StaffSpell.FROST, StaffSpell.TIDE)) withStaffHarness { h ->
            val scenes = mutableListOf<CustomConjurationScene>()
            val removed = mutableListOf<UUID?>()
            recordConjurationScenes(h, scenes, removed)
            val world = h.paper.addSimpleWorld("staff-empty-conjuration-${spell.id}")
            val player = h.player("staff-empty-conjuration-${spell.id}", world, spell)

            h.controller.conjurations.cast(player, spell, conjurationCast(player),
                staffSettings().tuning.getValue(spell)) shouldBe true

            scenes.size shouldBe 1
            scenes.single().geometry(8).isNotEmpty() shouldBe true
            h.hits shouldBe emptyList()
            h.scheduler.tick(200)
            h.hits shouldBe emptyList()
            (scenes.first().id in removed) shouldBe true
            scenes.size shouldBe if (spell == StaffSpell.FROST) 1 else 2
            if (spell != StaffSpell.FROST) (scenes.last().durationTicks in 1..12) shouldBe true
        }
    }

    "an empty conjuration consumes cooldown only after its scene is accepted" {
        for (spell in listOf(StaffSpell.MARK, StaffSpell.FROST, StaffSpell.TIDE)) withStaffHarness { h ->
            every { h.effects.custom(any(), any(), any(), any(), any()) } returns null
            val world = h.paper.addSimpleWorld("staff-empty-conjuration-budget-${spell.id}")
            val player = h.player("staff-empty-conjuration-budget-${spell.id}", world, spell)
            h.controller.start()
            h.controller.hotkey(player, spell, StaffHotkey.Q)
            h.cooldowns.remainingTicks(player.uniqueId) shouldBe 0L
            val scenes = mutableListOf<CustomConjurationScene>()
            recordConjurationScenes(h, scenes, mutableListOf())

            h.controller.hotkey(player, spell, StaffHotkey.Q)

            scenes.size shouldBe 1
            h.cooldowns.remainingTicks(player.uniqueId) shouldBe 4L
            h.hits shouldBe emptyList()
        }
    }

    "attack roots survive item swaps and damage and clear only their captured attack identity" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-attack-root-identity")
            val player = h.player("staff-attack-root-identity", world, StaffSpell.NOVA)
            val target = h.zombie(world, .5, 4.5)
            val cast = conjurationCast(player)
            val otherAttack = UUID.randomUUID()
            val roots = h.controller.conjurations

            roots.rootTarget(player, target, cast, 70) shouldBe true
            roots.rootActive(target.uniqueId, cast.attackId) shouldBe true
            roots.rootActive(target.uniqueId, otherAttack) shouldBe false
            roots.clearRoots(otherAttack)
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.EMBER))
            roots.onConjurationTargetDamage(EntityDamageEvent(target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0))
            target.health -= 2.0
            h.paper.performTicks(1)
            h.scheduler.tick(1)
            roots.rootActive(target.uniqueId, cast.attackId) shouldBe true

            roots.clearRoots(cast.attackId)
            roots.rootActive(target.uniqueId, cast.attackId) shouldBe false
            target.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
        }
    }

    "REQUIEM impact scene survives cleanup and recast belongs to the held staff" {
        StaffMagicBarriers.clear()
        try {
            withStaffHarness { h ->
                val scenes = mutableListOf<CustomConjurationScene>()
                val removed = mutableListOf<UUID?>()
                recordConjurationScenes(h, scenes, removed)
                val world = h.paper.addSimpleWorld("staff-requiem-q-scene")
                for (x in -3..3) for (z in 0..12) world.getBlockAt(x, 63, z).type = Material.STONE
                val player = h.player("requiem-q-scene", world, StaffSpell.REQUIEM)
                val cast = conjurationCast(player)
                val tuning = staffSettings().tuning.getValue(StaffSpell.REQUIEM)

                h.controller.conjurations.cast(player, StaffSpell.REQUIEM, cast, tuning) shouldBe true
                scenes.size shouldBe 1
                h.scheduler.tick(18)
                h.controller.conjurations.canRecast(player.uniqueId, StaffSpell.REQUIEM) shouldBe true
                h.controller.conjurations.canRecast(player.uniqueId, StaffSpell.FAULT) shouldBe false
                h.controller.conjurations.continuationLabel(player.uniqueId, StaffSpell.FAULT) shouldBe null

                h.controller.conjurations.cast(player, StaffSpell.REQUIEM, cast, tuning) shouldBe true

                scenes.size shouldBe 2
                scenes[1].spell shouldBe StaffSpell.REQUIEM
                scenes[1].durationTicks shouldBe 14
                scenes[1].geometry(7).size shouldBe 32
                (scenes[1].id in removed) shouldBe false
                h.controller.conjurations.canRecast(player.uniqueId, StaffSpell.REQUIEM) shouldBe false
            }
        } finally {
            StaffMagicBarriers.clear()
        }
    }

    "MARK waits long enough to leave its tether, ends through cover, and cannot retry denied punishment" {
        StaffMagicBarriers.clear()
        var deniedId: UUID? = null
        withStaffHarness(settings = staffSettings(markTicks = 18), hit = { _, target -> target.uniqueId != deniedId }) { h ->
            val scenes = mutableListOf<CustomConjurationScene>()
            val removed = mutableListOf<UUID?>()
            recordConjurationScenes(h, scenes, removed)
            val world = h.paper.addSimpleWorld("staff-mark-q-contact")
            val tuning = staffSettings().tuning.getValue(StaffSpell.MARK)

            val coveredPlayer = h.player("covered-mark", world, StaffSpell.MARK)
            val coveredTarget = h.zombie(world, 0.5, 4.5)
            val coveredCast = conjurationCast(coveredPlayer)
            h.controller.conjurations.cast(coveredPlayer, StaffSpell.MARK, coveredCast, tuning) shouldBe true
            h.scheduler.tick(30)
            coveredTarget.teleport(Location(world, 0.5, 64.0, 8.5))
            for (y in 64..66) world.getBlockAt(0, y, 6).type = Material.STONE
            visible(coveredPlayer.eyeLocation, coveredTarget) shouldBe false
            h.scheduler.tick(1)
            h.hits.any { it.uniqueId == coveredTarget.uniqueId } shouldBe false
            (scenes.first().id in removed) shouldBe true

            val protectedPlayer = h.player("protected-mark", world, StaffSpell.MARK).apply {
                teleport(Location(world, 10.5, 64.0, 0.5).apply { direction = Vector(0.0, 0.0, 1.0) })
            }
            val protectedTarget = h.zombie(world, 10.5, 4.5)
            deniedId = protectedTarget.uniqueId
            h.controller.conjurations.cast(protectedPlayer, StaffSpell.MARK,
                conjurationCast(protectedPlayer), tuning) shouldBe true
            protectedTarget.teleport(Location(world, 10.5, 64.0, 8.5))
            h.scheduler.tick(1)
            h.scheduler.tick(5)
            h.hits.count { it.uniqueId == protectedTarget.uniqueId } shouldBe 1

            val escapedPlayer = h.player("escaped-mark", world, StaffSpell.MARK).apply {
                teleport(Location(world, 20.5, 64.0, 0.5).apply { direction = Vector(0.0, 0.0, 1.0) })
            }
            val escapedTarget = h.zombie(world, 20.5, 4.5)
            h.controller.conjurations.cast(escapedPlayer, StaffSpell.MARK,
                conjurationCast(escapedPlayer), tuning) shouldBe true
            h.scheduler.tick(30)
            escapedTarget.teleport(Location(world, 20.5, 64.0, 8.5))
            h.scheduler.tick(1)
            h.hits.count { it.uniqueId == escapedTarget.uniqueId } shouldBe 1
        }
    }

    "FROST Q consumes only an active slow recorded for that caster" {
        StaffMagicBarriers.clear()
        withStaffHarness { h ->
            val scenes = mutableListOf<CustomConjurationScene>()
            recordConjurationScenes(h, scenes, mutableListOf())
            val world = h.paper.addSimpleWorld("staff-frost-q-owned-slow")
            val caster = h.player("frost-q-owner", world, StaffSpell.FROST)
            val otherCaster = h.player("frost-q-other-owner", world, StaffSpell.FROST).apply {
                teleport(Location(world, 5.5, 64.0, 0.5).apply { direction = Vector(0.0, 0.0, 1.0) })
            }
            val ownedTarget = h.zombie(world, 0.5, 4.5)
            val otherTarget = h.zombie(world, 5.5, 2.5)
            h.controller.start()
            h.controller.cast(caster)
            h.scheduler.tick(STAFF_FRONTAL_CONTACT_TICK)
            ownedTarget.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true

            otherTarget.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 0, false, false))
            h.controller.conjurations.rememberFrostSlow(otherCaster, otherTarget, 40)
            h.controller.conjurations.cast(caster, StaffSpell.FROST, conjurationCast(caster),
                staffSettings().tuning.getValue(StaffSpell.FROST)) shouldBe true

            h.hits.count { it.uniqueId == ownedTarget.uniqueId } shouldBe 2
            h.hits.any { it.uniqueId == otherTarget.uniqueId } shouldBe false
            ownedTarget.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 7
            ownedTarget.getPotionEffect(PotionEffectType.SLOWNESS)?.duration shouldBe 20
            otherTarget.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true
            scenes.size shouldBe 1
            scenes.single().geometry(12).size shouldBe 36
            repeat(20) { h.paper.performTicks(1); h.scheduler.tick(1) }
            ownedTarget.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
        }
    }

    "MARK and NOVA do not recreate aftermath scenes or roots after a damage callback cancels their owner" {
        for (spell in listOf(StaffSpell.MARK, StaffSpell.NOVA)) {
            var cancelDuringDamage: () -> Unit = {}
            withStaffHarness(hit = { _, _ -> cancelDuringDamage(); true }) { h ->
                val scenes = mutableListOf<CustomConjurationScene>()
                val removed = mutableListOf<UUID?>()
                recordConjurationScenes(h, scenes, removed)
                val world = h.paper.addSimpleWorld("staff-q-reentrant-cancel-${spell.id}")
                for (x in -2..2) for (z in 0..12) world.getBlockAt(x, 63, z).type = Material.STONE
                val player = h.player("staff-q-reentrant-cancel-${spell.id}", world, spell)
                val target = h.zombie(world, .5, 4.5)
                cancelDuringDamage = { h.controller.conjurations.cancel(player.uniqueId) }
                h.controller.conjurations.cast(player, spell, conjurationCast(player),
                    staffSettings().tuning.getValue(spell)) shouldBe true
                if (spell == StaffSpell.MARK) target.teleport(target.location.clone().add(0.0, 0.0, 4.0))
                h.scheduler.tick(12)
                h.hits shouldBe listOf(target)
                scenes.size shouldBe 1
                (scenes.single().id in removed) shouldBe true
                target.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            }
        }
    }

    "OSSUARY jaw replaces the armed trap with a closing scene that survives session cleanup" {
        StaffMagicBarriers.clear()
        withStaffHarness { h ->
            val scenes = mutableListOf<CustomConjurationScene>()
            val removed = mutableListOf<UUID?>()
            recordConjurationScenes(h, scenes, removed)
            val world = h.paper.addSimpleWorld("staff-ossuary-q-close")
            for (x in -2..2) for (z in 0..10) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("ossuary-q-close", world, StaffSpell.OSSUARY)
            val target = h.zombie(world, 0.5, 7.5)
            val tuning = staffSettings().tuning.getValue(StaffSpell.OSSUARY)

            h.controller.conjurations.cast(player, StaffSpell.OSSUARY, conjurationCast(player), tuning) shouldBe true
            scenes.size shouldBe 1
            h.scheduler.tick(8)

            scenes.size shouldBe 2
            scenes[1].spell shouldBe StaffSpell.OSSUARY
            scenes[1].durationTicks shouldBe 9
            scenes[1].geometry(0).mapNotNull { it.identity } shouldBe
                scenes[1].geometry(7).mapNotNull { it.identity }
            val openWidth = scenes[1].geometry(0).let { parts -> parts.maxOf { it.center.x } - parts.minOf { it.center.x } }
            val closedWidth = scenes[1].geometry(7).let { parts -> parts.maxOf { it.center.x } - parts.minOf { it.center.x } }
            (closedWidth < openWidth) shouldBe true
            (scenes[0].id in removed) shouldBe true
            (scenes[1].id in removed) shouldBe false

            h.scheduler.tick(9)
            (target.uniqueId in h.hits.map { it.uniqueId }) shouldBe true
            (scenes[1].id in removed) shouldBe false
        }
    }

    "TIDE capsule restores replaced slowness when cover breaks and when accepted damage breaks the root" {
        StaffMagicBarriers.clear()
        withStaffHarness { h ->
            val scenes = mutableListOf<CustomConjurationScene>()
            val removed = mutableListOf<UUID?>()
            recordConjurationScenes(h, scenes, removed)
            val world = h.paper.addSimpleWorld("staff-tide-q-root-cleanup")
            val player = h.player("tide-q-root", world, StaffSpell.TIDE)
            val target = h.zombie(world, 0.5, 4.5)
            val tuning = staffSettings().tuning.getValue(StaffSpell.TIDE)
            val slowness = PotionEffect(PotionEffectType.SLOWNESS, 80, 0, false, false)
            target.addPotionEffect(slowness)

            h.controller.conjurations.cast(player, StaffSpell.TIDE, conjurationCast(player), tuning) shouldBe true
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 7
            world.getBlockAt(0, 65, 2).type = Material.STONE
            h.paper.performTicks(1) // Root durations follow the native server tick.
            h.scheduler.tick(1)
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 0
            (target.getPotionEffect(PotionEffectType.SLOWNESS)?.duration ?: 0) shouldBe 79
            (scenes.first().id in removed) shouldBe true

            world.getBlockAt(0, 65, 2).type = Material.AIR
            h.controller.conjurations.cast(player, StaffSpell.TIDE, conjurationCast(player), tuning) shouldBe true
            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 7
            val event = EntityDamageEvent(target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0)
            h.controller.conjurations.onConjurationTargetDamage(event)
            target.health = (target.health - 2.0).coerceAtLeast(1.0)
            h.paper.performTicks(1) // Root durations follow the native server tick.
            h.scheduler.tick(1)

            target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 0
            (target.getPotionEffect(PotionEffectType.SLOWNESS)?.duration ?: 0) shouldBe 78
            scenes.size shouldBe 3
            (scenes[1].id in removed) shouldBe true
            scenes[2].durationTicks shouldBe 10
            scenes[2].geometry(4).all { it.identity!!.startsWith("tide:q:release:") } shouldBe true
            (scenes[2].id in removed) shouldBe false
        }
    }

    "FAULT virtual cover is removed on the first session tick after its display is evicted" {
        StaffMagicBarriers.clear()
        var visualActive = true
        try {
            withStaffHarness { h ->
                val scenes = mutableListOf<CustomConjurationScene>()
                recordConjurationScenes(h, scenes, mutableListOf())
                every { h.effects.isActive(any()) } answers { visualActive }
                val world = h.paper.addSimpleWorld("staff-fault-q-evicted-wall")
                for (x in -3..3) for (z in 0..9) world.getBlockAt(x, 63, z).type = Material.STONE
                val player = h.player("fault-q-evicted-wall", world, StaffSpell.FAULT)
                h.controller.conjurations.cast(player, StaffSpell.FAULT, conjurationCast(player),
                    staffSettings().tuning.getValue(StaffSpell.FAULT)) shouldBe true
                scenes.size shouldBe 1

                val eye = player.eyeLocation
                (StaffMagicBarriers.firstHit(eye, eye.direction.normalize(), 8.0) != null) shouldBe true
                h.scheduler.tick(1)
                visualActive = false
                h.scheduler.tick(1)
                StaffMagicBarriers.firstHit(eye, eye.direction.normalize(), 8.0) shouldBe null
            }
        } finally {
            StaffMagicBarriers.clear()
        }
    }
})

private data class CustomConjurationScene(
    val id: UUID,
    val spell: StaffSpell,
    val durationTicks: Int,
    val geometry: (Int) -> List<StaffDisplayPart>,
)

private fun recordConjurationScenes(
    h: StaffHarness,
    scenes: MutableList<CustomConjurationScene>,
    removed: MutableList<UUID?>,
) {
    every { h.effects.custom(any(), any(), any(), any(), any()) } answers {
        val scene = CustomConjurationScene(UUID.randomUUID(),
            arg<StaffSpell>(1), arg<Int>(3), arg<(Int) -> List<StaffDisplayPart>>(4))
        scenes += scene
        scene.id
    }
    every { h.effects.remove(any()) } answers {
        removed += firstArg<UUID?>()
        Unit
    }
}

private fun conjurationCast(player: org.bukkit.entity.Player) =
    StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
