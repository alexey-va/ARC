package ru.arc.helpcenter

import org.bukkit.Bukkit
import ru.arc.ARC
import com.denizenscript.denizencore.scripts.ScriptRegistry
import com.denizenscript.denizencore.scripts.containers.ScriptContainer
import java.math.BigDecimal

/** Optional legacy producer; its data container is also read by the drop script. */
internal object HelpCenterDenizenSources {
    private var failureReported = false

    fun describe(topicId: String): List<HelpCenterEnchantmentSourceFact> {
        if (topicId != "ae_lootbox" || !Bukkit.getPluginManager().isPluginEnabled("Denizen")) return emptyList()
        return try {
            LoadedDenizenSources.read()
        } catch (failure: LinkageError) {
            report(failure)
            listOf(HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "legacy-mob-drops")))
        } catch (failure: IllegalArgumentException) {
            report(failure)
            listOf(HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "legacy-mob-drops")))
        }
    }

    private fun report(failure: Throwable) {
        if (failureReported) return
        failureReported = true
        ARC.instance.logger.log(java.util.logging.Level.WARNING, "AE help cannot read the loaded legacy mob-drop rules", failure)
    }
}

/** Isolate native types from the always-loaded help controller when Denizen is absent. */
private object LoadedDenizenSources {
    fun read(): List<HelpCenterEnchantmentSourceFact> {
        val script = ScriptRegistry.getScriptContainer<ScriptContainer>("ae_drop_sources")
            ?: return listOf(HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "legacy-mob-drops")))
        val event = ScriptRegistry.getScriptContainer<ScriptContainer>("kill_mob_event")
            ?: return emptyList()
        if (!event.shouldEnable() || script.getString("schema") != "1" || script.getString("preset") != "ae_lootbox") return emptyList()
        return denizenSourceFacts(script::getString)
    }
}

internal fun denizenSourceFacts(value: (String) -> String?): List<HelpCenterEnchantmentSourceFact> {
    fun number(key: String) = requireNotNull(value(key)?.toDoubleOrNull()?.takeIf(Double::isFinite)) { "Invalid loaded legacy drop value: $key" }
    fun formatted(number: Double) = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString().replace('.', ',')
    val base = number("monster.base-chance")
    val cap = number("monster.max-chance")
    val unit = number("monster.health-unit")
    val end = number("monster.enderman-multiplier")
    val spawner = number("monster.spawner-multiplier")
    require(base in 0.0..1.0 && cap in 0.0..1.0 && unit > 0 && end >= 0 && spawner >= 0)
    fun amount(key: String): String = requireNotNull(value(key)?.toIntOrNull()?.takeIf { it > 0 }).toString()
    return listOf(
        HelpCenterEnchantmentSourceFact("denizen-monsters", mapOf(
            "base" to formatted(base * 100), "cap" to formatted(cap * 100), "health" to formatted(unit),
            "end" to formatted(end), "spawner" to formatted(spawner),
            "world" to requireNotNull(value("monster.enderman-world")),
            "cooldown" to requireNotNull(value("monster.ratelimit")),
        )),
        HelpCenterEnchantmentSourceFact("denizen-bosses", mapOf(
            "wither" to amount("wither-amount"), "dragon" to amount("dragon-amount"),
        )),
    )
}
