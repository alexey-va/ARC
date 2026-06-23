package ru.arc.restart

import ru.arc.configs.Config
import java.time.Duration

sealed class RestartServerTarget {
    data object Current : RestartServerTarget()

    data object All : RestartServerTarget()

    data class Named(
        val servers: Set<String>,
    ) : RestartServerTarget()
}

data class RestartFlags(
    val serverTarget: RestartServerTarget = RestartServerTarget.Current,
    val delay: Duration? = null,
    val cancel: Boolean = false,
)

object RestartFlagParser {
    private val VALUE_FLAGS = setOf("servers", "delay")

    /**
     * Parses `-key:value` and `-key value` flags.
     */
    fun parseFlags(args: Array<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (!arg.startsWith("-")) {
                index++
                continue
            }

            val body = arg.substring(1)
            if (body.contains(":")) {
                val parts = body.split(":", limit = 2)
                result[parts[0].lowercase()] = parts[1]
                index++
                continue
            }

            val key = body.lowercase()
            if (index + 1 < args.size && !args[index + 1].startsWith("-")) {
                result[key] = args[index + 1]
                index += 2
            } else if (key !in VALUE_FLAGS) {
                result[key] = "true"
                index++
            } else {
                index++
            }
        }
        return result
    }

    fun parse(
        args: Array<String>,
        defaultDelay: Duration,
    ): RestartFlags {
        if (args.isNotEmpty() && args[0].equals("cancel", ignoreCase = true)) {
            val flags = parseFlags(args.copyOfRange(1, args.size))
            return RestartFlags(
                serverTarget = parseServerTarget(flags["servers"]),
                delay = null,
                cancel = true,
            )
        }

        val flags = parseFlags(args)
        val delay = parseDelay(flags["delay"]) ?: defaultDelay

        return RestartFlags(
            serverTarget = parseServerTarget(flags["servers"]),
            delay = delay,
            cancel = false,
        )
    }

    private fun parseServerTarget(raw: String?): RestartServerTarget {
        if (raw.isNullOrBlank()) return RestartServerTarget.Current
        if (raw.equals("all", ignoreCase = true)) return RestartServerTarget.All
        val servers =
            raw
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        return if (servers.isEmpty()) RestartServerTarget.Current else RestartServerTarget.Named(servers)
    }

    private fun parseDelay(raw: String?): Duration? {
        if (raw.isNullOrBlank()) return null
        if (raw.all { it.isDigit() }) {
            return Duration.ofSeconds(raw.toLong())
        }
        return Config.parseDuration(raw)
    }

    /** XAction `servers`: null = all, set = filter. */
    fun toXActionServers(target: RestartServerTarget): Set<String>? =
        when (target) {
            RestartServerTarget.All -> null
            is RestartServerTarget.Named -> target.servers
            RestartServerTarget.Current -> null // local only, not used for publish
        }

    fun requiresCrossServerPublish(
        target: RestartServerTarget,
        currentServer: String?,
    ): Boolean =
        when (target) {
            RestartServerTarget.All -> {
                true
            }

            is RestartServerTarget.Named -> {
                val normalized = currentServer?.lowercase()
                target.servers.size > 1 ||
                    (normalized != null && !target.servers.contains(normalized))
            }

            RestartServerTarget.Current -> {
                false
            }
        }

    fun runsOnThisServer(
        target: RestartServerTarget,
        currentServer: String?,
    ): Boolean {
        val server = currentServer?.lowercase() ?: return false
        return when (target) {
            RestartServerTarget.All -> true
            RestartServerTarget.Current -> true
            is RestartServerTarget.Named -> target.servers.contains(server)
        }
    }
}
