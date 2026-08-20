package ru.arc.ai.config

import net.kyori.adventure.text.Component
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path

open class NpcChatConfig(
    private val config: Config,
    private val dataPath: Path = Path.of("."),
) {
    data class Persona(
        val id: String,
        val npcName: String,
        val displayName: String,
        val openingLine: String,
        val closingLine: String?,
        val radius: Double,
        val lifeTimeMillis: Long,
        val privateConversation: Boolean,
    )

    open val messageFormat: String
        get() = config.string("message-format", "<gray><gold>%gpt_name%<gray> » <white>%message%")

    open val cancelAppendix: String
        get() =
            config.string(
                "cancel-appendix",
                "\n<dark_gray><hover:show_text:'Закончить разговор'><click:run_command:/arc npc-chat stop %id%>[закончить разговор]</click></hover>",
            )

    open val endMessage: Component
        get() = config.component("end-message", "<red>Вы закончили разговор")

    open val endAllMessage: Component
        get() = config.component("end-all-message", "<red>Вы закончили все разговоры")

    open val maxBubbleLength: Int
        get() = config.integer("max-bubble-length", 500)

    open val bubbleDurationTicks: Int
        get() = config.integer("bubble-duration-ticks", 20 * 20)

    open val maxInputChars: Int
        get() = config.integer("max-input-chars", 240).coerceIn(32, 240)

    open val maxOutputChars: Int
        get() = config.integer("max-output-chars", 280).coerceIn(64, 280)

    open val maxHistoryTurns: Int
        get() = config.integer("max-history-turns", 6).coerceIn(0, 6)

    open val cooldownMillis: Long
        get() = config.integer("cooldown-seconds", 4).coerceIn(0, 30) * 1_000L

    open val fallbackMessage: Component
        get() =
            config.component(
                "fallback-message",
                "<gray>Сейчас связь барахлит. Спроси ещё раз чуть позже.",
            )

    open val tooLongMessage: Component
        get() = config.component("too-long-message", "<gray>Скажи короче — до 240 символов.")

    open fun persona(id: String): Persona? {
        if (id !in config.keys("personas")) return null
        val prefix = "personas.$id"
        val npcName = config.string("$prefix.npc-name", "").trim()
        if (npcName.isEmpty()) return null
        return Persona(
            id = id,
            npcName = npcName,
            displayName = config.string("$prefix.display-name", npcName),
            openingLine = config.string("$prefix.opening-line", "Спрашивай, если что-то нужно."),
            closingLine = config.stringOrNull("$prefix.closing-line")?.takeIf(String::isNotBlank),
            radius = config.real("$prefix.radius", 5.0).coerceIn(2.0, 12.0),
            lifeTimeMillis = config.integer("$prefix.lifetime-seconds", 180).coerceIn(30, 600) * 1_000L,
            privateConversation = config.bool("$prefix.private", true),
        )
    }

    open fun personaIds(): Set<String> = config.keys("personas")

    /** NPC prompts: `prompts/npc/common.txt` + `prompts/npc/{archetype}.txt` (plain text, no YAML). */
    open fun systemPrompt(archetype: String): String {
        val fromFiles =
            buildList {
                PromptFiles.readText(dataPath, "prompts/npc/common.txt")?.let { add(it) }
                PromptFiles.readText(dataPath, "prompts/npc/$archetype.txt")?.let { add(it) }
            }
        if (fromFiles.isNotEmpty()) {
            return fromFiles.joinToString("\n\n")
        }
        return buildString {
            config.stringList("common-system-messages", emptyList()).forEach { appendLine(it) }
            config.stringList("archetypes.$archetype.system", emptyList()).forEach { appendLine(it) }
        }.trim()
    }

    open fun cacheTtlMinutes(archetype: String): Long =
        config.integer("archetypes.$archetype.cache-ttl-minutes", 10).toLong()

    open fun maxHistoryLength(archetype: String): Int =
        config.integer("archetypes.$archetype.max-history-length", maxHistoryTurns)

    open fun model(archetype: String, defaultModel: String): String =
        config.string("archetypes.$archetype.model", defaultModel)

    open fun maxTokens(archetype: String, defaultMaxTokens: Int): Int =
        config.integer("archetypes.$archetype.max-tokens", defaultMaxTokens)

    open fun temperature(archetype: String, defaultTemperature: Double): Double =
        config.real("archetypes.$archetype.temperature", defaultTemperature)

    companion object {
        const val RESOURCE = "npc-chat.yml"

        fun load(dataPath: Path): NpcChatConfig {
            Config.copyDefaultConfig(ConfigManager.bundledModuleResource(RESOURCE), dataPath, replace = false)
            return NpcChatConfig(ConfigManager.ofModule(dataPath, RESOURCE), dataPath)
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
    override val maxInputChars: Int = 240,
    override val maxOutputChars: Int = 280,
    override val maxHistoryTurns: Int = 6,
    override val cooldownMillis: Long = 4_000,
    override val fallbackMessage: Component = Component.text("fallback"),
    override val tooLongMessage: Component = Component.text("too long"),
    private val prompts: Map<String, String> = emptyMap(),
    private val archetypes: Map<String, ArchetypeSettings> = emptyMap(),
    private val personas: Map<String, Persona> = emptyMap(),
) : NpcChatConfig(EmptyConfig) {
    data class ArchetypeSettings(
        val cacheTtlMinutes: Long = 10,
        val maxHistoryLength: Int = 100,
        val model: String = "openai/gpt-4o-mini",
        val maxTokens: Int = 250,
        val temperature: Double = 0.7,
    )

    override fun systemPrompt(archetype: String): String = prompts[archetype] ?: ""

    override fun persona(id: String): Persona? = personas[id]

    override fun personaIds(): Set<String> = personas.keys

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
}
