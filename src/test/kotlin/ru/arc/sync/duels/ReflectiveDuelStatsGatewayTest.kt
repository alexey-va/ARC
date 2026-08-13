package ru.arc.sync.duels

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.maps.shouldContainExactly
import java.util.UUID

class ReflectiveDuelStatsGatewayTest :
    StringSpec({
        "reads and applies stats through the JDK-only plugin boundary" {
            val playerId = UUID.randomUUID()
            val plugin = FakeDuelsPlugin()
            val gateway = ReflectiveDuelStatsGateway(plugin)

            gateway.read(playerId) shouldContainExactly mapOf("wins" to 7, "rating" to 1032)
            gateway.apply(playerId, mapOf("wins" to 8, "rating" to 1048))

            plugin.appliedId shouldBe playerId
            plugin.applied shouldContainExactly mapOf("wins" to 8, "rating" to 1048)
        }
    })

private class FakeDuelsPlugin {
    var appliedId: UUID? = null
    var applied: Map<String, Any?> = emptyMap()

    @Suppress("unused")
    fun getStatsData(uuid: UUID): Map<String, Any?> = mapOf("wins" to 7, "rating" to 1032)

    @Suppress("unused")
    fun applyStatsData(
        uuid: UUID,
        values: Map<String, Any?>,
    ) {
        appliedId = uuid
        applied = values
    }
}
