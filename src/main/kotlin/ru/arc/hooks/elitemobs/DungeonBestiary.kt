package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Native events are captured on Paper's thread; only immutable discovery keys cross into Redis. */
internal class DungeonBestiary(
    private val dungeon: EMDungeonQol,
    private val store: DungeonBestiaryProgressStore = DungeonBestiaryProgressStore(
        checkNotNull(ARC.redisManager) { "Redis is unavailable for the dungeon bestiary" },
    ),
    internal val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : AutoCloseable {
    private val known = mutableMapOf<UUID, MutableSet<String>>()
    private val pending = mutableMapOf<Pair<UUID, String>, CompletableFuture<Boolean>>()
    @Volatile private var closed = false

    fun entries(contentId: String): List<BestiaryMob> = NativeDungeonBestiaryCatalog.entries(contentId)

    fun loadProgress(playerId: UUID): CompletableFuture<Set<String>> {
        pending.entries.removeIf { it.value.isDone }
        val writes = pending.filterKeys { it.first == playerId }.values.toTypedArray()
        return CompletableFuture.allOf(*writes).thenCompose { store.load(playerId) }.whenComplete { _, failure ->
            if (failure != null && !closed) Logging.warn("Dungeon bestiary load failed: player={}", playerId, failure)
        }
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
        val playerId = player.uniqueId
        val key = playerId to entry.id
        if (pending[key]?.isDone == true) pending.remove(key)
        if (entry.id in known[playerId].orEmpty() || key in pending) return
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
                    if (failure != null) {
                        result.completeExceptionally(failure)
                        if (player.isOnline) player.sendMessage(dungeon.text("bestiary.save-failed",
                            "<#e8dfd2>Не удалось сохранить запись бестиария. Следующая победа повторит сохранение."))
                    } else {
                        if (player.isOnline) known.getOrPut(playerId) { mutableSetOf() }.add(entry.id)
                        result.complete(fresh)
                        if (fresh && player.isOnline) player.sendMessage(dungeon.text("bestiary.discovered",
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

    fun forget(playerId: UUID) { known.remove(playerId) }

    override fun close() {
        closed = true
        tasks.close()
        known.clear()
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Dungeon bestiary stopped")) }
        pending.clear()
    }
}

/** Participating in the actual fight counts; proximity, party membership and a spectator do not. */
internal fun bestiaryKillCredit(damage: Double, online: Boolean, survival: Boolean, sameWorld: Boolean, member: Boolean): Boolean =
    damage.isFinite() && damage > 0 && online && survival && sameWorld && member
