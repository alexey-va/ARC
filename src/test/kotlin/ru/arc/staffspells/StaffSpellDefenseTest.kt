package ru.arc.staffspells

import io.mockk.every
import io.mockk.mockk
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import ru.arc.util.CooldownManager
import java.util.UUID

class StaffSpellDefenseTest : FreeSpec({
    "guard blocks frontal attacks within its hit budget, leaves the rear open, expires, and has independent cooldown" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-arc")
            val player = h.player("staff-defense-arc", world, StaffSpell.FAULT)
            val front = h.zombie(world, x = 0.5, z = 3.0)
            val rear = h.zombie(world, x = 0.5, z = -2.0)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            var closed = false
            try {
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
                CooldownManager.isOnCooldown(player.uniqueId, "staff-spells") shouldBe false
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe false

                val rearHit = attack(rear, player)
                defense.onDamage(rearHit)
                rearHit.isCancelled shouldBe false

                repeat(3) {
                    val frontalHit = attack(front, player)
                    defense.onDamage(frontalHit)
                    frontalHit.isCancelled shouldBe true
                }
                val overBudgetHit = attack(front, player)
                defense.onDamage(overBudgetHit)
                overBudgetHit.isCancelled shouldBe false

                h.scheduler.tick(80)
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
                h.scheduler.tick(32)
                val expiredHit = attack(front, player)
                defense.onDamage(expiredHit)
                expiredHit.isCancelled shouldBe false

                h.scheduler.tick(80)
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
                defense.close()
                closed = true
                val closedHit = attack(front, player)
                defense.onDamage(closedHit)
                closedHit.isCancelled shouldBe false
            } finally {
                if (!closed) defense.close()
            }
        }
    }
})

private fun attack(damager: LivingEntity, player: org.bukkit.entity.Player) = EntityDamageByEntityEvent(
    damager,
    player,
    EntityDamageEvent.DamageCause.ENTITY_ATTACK,
    4.0,
)
