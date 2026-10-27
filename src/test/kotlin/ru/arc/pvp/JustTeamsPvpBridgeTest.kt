package ru.arc.pvp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.dungeons.api.ArcPvpService.Result
import java.util.UUID

class JustTeamsPvpBridgeTest : StringSpec({
    "cached native team settings and either-direction alliances retain every native veto" {
        val attackerId = UUID.randomUUID()
        val victimId = UUID.randomUUID()
        val attacker = mockk<Player> { every { uniqueId } returns attackerId }
        val victim = mockk<Player> { every { uniqueId } returns victimId }
        val cache = PvpTeamsCacheFixture()
        val config = mockk<FileConfiguration>()
        var blockProjectiles = false
        var blockTnt = false
        every { config.getBoolean("settings.friendly_fire.block_projectiles", false) } answers { blockProjectiles }
        every { config.getBoolean("settings.friendly_fire.block_tnt", false) } answers { blockTnt }
        val plugin = mockk<JavaPlugin>(moreInterfaces = arrayOf(PvpTeamsPluginFixture::class))
        every { (plugin as PvpTeamsPluginFixture).getTeamManager() } returns cache
        every { plugin.config } returns config
        val bridge = JustTeamsPvpBridge(plugin)
        bridge.check(attacker, victim, attacker) shouldBe Result.ALLOWED
        cache.teams[attackerId] = PvpTeamFixture(1, false)
        cache.teams[victimId] = PvpTeamFixture(1, false)
        bridge.check(attacker, victim, attacker) shouldBe Result.TEAM
        cache.teams[victimId] = PvpTeamFixture(1, true)
        bridge.check(attacker, victim, attacker) shouldBe Result.ALLOWED
        blockProjectiles = true
        bridge.check(attacker, victim, mockk<Projectile>()) shouldBe Result.TEAM_PROJECTILE
        blockTnt = true
        bridge.check(attacker, victim, mockk<TNTPrimed>()) shouldBe Result.TEAM_TNT
        cache.teams[victimId] = PvpTeamFixture(2, true, listOf(1))
        bridge.check(attacker, victim, attacker) shouldBe Result.ALLY
        cache.teams[victimId] = PvpTeamFixture(2, true)
        cache.teams[attackerId] = PvpTeamFixture(1, true, listOf(2))
        bridge.check(attacker, victim, attacker) shouldBe Result.ALLY
        cache.teams[attackerId] = PvpTeamFixture(1, true)
        bridge.check(attacker, victim, attacker) shouldBe Result.ALLOWED
    }
})

interface PvpTeamsPluginFixture { fun getTeamManager(): PvpTeamsCacheFixture }
class PvpTeamsCacheFixture {
    val teams = mutableMapOf<UUID, PvpTeamFixture>()
    fun getPlayerTeamCached(id: UUID): PvpTeamFixture? = teams[id]
}
class PvpTeamFixture(private val id: Int, private val pvp: Boolean, private val alliances: List<Int> = emptyList()) {
    fun getId(): Int = id
    fun isPvpEnabled(): Boolean = pvp
    fun getAllies(): List<Int> = alliances
}
