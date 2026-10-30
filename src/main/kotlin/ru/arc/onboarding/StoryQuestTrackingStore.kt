package ru.arc.onboarding

import ru.arc.redis.RedisOperations
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Selection is separate from append-only quest progress; an empty value means explicitly untracked. */
internal class StoryQuestTrackingStore(private val redis: RedisOperations) {
    fun load(playerId: UUID): CompletableFuture<String?> =
        redis.loadMapEntries(KEY, playerId.toString()).thenApply { values ->
            require(values.size == 1)
            values.single()?.also(::validate)
        }

    fun save(playerId: UUID, questId: String?): CompletableFuture<Void> {
        val value = questId.orEmpty()
        validate(value)
        return redis.saveMapEntries(KEY, playerId.toString(), value).thenApply { null }
    }

    private fun validate(value: String) {
        require(value.isEmpty() || value == "first_home" || OptionalQuestKind.entries.any { it.id == value }) {
            "Unknown tracked story quest"
        }
    }

    companion object { const val KEY = "arc.onboarding.tracking.v1" }
}
