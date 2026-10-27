package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import me.angeschossen.lands.api.land.LandWorld
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import java.util.UUID

class StaffSpellLandsProtectionTest : FreeSpec({
    "spells check both actual roles at the victim location without general permission bypass" {
        val manager = mockk<PluginManager>()
        val plugin = mockk<Plugin>()
        val lands = mockk<LandsIntegration>()
        val world = mockk<World>()
        val protectedWorld = mockk<LandWorld>()
        val attacker = mockk<Player>()
        val victim = mockk<Player>()
        val attackerId = UUID.randomUUID()
        val victimId = UUID.randomUUID()
        val at = Location(world, 10.0, 64.0, 20.0)
        mockkStatic(Bukkit::class, LandsIntegration::class)
        try {
            every { Bukkit.getPluginManager() } returns manager
            every { manager.isPluginEnabled("Lands") } returns false
            StaffSpellLandsProtection.canAttack(attacker, victim) shouldBe true
            verify(exactly = 0) { LandsIntegration.of(any()) }
            every { manager.isPluginEnabled("Lands") } returns true
            every { manager.getPlugin("ArcDungeons") } returns plugin
            every { LandsIntegration.of(plugin) } returns lands
            every { attacker.uniqueId } returns attackerId
            every { victim.uniqueId } returns victimId
            every { victim.world } returns world
            every { victim.location } returns at
            every { lands.getWorld(world) } returns protectedWorld
            var attackerAllowed = false
            var victimAllowed = true
            every { protectedWorld.hasRoleFlag(attackerId, at, Flags.ATTACK_PLAYER) } answers { attackerAllowed }
            every { protectedWorld.hasRoleFlag(victimId, at, Flags.ATTACK_PLAYER) } answers { victimAllowed }
            StaffSpellLandsProtection.canAttack(attacker, victim) shouldBe false
            attackerAllowed = true
            victimAllowed = false
            StaffSpellLandsProtection.canAttack(attacker, victim) shouldBe false
            victimAllowed = true
            StaffSpellLandsProtection.canAttack(attacker, victim) shouldBe true
            verify(exactly = 0) { attacker.hasPermission(any<String>()) }
            verify(exactly = 0) { victim.hasPermission(any<String>()) }
            every { lands.getWorld(world) } returns null
            StaffSpellLandsProtection.canAttack(attacker, victim) shouldBe true
        } finally {
            unmockkStatic(Bukkit::class, LandsIntegration::class)
        }
    }
})
