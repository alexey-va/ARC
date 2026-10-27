package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicesManager
import ru.arc.dungeons.api.ArcPvpService
import ru.arc.dungeons.api.ArcPvpService.Result

class StaffSpellPvpProtectionTest : FreeSpec({
    "spells use the shared area and clan result without role checks or passive feedback" {
        val manager = mockk<ServicesManager>()
        val service = mockk<ArcPvpService>(relaxed = true)
        val attacker = mockk<Player>()
        val victim = mockk<Player>()
        var result = Result.ALLOWED
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.getServicesManager() } returns manager
            every { manager.load(ArcPvpService::class.java) } returns service
            every { service.check(attacker, victim, attacker) } answers { result }
            StaffSpellPvpProtection.canAttack(attacker, victim) shouldBe true
            result = Result.SETTLEMENT
            StaffSpellPvpProtection.canAttack(attacker, victim) shouldBe false
            result = Result.TEAM
            StaffSpellPvpProtection.canAttack(attacker, victim) shouldBe false
            verify(exactly = 0) { service.notifyDenied(any(), any()) }
            StaffSpellPvpProtection.notifyDenied(attacker, victim)
            verify(exactly = 1) { service.notifyDenied(attacker, Result.TEAM) }
            verify(exactly = 0) { attacker.hasPermission(any<String>()) }
            verify(exactly = 0) { victim.hasPermission(any<String>()) }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }
})
