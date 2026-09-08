package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class NativeWormholeCooldownsTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach {
        paper = MockBukkitTestRuntime.open()
        WormholeManagerFixture.current = WormholeManagerFixture()
    }
    afterEach { paper.close() }

    "leaving an arrival world releases the radius lock without removing or shortening cooldown" {
        val player = paper.addPlayer("exit")
        val dungeon = paper.addSimpleWorld("dungeon")
        val hub = paper.addSimpleWorld("guild")
        val record = WormholeManagerFixture.PlayerWormholeData(dungeon.spawnLocation, Long.MAX_VALUE)
        val records = WormholeManagerFixture.current!!.getPlayerTeleportData()
        records[player.uniqueId] = record
        player.teleport(hub.spawnLocation)

        NativeWormholeCooldowns({ WormholeManagerFixture::class.java }).leftWorld(player, dungeon)

        record.hasLeftTeleportRadius shouldBe true
        record.timeStamp shouldBe Long.MAX_VALUE
        records[player.uniqueId] shouldBe record
    }

    "same-world movement and a new destination lock are preserved" {
        val player = paper.addPlayer("arrival")
        val dungeon = paper.addSimpleWorld("dungeon")
        val hub = paper.addSimpleWorld("guild")
        val record = WormholeManagerFixture.PlayerWormholeData(dungeon.spawnLocation, 1L)
        WormholeManagerFixture.current!!.getPlayerTeleportData()[player.uniqueId] = record
        val cooldowns = NativeWormholeCooldowns({ WormholeManagerFixture::class.java })
        player.teleport(dungeon.spawnLocation)
        cooldowns.leftWorld(player, dungeon)
        record.hasLeftTeleportRadius shouldBe false
        cooldowns.leftWorld(player, hub)
        record.hasLeftTeleportRadius shouldBe false
    }

    "missing manager or record does not create native cooldown state" {
        val player = paper.addPlayer("missing")
        val dungeon = paper.addSimpleWorld("dungeon")
        val cooldowns = NativeWormholeCooldowns({ WormholeManagerFixture::class.java })
        cooldowns.leftWorld(player, dungeon)
        WormholeManagerFixture.current!!.getPlayerTeleportData().size shouldBe 0
        WormholeManagerFixture.current = null
        cooldowns.leftWorld(player, dungeon)
        WormholeManagerFixture.current shouldBe null
    }

    "unsupported native API fails safely and warns once" {
        val player = paper.addPlayer("unsupported")
        val dungeon = paper.addSimpleWorld("dungeon")
        val warnings = mutableListOf<String>()
        val cooldowns = NativeWormholeCooldowns({ String::class.java }, warnings::add)
        repeat(2) { cooldowns.leftWorld(player, dungeon) }
        warnings.size shouldBe 1
    }
})

class WormholeManagerFixture {
    private val records = mutableMapOf<UUID, PlayerWormholeData>()
    fun getPlayerTeleportData() = records

    class PlayerWormholeData(val destination: Location, val timeStamp: Long) {
        var hasLeftTeleportRadius = false
    }

    companion object {
        var current: WormholeManagerFixture? = null
        @JvmStatic fun getInstance(shuttingDown: Boolean): WormholeManagerFixture? {
            check(shuttingDown) { "Compatibility must not initialize the native manager" }
            return current
        }
    }
}
