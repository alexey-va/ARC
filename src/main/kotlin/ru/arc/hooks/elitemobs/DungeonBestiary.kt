package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

internal data class DungeonBestiaryProgress(val discovered: Int?, val total: Int)

/** Native events are captured on Paper's thread; only immutable discovery keys cross into Redis. */
internal class DungeonBestiary(
    private val dungeon: EMDungeonQol,
    private val progressStore: () -> DungeonBestiaryProgressStore? = { ARC.redisManager?.let(::DungeonBestiaryProgressStore) },
    internal val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val catalogEntries: (String) -> List<BestiaryMob> = { NativeDungeonBestiaryCatalog.entries(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class ProgressCache {
        var durableIds: Set<String>? = null
        var load: CompletableFuture<Set<String>>? = null
        var retryAfterMillis = 0L
        var lastFailureLogMillis: Long? = null
    }

    private val known = mutableMapOf<UUID, MutableSet<String>>()
    private val progressCache = mutableMapOf<UUID, ProgressCache>()
    private val pending = mutableMapOf<Pair<UUID, String>, CompletableFuture<Boolean>>()
    @Volatile private var closed = false

    fun entries(contentId: String): List<BestiaryMob> = catalogEntries(contentId)

    /** Read-only panel projection; the first result stays unknown until a complete Redis read arrives. */
    fun progress(playerId: UUID, contentId: String): DungeonBestiaryProgress {
        val roster = entries(contentId).distinctBy(BestiaryMob::id)
        if (closed) return DungeonBestiaryProgress(null, roster.size)
        val cached = progressCache[playerId]?.durableIds
        if (cached == null) preload(playerId)
        val durable = progressCache[playerId]?.durableIds ?: return DungeonBestiaryProgress(null, roster.size)
        val unlocked = durable + known[playerId].orEmpty()
        return DungeonBestiaryProgress(roster.count { it.id in unlocked }, roster.size)
    }

    /** Warm a player's durable snapshot without blocking or retrying repeatedly after a failure. */
    fun preload(playerId: UUID) {
        if (closed || progressStore() == null) return
        val state = progressCache.getOrPut(playerId) { ProgressCache() }
        if (state.durableIds != null || state.load != null || nowMillis() < state.retryAfterMillis) return
        startLoad(playerId, state)
    }

    /** Full read for the bestiary menu; concurrent opens share one read and pending writes are awaited. */
    fun loadProgress(playerId: UUID): CompletableFuture<Set<String>> {
        if (closed) return CompletableFuture.failedFuture<Set<String>>(CancellationException("Dungeon bestiary stopped"))
        val state = progressCache.getOrPut(playerId) { ProgressCache() }
        // A deliberate menu retry bypasses automatic preload backoff, while concurrent reads still coalesce.
        return startLoad(playerId, state)
    }

    private fun startLoad(playerId: UUID, state: ProgressCache): CompletableFuture<Set<String>> {
        state.load?.let { return it }
        val store = progressStore()
        val result = CompletableFuture<Set<String>>()
        state.load = result
        val token = try {
            tasks.token()
        } catch (failure: RuntimeException) {
            state.load = null
            result.completeExceptionally(failure)
            return result
        }

        pending.entries.removeIf { it.value.isDone }
        val writes = pending.filterKeys { it.first == playerId }.values.toTypedArray()
        val read: CompletableFuture<Set<String>> = try {
            CompletableFuture.allOf(*writes).thenCompose {
                if (result.isDone || closed) CompletableFuture.failedFuture<Set<String>>(CancellationException("Stale dungeon bestiary read"))
                else store?.load(playerId) ?: CompletableFuture.failedFuture(IllegalStateException("Dungeon bestiary storage is unavailable"))
            }
        } catch (failure: Exception) {
            CompletableFuture.failedFuture(failure)
        }
        read.whenComplete { stored, failure ->
            if (result.isDone) return@whenComplete
            val completion: () -> Unit = {
                if (closed || progressCache[playerId] !== state || state.load !== result) {
                    result.completeExceptionally(CancellationException("Stale dungeon bestiary read"))
                } else {
                    state.load = null
                    if (failure != null || stored == null) {
                        val cause = failure ?: IllegalStateException("Bestiary store returned no progress")
                        val now = nowMillis()
                        state.retryAfterMillis = now + LOAD_RETRY_DELAY_MILLIS
                        if (state.lastFailureLogMillis == null || now - requireNotNull(state.lastFailureLogMillis) >= LOAD_FAILURE_LOG_INTERVAL_MILLIS) {
                            Logging.warn("Dungeon bestiary load failed: player={}", playerId, cause)
                            state.lastFailureLogMillis = now
                        }
                        result.completeExceptionally(cause)
                    } else {
                        val unlocked = stored + known[playerId].orEmpty()
                        state.durableIds = unlocked
                        state.retryAfterMillis = 0L
                        result.complete(unlocked)
                    }
                }
            }
            if (Bukkit.isPrimaryThread()) {
                completion()
            } else try {
                if (tasks.runSync(token, completion) == null && !result.isDone) {
                    result.completeExceptionally(CancellationException("Dungeon bestiary stopped"))
                }
            } catch (schedulingFailure: RuntimeException) {
                result.completeExceptionally(schedulingFailure)
            }
        }
        return result
    }

    fun defeated(boss: CustomBossEntity, visit: DungeonVisit) {
        if (closed || boss.isTriggeredAntiExploit) return
        val contentId = visit.contentId ?: return
        val id = NativeDungeonBestiaryCatalog.discoveryId(boss.customBossesConfigFields.filename)
        val entry = NativeDungeonBestiaryCatalog.entries(contentId, refresh = false).firstOrNull { it.id == id } ?: return
        val world = boss.location?.world ?: return
        for ((player, damage) in boss.damagers.toMap()) {
            if (player.hasMetadata("NPC")) continue
            if (!bestiaryKillCredit(damage, player.isOnline,
                    player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE,
                    player.world.uid == world.uid, visit.members?.contains(player.uniqueId) != false)) continue
            discover(player, entry)
        }
    }

    internal fun discover(player: Player, entry: BestiaryMob) {
        if (closed) return
        val store = progressStore() ?: return
        val playerId = player.uniqueId
        val state = progressCache.getOrPut(playerId) { ProgressCache() }
        val key = playerId to entry.id
        if (pending[key]?.isDone == true) pending.remove(key)
        if (entry.id in known[playerId].orEmpty() || entry.id in state.durableIds.orEmpty() || key in pending) return
        val result = CompletableFuture<Boolean>()
        pending[key] = result
        fun attempt(number: Int) {
            if (closed) return
            val write = try { store.discover(playerId, entry.id) }
                catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
            write.whenComplete { fresh, failure ->
                // Report even if shutdown has fenced off the gameplay continuation.
                if (failure != null && (number == 1 || number == 3)) {
                    Logging.warn("Dungeon bestiary discovery failed: player={} mob={} attempt={}/3", playerId, entry.id, number, failure)
                }
                try { tasks.runSync {
                    if (failure != null && number < 3) {
                        try {
                            if (tasks.runLater(if (number == 1) 40 else 200) { attempt(number + 1) } != null) return@runSync
                        } catch (schedulingFailure: RuntimeException) {
                            Logging.warn("Dungeon bestiary retry scheduling failed: player={} mob={}", playerId, entry.id, schedulingFailure)
                        }
                    }
                    pending.remove(key)
                    val currentSession = progressCache[playerId] === state
                    if (failure != null) {
                        result.completeExceptionally(failure)
                        if (currentSession && player.isOnline) player.sendMessage(dungeon.text("bestiary.save-failed",
                            "<#e8dfd2>Не удалось сохранить запись бестиария. Следующая победа повторит сохранение."))
                    } else {
                        if (currentSession && player.isOnline) {
                            known.getOrPut(playerId) { mutableSetOf() }.add(entry.id)
                            state.durableIds = state.durableIds?.plus(entry.id)
                        }
                        result.complete(fresh)
                        if (fresh && currentSession && player.isOnline) player.sendMessage(dungeon.text("bestiary.discovered",
                            "<#c4abff>Бестиарий: <white><name></white><newline><#e8dfd2>Запись открыта. Способности и добыча — в меню данжа.",
                            "name" to Component.text(plainDungeonQuestText(entry.name))))
                    }
                } } catch (schedulingFailure: RuntimeException) {
                    result.completeExceptionally(schedulingFailure)
                    Logging.warn("Dungeon bestiary completion scheduling failed: player={} mob={}", playerId, entry.id, schedulingFailure)
                }
            }
        }
        attempt(1)
    }

    fun forget(playerId: UUID) {
        known.remove(playerId)
        progressCache.remove(playerId)?.load?.completeExceptionally(CancellationException("Player left"))
    }

    override fun close() {
        closed = true
        tasks.close()
        progressCache.values.mapNotNull(ProgressCache::load).forEach {
            it.completeExceptionally(CancellationException("Dungeon bestiary stopped"))
        }
        progressCache.clear()
        known.clear()
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Dungeon bestiary stopped")) }
        pending.clear()
    }

    private companion object {
        const val LOAD_RETRY_DELAY_MILLIS = 30_000L
        const val LOAD_FAILURE_LOG_INTERVAL_MILLIS = 60_000L
    }
}

/** Participating in the actual fight counts; proximity, party membership and a spectator do not. */
internal fun bestiaryKillCredit(damage: Double, online: Boolean, survival: Boolean, sameWorld: Boolean, member: Boolean): Boolean =
    damage.isFinite() && damage > 0 && online && survival && sameWorld && member
