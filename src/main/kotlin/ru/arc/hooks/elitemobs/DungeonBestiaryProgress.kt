package ru.arc.hooks.elitemobs

import ru.arc.redis.RedisOperations
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Monotone Redis progress for the dungeon bestiary.
 *
 * The caller validates a filename against the live EliteMobs roster before
 * discovery. This store only accepts bounded canonical filenames and performs
 * no Bukkit/native registry work from Redis completion threads.
 */
internal class DungeonBestiaryProgressStore(
    private val redis: RedisOperations,
) {
    fun load(playerId: UUID): CompletableFuture<Set<String>> = redis.loadMap(key(playerId)).thenApply { entries ->
        check(entries.size <= MAX_FIELDS) { "Dungeon bestiary progress exceeded its field bound" }
        entries.mapNotNull { (bossId, value) ->
            if (!isCanonicalBossFilename(bossId)) return@mapNotNull null
            check(value == DISCOVERED) { "Dungeon bestiary contains an invalid progress value" }
            bossId
        }.toSet()
    }

    /** Returns true only when this call creates the player's first discovery. */
    fun discover(playerId: UUID, bossId: String): CompletableFuture<Boolean> {
        require(isCanonicalBossFilename(bossId)) { "Invalid dungeon bestiary boss filename" }
        val key = key(playerId)
        return redis.compareAndSetMapEntry(key, bossId, null, DISCOVERED).thenCompose { firstDiscovery ->
            if (firstDiscovery) {
                CompletableFuture.completedFuture(true)
            } else {
                // A failed CAS normally means this field is already "1". Read
                // it once so corrupt or unexpectedly absent state fails closed.
                redis.loadMapEntries(key, bossId).thenApply { values ->
                    check(values.size == 1 && values.single() == DISCOVERED) {
                        "Dungeon bestiary contains an invalid progress value"
                    }
                    false
                }
            }
        }
    }

    private fun key(playerId: UUID): String = "$HASH_PREFIX$playerId"

    private companion object {
        const val HASH_PREFIX = "arc.dungeon_bestiary."
        const val DISCOVERED = "1"
        const val MAX_FIELDS = 20_000
        const val MAX_FILENAME_BYTES = 255

        fun isCanonicalBossFilename(value: String): Boolean =
            value.endsWith(".yml") && value.length in 5..MAX_FILENAME_BYTES &&
                '/' !in value && value.none(Char::isISOControl) &&
                value.toByteArray(StandardCharsets.UTF_8).size <= MAX_FILENAME_BYTES
    }
}
