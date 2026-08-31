package ru.arc.hooks

import org.bukkit.OfflinePlayer
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Bounded, player-aware cache for explicitly wrapped PlaceholderAPI values.
 *
 * The delegate always runs on the caller's thread. This class deliberately
 * does not make third-party expansions asynchronous or otherwise change their
 * threading contract.
 */
internal class CachedPlaceholderResolver(
    private val delegate: (OfflinePlayer?, String) -> String,
    private val ticker: () -> Long = System::nanoTime,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxValueLength: Int = DEFAULT_MAX_VALUE_LENGTH,
) {
    private data class CacheKey(
        val playerId: UUID?,
        val ttlSeconds: Int,
        val innerPlaceholder: String,
        val outputMode: OutputMode,
    )

    private data class CacheEntry(
        val value: String,
        val cachedAtNanos: Long,
    )

    private data class Request(
        val ttlSeconds: Int,
        val innerPlaceholder: String,
        val outputMode: OutputMode,
    )

    private enum class OutputMode {
        PRESERVE,
        PLAIN,
    }

    private val lock = Any()
    private val resolving = ThreadLocal.withInitial { false }
    private val entries =
        object : LinkedHashMap<CacheKey, CacheEntry>(capacity, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?,
            ): Boolean = size > capacity
        }

    private var generation = 0L
    private var lastObservedNanos: Long? = null

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxValueLength >= 0) { "maxValueLength must not be negative" }
    }

    /** Returns null for an invalid or recursively nested cache request. */
    fun resolve(player: OfflinePlayer?, params: String): String? {
        val request = parseRequest(params) ?: return null
        if (resolving.get()) return null

        val key = CacheKey(
            player?.uniqueId,
            request.ttlSeconds,
            request.innerPlaceholder,
            request.outputMode,
        )
        val ttlNanos = request.ttlSeconds * NANOS_PER_SECOND
        val missGeneration =
            synchronized(lock) {
                val lookupNanos = ticker()
                observeClockLocked(lookupNanos)
                entries[key]?.let { entry ->
                    val age = lookupNanos - entry.cachedAtNanos
                    if (age >= 0L && age < ttlNanos) return entry.value
                    entries.remove(key)
                }
                generation
            }

        val token = "%${request.innerPlaceholder}%"
        val resolved =
            try {
                resolving.set(true)
                delegate(player, token)
            } finally {
                resolving.remove()
            }

        if (resolved == token) return resolved
        val value = when (request.outputMode) {
            OutputMode.PRESERVE -> resolved
            OutputMode.PLAIN -> stripLegacySectionFormatting(resolved)
        }
        if (resolved.length > maxValueLength || value.length > maxValueLength) return value

        return synchronized(lock) {
            val storedAtNanos = ticker()
            observeClockLocked(storedAtNanos)
            if (generation != missGeneration) return@synchronized value

            entries[key]?.let { entry ->
                val age = storedAtNanos - entry.cachedAtNanos
                if (age >= 0L && age < ttlNanos) return@synchronized entry.value
                entries.remove(key)
            }

            entries[key] = CacheEntry(value, storedAtNanos)
            value
        }
    }

    /** Invalidates all values and prevents in-flight misses from repopulating them. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            generation++
            lastObservedNanos = null
        }
    }

    internal fun size(): Int = synchronized(lock) { entries.size }

    private fun observeClockLocked(nowNanos: Long) {
        val previous = lastObservedNanos
        if (previous != null && nowNanos < previous) {
            entries.clear()
            generation++
        }
        lastObservedNanos = nowNanos
    }

    private fun parseRequest(params: String): Request? {
        if (!params.startsWith(PREFIX, ignoreCase = true)) return null
        if (params.length > MAX_PARAMS_LENGTH) return null

        var body = params.substring(PREFIX.length)
        val outputMode =
            if (body.startsWith(PLAIN_MODE_PREFIX, ignoreCase = true)) {
                body = body.substring(PLAIN_MODE_PREFIX.length)
                OutputMode.PLAIN
            } else {
                OutputMode.PRESERVE
            }
        val ttlSeparator = body.indexOf('_')
        if (ttlSeparator <= 0) return null

        val ttlText = body.substring(0, ttlSeparator)
        if (ttlText.length > MAX_TTL_TEXT_LENGTH) return null
        if (ttlText.any { !it.isDigit() }) return null
        val ttlSeconds = ttlText.toIntOrNull() ?: return null
        if (ttlSeconds !in MIN_TTL_SECONDS..MAX_TTL_SECONDS) return null

        val inner = body.substring(ttlSeparator + 1)
        if (inner.isEmpty() || inner.length > MAX_INNER_LENGTH) return null
        if (inner.any { it == '%' || it.isWhitespace() || it.isISOControl() }) return null
        if (inner.startsWith("arc_cache_", ignoreCase = true)) return null
        if (inner.startsWith("rel_", ignoreCase = true)) return null

        return Request(ttlSeconds, inner, outputMode)
    }

    private fun stripLegacySectionFormatting(value: String): String {
        if ('§' !in value) return value

        val plain = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (
                character == '§' &&
                index + 1 < value.length &&
                value[index + 1].lowercaseChar() in LEGACY_FORMAT_CODES
            ) {
                index += 2
                continue
            }
            plain.append(character)
            index++
        }
        return plain.toString()
    }

    companion object {
        internal const val MIN_TTL_SECONDS = 1
        internal const val MAX_TTL_SECONDS = 300
        internal const val MAX_INNER_LENGTH = 160
        internal const val DEFAULT_CAPACITY = 4096
        internal const val DEFAULT_MAX_VALUE_LENGTH = 2048
        private const val PREFIX = "cache_"
        private const val PLAIN_MODE_PREFIX = "plain_"
        private const val MAX_TTL_TEXT_LENGTH = 3
        private const val MAX_PARAMS_LENGTH =
            PREFIX.length + PLAIN_MODE_PREFIX.length + MAX_TTL_TEXT_LENGTH + 1 + MAX_INNER_LENGTH
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val LEGACY_FORMAT_CODES = "0123456789abcdefklmnorx"
    }
}
