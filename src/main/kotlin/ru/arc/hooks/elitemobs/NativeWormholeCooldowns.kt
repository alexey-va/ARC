package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.economy.EconomyHandler
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.util.Logging

/** Compatibility with EliteMobs' arrival-radius lock after a world exit. */
internal class NativeWormholeCooldowns(
    private val managerClass: () -> Class<*> = {
        Class.forName("com.magmaguy.elitemobs.wormhole.WormholeManager", false, EconomyHandler::class.java.classLoader)
    },
    private val warn: (String) -> Unit = { Logging.warn(it) },
) {
    private var failureReported = false
    private val access by lazy { runCatching { Access(managerClass()) }.onFailure(::reportFailure).getOrNull() }

    fun leftWorld(player: Player, from: World) {
        if (player.world.uid == from.uid) return
        val access = access ?: return
        runCatching {
            // true only reads the existing singleton; false starts its native task.
            val manager = access.instance.invoke(null, true) ?: return
            val records = access.records.invoke(manager) as Map<*, *>
            val record = records[player.uniqueId] ?: return
            val destination = access.destination.get(record) as Location
            if (destination.world?.uid != from.uid) return
            // Leaving the world also leaves its arrival radius. Keep the record and
            // timestamp so EliteMobs still enforces and expires its own cooldown.
            access.leftRadius.setBoolean(record, true)
        }.onFailure(::reportFailure)
    }

    private fun reportFailure(error: Throwable) {
        if (failureReported) return
        failureReported = true
        warn("EliteMobs wormhole exit compatibility unavailable: ${error.javaClass.simpleName}: ${error.message}")
    }

    private class Access(type: Class<*>) {
        val instance = type.getMethod("getInstance", Boolean::class.javaPrimitiveType)
        val records = type.getMethod("getPlayerTeleportData")
        private val record = type.declaredClasses.single { it.simpleName == "PlayerWormholeData" }
        val destination = record.getDeclaredField("destination").apply {
            check(this.type == Location::class.java)
            isAccessible = true
        }
        val leftRadius = record.getDeclaredField("hasLeftTeleportRadius").apply {
            check(this.type == Boolean::class.javaPrimitiveType)
            isAccessible = true
        }
    }
}
