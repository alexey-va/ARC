package ru.arc.audit

import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/** Short-lived correlation between an accepted admin command and RedisEconomy's later Vault event. */
object AdminEconomyCommandTracker {
    enum class Kind { DELTA, SET }

    data class Pending(
        val kind: Kind,
        val amount: Double,
        val actor: String,
        val action: String,
        val source: EconomySource,
        val origin: String,
        val correlationId: String,
        val expiresAt: Long,
    )

    private val pending = ConcurrentHashMap<String, ConcurrentLinkedDeque<Pending>>()
    private val trackedCommands = AtomicLong()

    fun track(
        args: List<String>,
        actor: String,
        now: Long = System.currentTimeMillis(),
        source: EconomySource = EconomySource.ADMIN_COMMAND,
        origin: String = actor,
    ): Boolean {
        val parsed = parse(args, actor, source, origin, now) ?: return false
        val queue = pending.computeIfAbsent(parsed.first) { ConcurrentLinkedDeque() }
        queue.addLast(parsed.second)
        while (queue.size > MAX_PENDING_PER_PLAYER) queue.pollFirst()
        if (trackedCommands.incrementAndGet() % CLEANUP_INTERVAL == 0L) cleanupExpired(now)
        return true
    }

    fun consumeDelta(player: String, amount: Double, now: Long = System.currentTimeMillis()): Pending? =
        consume(player, Kind.DELTA, amount, now)

    fun consumeSet(player: String, absoluteBalance: Double, now: Long = System.currentTimeMillis()): Pending? =
        consume(player, Kind.SET, absoluteBalance, now)

    internal fun clear() = pending.clear()

    private fun parse(
        args: List<String>,
        actor: String,
        source: EconomySource,
        origin: String,
        now: Long,
    ): Pair<String, Pending>? {
        val command = args.firstOrNull()?.removePrefix("/")?.lowercase(Locale.ROOT) ?: return null
        val parsed =
            when {
                command == "money" && args.size == 5 && args[2].equals("vault", ignoreCase = true) ->
                    Triple(args[1], args[3], args[4])
                command == "cmi" && args.size >= 5 && args[1].equals("money", ignoreCase = true) ->
                    Triple(args[3], args[2], args[4])
                else -> return null
            }
        val rawAmount = parsed.third.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        val action = parsed.second.lowercase(Locale.ROOT)
        val kind = if (action == "set") Kind.SET else Kind.DELTA
        val signedAmount =
            when (action) {
                "give", "set" -> rawAmount
                "take" -> -rawAmount
                else -> return null
            }
        return parsed.first.lowercase(Locale.ROOT) to
            Pending(
                kind = kind,
                amount = signedAmount,
                actor = actor.take(80),
                action = action,
                source = source,
                origin = origin.take(240),
                correlationId = UUID.randomUUID().toString(),
                expiresAt = now + TTL_MILLIS,
            )
    }

    private fun consume(player: String, kind: Kind, amount: Double, now: Long): Pending? {
        val key = player.lowercase(Locale.ROOT)
        val queue = pending[key] ?: return null
        queue.removeIf { it.expiresAt < now }
        val match = queue.firstOrNull { it.kind == kind && approximatelyEqual(it.amount, amount) }
        if (match != null) queue.remove(match)
        if (queue.isEmpty()) pending.remove(key, queue)
        return match
    }

    private fun approximatelyEqual(expected: Double, actual: Double): Boolean =
        abs(expected - actual) <= max(0.000_001, max(abs(expected), abs(actual)) * 1e-9)

    private fun cleanupExpired(now: Long) {
        pending.forEach { (key, queue) ->
            queue.removeIf { it.expiresAt < now }
            if (queue.isEmpty()) pending.remove(key, queue)
        }
    }

    private const val TTL_MILLIS = 5_000L
    private const val CLEANUP_INTERVAL = 256L
    private const val MAX_PENDING_PER_PLAYER = 16
}

internal data class EconomyCommandOrigin(
    val source: EconomySource,
    val origin: String,
)

/**
 * Preserves one bounded gameplay source when a Denizen queue dispatches a
 * console economy command through CMI. RedisEconomy records only CMI's class in
 * that path, so the upstream source is available only while the command event
 * is still on the synchronous stack.
 */
internal object EconomyCommandOriginResolver {
    fun resolve(stackTrace: Array<StackTraceElement> = Thread.currentThread().stackTrace): EconomyCommandOrigin {
        val gameplayCaller =
            GAMEPLAY_PACKAGES.firstNotNullOfOrNull { (packageName, source) ->
                stackTrace.firstOrNull { it.className.startsWith(packageName) }
                    ?.let { EconomyCommandOrigin(source, it.className.take(240)) }
            }
        return gameplayCaller ?: EconomyCommandOrigin(EconomySource.ADMIN_COMMAND, "Server")
    }

    private val GAMEPLAY_PACKAGES =
        listOf(
            "com.denizenscript." to EconomySource.DENIZEN,
            "com.leonardobishop.quests." to EconomySource.QUESTS,
        )
}
