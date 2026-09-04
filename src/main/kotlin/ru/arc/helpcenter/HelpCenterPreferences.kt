package ru.arc.helpcenter

import com.google.gson.Gson
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdater
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class HelpCenterPreferences(
    val favorites: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
) {
    fun toggleFavorite(id: String): HelpCenterPreferences {
        validateId(id)
        val updated = if (id in favorites) favorites - id else (favorites + id).takeLast(MAX_FAVORITES)
        return copy(favorites = updated).validated()
    }

    fun recordRecent(id: String): HelpCenterPreferences {
        validateId(id)
        return copy(recent = (listOf(id) + recent.filterNot(id::equals)).take(MAX_RECENT)).validated()
    }

    fun validated(): HelpCenterPreferences = apply {
        require(favorites.size <= MAX_FAVORITES) { "Too many help center favorites" }
        require(recent.size <= MAX_RECENT) { "Too many help center recent actions" }
        require(favorites.distinct().size == favorites.size) { "Duplicate help center favorites" }
        require(recent.distinct().size == recent.size) { "Duplicate help center recent actions" }
        (favorites + recent).forEach(::validateId)
    }

    companion object {
        const val MAX_FAVORITES = 4
        const val MAX_RECENT = 6
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,47}")

        fun empty(): HelpCenterPreferences = HelpCenterPreferences()

        private fun validateId(id: String) = require(ID.matches(id)) { "Unsafe help center action id" }
    }
}

interface HelpCenterPreferenceStore : AutoCloseable {
    fun load(playerId: UUID): CompletableFuture<HelpCenterPreferences>

    fun toggleFavorite(playerId: UUID, actionId: String): CompletableFuture<HelpCenterPreferences>

    fun recordRecent(playerId: UUID, actionId: String): CompletableFuture<HelpCenterPreferences>

    override fun close() = Unit
}

class UnavailableHelpCenterPreferenceStore : HelpCenterPreferenceStore {
    override fun load(playerId: UUID): CompletableFuture<HelpCenterPreferences> =
        CompletableFuture.failedFuture(IllegalStateException("Personalization storage is unavailable"))

    override fun toggleFavorite(playerId: UUID, actionId: String): CompletableFuture<HelpCenterPreferences> = load(playerId)

    override fun recordRecent(playerId: UUID, actionId: String): CompletableFuture<HelpCenterPreferences> = load(playerId)
}

class RedisHelpCenterPreferenceStore(private val redis: RedisOperations, gson: Gson) : HelpCenterPreferenceStore {
    private val codec = BoundedJsonCodec(
        gson,
        HelpCenterPreferences::class.java,
        JsonObjectContract(
            allowedFields = setOf("favorites", "recent"),
            fieldContracts = mapOf(
                "favorites" to JsonArrayContract(maxEntries = HelpCenterPreferences.MAX_FAVORITES),
                "recent" to JsonArrayContract(maxEntries = HelpCenterPreferences.MAX_RECENT),
            ),
        ),
        JsonResourceBounds(maxCharacters = 2_048, maxDepth = 3, maxContainerEntries = 8, maxTotalNodes = 16, maxStringCharacters = 48),
        HelpCenterPreferences::validated,
    )
    private val updater = RedisHashUpdater(redis, STORAGE_KEY, codec)

    override fun load(playerId: UUID): CompletableFuture<HelpCenterPreferences> =
        redis.loadMapEntries(STORAGE_KEY, playerId.toString()).thenApply { values ->
            require(values.size == 1) { "Unexpected personalization lookup result" }
            values.single()?.let(codec::decode) ?: HelpCenterPreferences.empty()
        }

    override fun toggleFavorite(playerId: UUID, actionId: String): CompletableFuture<HelpCenterPreferences> =
        update(playerId) { it.toggleFavorite(actionId) }

    override fun recordRecent(playerId: UUID, actionId: String): CompletableFuture<HelpCenterPreferences> =
        update(playerId) { it.recordRecent(actionId) }

    private fun update(
        playerId: UUID,
        transform: (HelpCenterPreferences) -> HelpCenterPreferences,
    ): CompletableFuture<HelpCenterPreferences> = updater.update(playerId.toString()) { current ->
        RedisHashDecision.Write(transform(current ?: HelpCenterPreferences.empty()))
    }.thenApply { result ->
        when (result) {
            is ru.arc.redis.safety.RedisHashUpdateResult.Changed -> requireNotNull(result.after)
            is ru.arc.redis.safety.RedisHashUpdateResult.Unchanged -> result.current
            is ru.arc.redis.safety.RedisHashUpdateResult.Rejected -> error("Personalization update was rejected")
            is ru.arc.redis.safety.RedisHashUpdateResult.Contended -> error("Personalization update was contended")
        }
    }

    private companion object {
        const val STORAGE_KEY = "arc.help-center.preferences.v1"
    }
}
