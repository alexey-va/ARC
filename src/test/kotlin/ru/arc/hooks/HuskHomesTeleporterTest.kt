package ru.arc.hooks

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import net.william278.huskhomes.api.HuskHomesAPI
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.teleport.Target
import net.william278.huskhomes.teleport.TeleportBuilder
import net.william278.huskhomes.teleport.TimedTeleport
import net.william278.huskhomes.user.BukkitUser
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.common.ServerLocation

class HuskHomesTeleporterTest : FreeSpec({
    "an unloaded remote world uses exact HuskHomes coordinates and its native warmup" {
        mockkStatic(Bukkit::class, HuskHomesAPI::class)
        try {
            val api = mockk<HuskHomesAPI>()
            val player = mockk<Player>()
            val user = mockk<BukkitUser>()
            val builder = mockk<TeleportBuilder>()
            val teleport = mockk<TimedTeleport>()
            val target = slot<Target>()
            every { Bukkit.getWorld("rc_origin_spawn") } returns null
            every { HuskHomesAPI.getInstance() } returns api
            every { api.adaptUser(player) } returns user
            every { api.teleportBuilder(user) } returns builder
            every { builder.target(capture(target)) } returns builder
            every { builder.toTimedTeleport() } returns teleport
            justRun { teleport.execute() }

            HuskHomesTeleporter.teleport(player, ServerLocation("spawn", "rc_origin_spawn", 0.5, 70.0, 0.5, 180f, 0f)) shouldBe true
            val position = target.captured as Position
            position.server shouldBe "spawn"
            position.world.name shouldBe "rc_origin_spawn"
            listOf(position.x, position.y, position.z) shouldBe listOf(0.5, 70.0, 0.5)
            position.yaw shouldBe 180f
            position.pitch shouldBe 0f
            verify(exactly = 1) { teleport.execute() }
            verify(exactly = 0) { builder.toTeleport() }
        } finally {
            unmockkStatic(Bukkit::class, HuskHomesAPI::class)
        }
    }
})
