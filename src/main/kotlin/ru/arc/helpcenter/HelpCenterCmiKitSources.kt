package ru.arc.helpcenter

import com.Zrips.CMI.CMI
import org.bukkit.Bukkit
import ru.arc.itemcatalog.RewardCatalogSource
import ru.arc.ops.BundleDefinition
import ru.arc.ops.ItemPresets
import java.util.Locale

/** CMI remains the owner of kit availability, permissions and cooldowns. */
internal data class HelpCenterCmiKit(
    val commandName: String,
    val enabled: Boolean,
    val delaySeconds: Long,
    val commands: List<String>,
)

internal object HelpCenterCmiKitSources {
    private const val MAX_KITS = 256
    private const val MAX_COMMANDS = 64
    private const val MAX_PRESETS = 256

    fun describe(topicId: String, snapshot: HelpCenterEnchantmentSourceSnapshot): List<HelpCenterEnchantmentSourceFact> {
        if (topicId != "overview" && topicId !in HelpCenterEnchantmentSources.boxTopics) return emptyList()
        if (!Bukkit.getPluginManager().isPluginEnabled("CMI")) return emptyList()
        return try {
            val kits = CMI.getInstance().kitsManager.kitMap.values.toList()
            if (kits.size > MAX_KITS || kits.any { (it.commands?.size ?: 0) > MAX_COMMANDS }) return unavailable()
            val captured = kits.map { kit ->
                HelpCenterCmiKit(kit.commandName, kit.isEnabled, kit.delay, kit.commands?.toList().orEmpty())
            }
            val requested = captured.flatMap { kit -> kit.commands.mapNotNull(::arcGive) }.map { it.first }.distinct()
            if (requested.size > MAX_PRESETS) return unavailable()
            val definitions = ItemPresets.loadedDefinitions(requested) ?: return unavailable()
            val nested = definitions.filterIsInstance<BundleDefinition>().flatMap { it.items.map { entry -> entry.preset } }.distinct()
            if (nested.size > MAX_PRESETS) return unavailable()
            val children = ItemPresets.loadedDefinitions(nested) ?: return unavailable()
            val prepared = snapshot.copy(presets = (snapshot.presets.orEmpty() + definitions + children).distinctBy { it.id })
            describe(topicId, prepared, captured)
        } catch (_: Exception) {
            unavailable()
        } catch (_: LinkageError) {
            unavailable()
        }
    }

    internal fun describe(
        topicId: String,
        snapshot: HelpCenterEnchantmentSourceSnapshot,
        kits: List<HelpCenterCmiKit>,
    ): List<HelpCenterEnchantmentSourceFact> = buildList {
        val topics = if (topicId == "overview") HelpCenterEnchantmentSources.boxTopics else listOf(topicId)
        kits.filter { it.enabled && it.commandName.matches(KIT_NAME) && it.delaySeconds >= 0 }.forEach { kit ->
            val outcomes = kit.commands.mapNotNull(::arcGive).flatMap { (preset, amount) ->
                HelpCenterEnchantmentSources.rewardOutcomes(RewardCatalogSource.Preset(preset), snapshot, amount)
            }
            topics.forEach { topic ->
                val matches = outcomes.filter { it.topicId == topic && it.probability == 1.0 }
                val amount = matches.sumOf { it.amount }
                if (amount > 0) add(HelpCenterEnchantmentSourceFact("cmi-kit-source", mapOf(
                    "kit" to kit.commandName, "item" to topic, "amount" to amount.toString(),
                    "cooldown" to helpCooldown(kit.delaySeconds),
                )))
            }
        }
    }

    /** A fixed CMI command grammar; arbitrary console commands are never executed or interpreted. */
    internal fun arcGive(command: String): Pair<String, Int>? {
        val words = command.trim().split(WHITESPACE)
        if (words.size !in 5..6 || !words[0].equals("asConsole!", true) ||
            !words[1].equals("arc", true) || !words[2].equals("give", true) || words[3] != "[playerName]"
        ) return null
        val preset = words[4].lowercase(Locale.ROOT)
        if (!PRESET_NAME.matches(preset)) return null
        val amount = if (words.size == 5) 1 else words[5].toIntOrNull()?.coerceIn(1, 64) ?: return null
        return preset to amount
    }

    private fun unavailable() = listOf(HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "cmi-kits")))
    private val WHITESPACE = Regex("\\s+")
    private val KIT_NAME = Regex("[A-Za-z0-9_]{1,64}")
    private val PRESET_NAME = Regex("[a-z][a-z0-9_]{0,63}")
}

internal fun helpCooldown(seconds: Long): String = when {
    seconds > 0 && seconds % 86_400L == 0L -> "${seconds / 86_400L}d"
    seconds > 0 && seconds % 3_600L == 0L -> "${seconds / 3_600L}h"
    seconds > 0 && seconds % 60L == 0L -> "${seconds / 60L}m"
    else -> "${seconds}s"
}
