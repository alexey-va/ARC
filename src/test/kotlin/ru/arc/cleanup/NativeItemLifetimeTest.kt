package ru.arc.cleanup

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

class NativeItemLifetimeTest : FreeSpec({
    "loads active Spigot defaults and Paper world snapshots" {
        val world = world("survival")
        val custom = world("custom")
        val spigot = yaml("""
            world-settings:
              default:
                item-despawn-rate: 6000
              custom:
                item-despawn-rate: 1200
        """.trimIndent())
        val paper = yaml("""
            __________WORLDS__________:
              __defaults__:
                entities:
                  spawning:
                    alt-item-despawn-rate:
                      enabled: true
                      items:
                        cobblestone: 3600
                        egg: 180
              custom:
                entities:
                  spawning:
                    alt-item-despawn-rate:
                      items:
                        egg: 90
        """.trimIndent())
        val resolver = NativeItemLifetime.load(server(listOf(world, custom), spigot, paper))

        resolver.ticks(world, Material.COBBLESTONE) shouldBe 3600
        resolver.ticks(world, Material.EGG) shouldBe 180
        resolver.ticks(world, Material.DIAMOND_SWORD) shouldBe 6000
        resolver.ticks(custom, Material.EGG) shouldBe 90
        resolver.ticks(custom, Material.DIAMOND_SWORD) shouldBe 1200
    }

    "disabled Paper override uses Spigot rate and invalid per-world Spigot fails closed" {
        val disabled = world("disabled")
        val invalid = world("invalid")
        val spigot = yaml("""
            world-settings:
              default:
                item-despawn-rate: 6000
              invalid:
                item-despawn-rate: nope
        """.trimIndent())
        val paper = yaml("""
            __________WORLDS__________:
              __defaults__:
                entities:
                  spawning:
                    alt-item-despawn-rate:
                      enabled: true
                      items:
                        cobblestone: 3600
              disabled:
                entities:
                  spawning:
                    alt-item-despawn-rate:
                      enabled: false
        """.trimIndent())
        val resolver = NativeItemLifetime.load(server(listOf(disabled, invalid), spigot, paper))

        resolver.ticks(disabled, Material.COBBLESTONE) shouldBe 6000
        resolver.ticks(invalid, Material.STONE).shouldBeNull()
    }

    "unknown world is fail closed" {
        val known = world("known")
        val unknown = world("unknown")
        val resolver = NativeItemLifetime.load(
            server(listOf(known), yaml("world-settings.default.item-despawn-rate: 6000"), yaml("")),
        )

        resolver.ticks(unknown, Material.STONE).shouldBeNull()
    }
}) {
    companion object {
        private fun world(name: String): World = mockk<World> {
            every { uid } returns UUID.nameUUIDFromBytes(name.toByteArray())
            every { this@mockk.name } returns name
        }

        private fun yaml(content: String): YamlConfiguration = YamlConfiguration().also { it.loadFromString(content) }

        private fun server(worlds: List<World>, spigot: YamlConfiguration, paper: YamlConfiguration): Server {
            val bridge = mockk<Server.Spigot>()
            every { bridge.spigotConfig } returns spigot
            every { bridge.paperConfig } returns paper
            return mockk {
                every { this@mockk.worlds } returns worlds
                every { spigot() } returns bridge
            }
        }
    }
}
