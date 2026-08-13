package ru.arc.sync.duels

import java.lang.reflect.InvocationTargetException
import java.util.UUID

internal interface DuelStatsGateway {
    fun read(playerId: UUID): Map<String, Any?>

    fun apply(playerId: UUID, values: Map<String, Any?>)
}

internal class ReflectiveDuelStatsGateway(
    private val plugin: Any,
) : DuelStatsGateway {
    private val readMethod = plugin.javaClass.getMethod("getStatsData", UUID::class.java)
    private val applyMethod = plugin.javaClass.getMethod("applyStatsData", UUID::class.java, Map::class.java)

    override fun read(playerId: UUID): Map<String, Any?> {
        val raw = invoke { readMethod.invoke(plugin, playerId) }
        require(raw is Map<*, *>) { "Duels getStatsData must return a Map" }
        return raw.entries.associate { (key, value) ->
            require(key is String) { "Duels stats keys must be strings" }
            key to value
        }
    }

    override fun apply(
        playerId: UUID,
        values: Map<String, Any?>,
    ) {
        invoke { applyMethod.invoke(plugin, playerId, values) }
    }

    private fun invoke(block: () -> Any?): Any? =
        try {
            block()
        } catch (exception: InvocationTargetException) {
            throw (exception.targetException ?: exception)
        }
}
