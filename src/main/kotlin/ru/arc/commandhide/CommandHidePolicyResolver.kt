package ru.arc.commandhide

import net.kyori.adventure.text.Component
import ru.arc.util.TextUtil
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class ResolvedCommandHideGroup(
    val id: String,
    val permission: String,
    val patterns: List<CommandPattern>,
)

internal class CommandHideSnapshot private constructor(
    val enabled: Boolean,
    val stripCommandNamespace: Boolean,
    val hideNamespacedRoots: Boolean,
    val bypassPermission: String,
    val policyCacheNanos: Long,
    val blockedMessage: Component?,
    val groups: List<ResolvedCommandHideGroup>,
) {
    private val combinedPolicies = ConcurrentHashMap<List<String>, CommandHidePolicy>()
    private val emptyPolicy = CommandHidePolicy.empty(stripCommandNamespace)

    val groupCount: Int get() = groups.size

    val patternCount: Int = groups.flatMap(ResolvedCommandHideGroup::patterns).distinctBy(CommandPattern::canonical).size

    fun policy(checkPermission: (String) -> Boolean): CommandHidePolicy {
        if (!enabled) return emptyPolicy
        if (bypassPermission.isNotEmpty() && checkPermission(bypassPermission)) {
            return emptyPolicy
        }

        val selected = groups.filter { checkPermission(it.permission) }
        if (selected.isEmpty()) return emptyPolicy

        val key = selected.map(ResolvedCommandHideGroup::id)
        return combinedPolicies.computeIfAbsent(key) {
            CommandHidePolicy(
                patterns = selected.flatMap(ResolvedCommandHideGroup::patterns),
                stripCommandNamespace = stripCommandNamespace,
                hideNamespacedRoots = hideNamespacedRoots,
                blockedMessage = blockedMessage,
            )
        }
    }

    companion object {
        private val groupId = Regex("[a-z0-9][a-z0-9._-]*")

        fun compile(config: CommandHideModuleConfig): CommandHideSnapshot {
            val rawGroups = LinkedHashMap<String, CommandHideGroupConfig>()
            for (group in config.groups) {
                val normalizedId = group.id.trim().lowercase(Locale.ROOT)
                require(normalizedId.matches(groupId)) {
                    "Invalid command-hide group id '${group.id}'"
                }
                require(rawGroups.put(normalizedId, group.copy(id = normalizedId)) == null) {
                    "Duplicate command-hide group id '$normalizedId'"
                }
            }

            val parsedOwnPatterns =
                rawGroups.mapValues { (_, group) ->
                    group.commands.map { command ->
                        CommandPattern.parse(command, config.stripCommandNamespace)
                    }
                }
            val resolvedPatterns = HashMap<String, List<CommandPattern>>()
            val visiting = LinkedHashSet<String>()

            fun resolve(id: String): List<CommandPattern> {
                resolvedPatterns[id]?.let { return it }
                val group = checkNotNull(rawGroups[id]) { "Unknown command-hide group '$id'" }
                check(visiting.add(id)) {
                    "Command-hide group inheritance cycle: ${(visiting.toList() + id).joinToString(" -> ")}"
                }

                val inherited =
                    group.inherits.flatMap { rawParent ->
                        val parent = rawParent.trim().lowercase(Locale.ROOT)
                        require(parent in rawGroups) {
                            "Command-hide group '$id' inherits unknown group '$rawParent'"
                        }
                        resolve(parent)
                    }
                visiting.remove(id)

                return (inherited + parsedOwnPatterns.getValue(id))
                    .distinctBy(CommandPattern::canonical)
                    .also { resolvedPatterns[id] = it }
            }

            val groups =
                rawGroups.keys.sorted().map { id ->
                    ResolvedCommandHideGroup(
                        id = id,
                        permission = PERMISSION_PREFIX + id,
                        patterns = resolve(id),
                    )
                }

            return CommandHideSnapshot(
                enabled = config.enabled,
                stripCommandNamespace = config.stripCommandNamespace,
                hideNamespacedRoots = config.hideNamespacedRoots,
                bypassPermission = config.bypassPermission.trim().lowercase(Locale.ROOT),
                policyCacheNanos = TimeUnit.MILLISECONDS.toNanos(config.policyCacheMillis),
                blockedMessage = config.blockedMessage.takeIf(String::isNotBlank)?.let(TextUtil::mm),
                groups = groups,
            )
        }

        const val PERMISSION_PREFIX = "arc.command.hide."
    }
}

internal class CommandHidePolicyResolver(
    initialConfig: CommandHideModuleConfig,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class CachedPolicy(
        val snapshot: CommandHideSnapshot,
        val createdAtNanos: Long,
        val policy: CommandHidePolicy,
    )

    @Volatile
    private var snapshot = CommandHideSnapshot.compile(initialConfig)

    private val playerPolicies = ConcurrentHashMap<UUID, CachedPolicy>()

    fun policy(
        playerId: UUID,
        checkPermission: (String) -> Boolean,
    ): CommandHidePolicy {
        val current = snapshot
        val now = nanoTime()
        val cached = playerPolicies[playerId]
        if (
            cached != null &&
            cached.snapshot === current &&
            current.policyCacheNanos > 0L &&
            now - cached.createdAtNanos < current.policyCacheNanos
        ) {
            return cached.policy
        }
        return cache(playerId, current, now, checkPermission)
    }

    fun refresh(
        playerId: UUID,
        checkPermission: (String) -> Boolean,
    ): CommandHidePolicy = cache(playerId, snapshot, nanoTime(), checkPermission)

    fun cached(playerId: UUID): CommandHidePolicy? {
        val current = snapshot
        return playerPolicies[playerId]?.takeIf { it.snapshot === current }?.policy
    }

    fun invalidate(playerId: UUID) {
        playerPolicies.remove(playerId)
    }

    fun clear() {
        playerPolicies.clear()
    }

    fun reload(config: CommandHideModuleConfig): CommandHideSnapshot {
        val replacement = CommandHideSnapshot.compile(config)
        snapshot = replacement
        playerPolicies.clear()
        return replacement
    }

    fun currentSnapshot(): CommandHideSnapshot = snapshot

    private fun cache(
        playerId: UUID,
        current: CommandHideSnapshot,
        now: Long,
        checkPermission: (String) -> Boolean,
    ): CommandHidePolicy {
        val policy = current.policy(checkPermission)
        playerPolicies[playerId] = CachedPolicy(current, now, policy)
        return policy
    }
}
