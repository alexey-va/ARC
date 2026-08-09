package ru.arc.commandhide

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path

data class CommandHideGroupConfig(
    val id: String,
    val inherits: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
)

open class CommandHideModuleConfig(
    private val config: Config,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", true)

    open val stripCommandNamespace: Boolean
        get() = config.bool("strip-command-namespace", true)

    open val bypassPermission: String
        get() = config.string("bypass-permission", "arc.hide.bypass")

    open val policyCacheMillis: Long
        get() = config.durationMillis("policy-cache", 5_000L).coerceAtLeast(0L)

    open val blockedMessage: String
        get() = config.string("blocked-message", "<red>Неизвестная команда.")

    open val groups: List<CommandHideGroupConfig>
        get() =
            config.keys("groups").sorted().map { id ->
                CommandHideGroupConfig(
                    id = id,
                    inherits = config.stringList("groups.$id.inherits"),
                    commands = config.stringList("groups.$id.commands"),
                )
            }

    companion object {
        fun load(dataPath: Path): CommandHideModuleConfig =
            CommandHideModuleConfig(ConfigManager.ofModule(dataPath, "command-hide.yml"))
    }
}

class TestCommandHideModuleConfig(
    override val enabled: Boolean = true,
    override val stripCommandNamespace: Boolean = true,
    override val bypassPermission: String = "",
    override val policyCacheMillis: Long = 5_000L,
    override val blockedMessage: String = "blocked",
    override val groups: List<CommandHideGroupConfig> = emptyList(),
) : CommandHideModuleConfig(EmptyConfig)
