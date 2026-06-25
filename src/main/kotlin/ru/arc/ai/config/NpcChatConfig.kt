package ru.arc.ai.config

import net.kyori.adventure.text.Component
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path

open class NpcChatConfig(
    private val config: Config,
) {
    open val messageFormat: String
        get() = config.string("message-format", "<gray><gold>%gpt_name%<gray> » <white>%message%")

    open val cancelAppendix: String
        get() =
            config.string(
                "cancel-appendix",
                "\n<red><hover:show_text:'Нажмите, чтобы закончить'><click:run_command:/arc ai stop %id%>[Нажмите, чтобы закончить разговор]</click></hover>",
            )

    open val endMessage: Component
        get() = config.component("end-message", "<red>Вы закончили разговор")

    open val endAllMessage: Component
        get() = config.component("end-all-message", "<red>Вы закончили все разговоры")

    open val maxBubbleLength: Int
        get() = config.integer("max-bubble-length", 500)

    open val bubbleDurationTicks: Int
        get() = config.integer("bubble-duration-ticks", 20 * 20)

    open val commonSystemMessages: List<String>
        get() = config.stringList("common-system-messages", emptyList())

    open fun cacheTtlMinutes(archetype: String): Long =
        config.integer("archetypes.$archetype.cache-ttl-minutes", 10).toLong()

    open fun maxHistoryLength(archetype: String): Int =
        config.integer("archetypes.$archetype.max-history-length", 100)

    open fun model(archetype: String, defaultModel: String): String =
        config.string("archetypes.$archetype.model", defaultModel)

    open fun maxTokens(archetype: String, defaultMaxTokens: Int): Int =
        config.integer("archetypes.$archetype.max-tokens", defaultMaxTokens)

    open fun temperature(archetype: String, defaultTemperature: Double): Double =
        config.real("archetypes.$archetype.temperature", defaultTemperature)

    open fun systemMessages(archetype: String): List<String> =
        config.stringList("archetypes.$archetype.system", emptyList())

    companion object {
        const val RESOURCE = "npc-chat.yml"

        fun load(dataPath: Path): NpcChatConfig {
            Config.copyDefaultConfig(ConfigManager.bundledModuleResource(RESOURCE), dataPath, replace = false)
            return NpcChatConfig(ConfigManager.ofModule(dataPath, RESOURCE))
        }
    }
}

class TestNpcChatConfig(
    override val messageFormat: String = "<gray>%gpt_name% » %message%",
    override val cancelAppendix: String = "",
    override val endMessage: Component = Component.text("end"),
    override val endAllMessage: Component = Component.text("end all"),
    override val maxBubbleLength: Int = 500,
    override val bubbleDurationTicks: Int = 200,
    override val commonSystemMessages: List<String> = emptyList(),
    private val archetypes: Map<String, ArchetypeSettings> = emptyMap(),
) : NpcChatConfig(EmptyConfig) {
    data class ArchetypeSettings(
        val cacheTtlMinutes: Long = 10,
        val maxHistoryLength: Int = 100,
        val model: String = "openai/gpt-4o-mini",
        val maxTokens: Int = 250,
        val temperature: Double = 0.7,
        val system: List<String> = emptyList(),
    )

    override fun cacheTtlMinutes(archetype: String): Long =
        archetypes[archetype]?.cacheTtlMinutes ?: 10

    override fun maxHistoryLength(archetype: String): Int =
        archetypes[archetype]?.maxHistoryLength ?: 100

    override fun model(archetype: String, defaultModel: String): String =
        archetypes[archetype]?.model ?: defaultModel

    override fun maxTokens(archetype: String, defaultMaxTokens: Int): Int =
        archetypes[archetype]?.maxTokens ?: defaultMaxTokens

    override fun temperature(archetype: String, defaultTemperature: Double): Double =
        archetypes[archetype]?.temperature ?: defaultTemperature

    override fun systemMessages(archetype: String): List<String> =
        archetypes[archetype]?.system ?: emptyList()
}
