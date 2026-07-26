@file:Suppress("DEPRECATION")

package ru.arc.sync

import com.google.gson.annotations.SerializedName
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile
import io.github.thebusybiscuit.slimefun4.api.researches.Research
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.core.ScheduledTask
import ru.arc.core.delayed
import ru.arc.core.ticks
import ru.arc.sync.base.Context
import ru.arc.sync.base.Sync
import ru.arc.sync.base.SyncData
import ru.arc.sync.base.SyncRepo
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class SlimefunSync : Sync {

    private val syncRepo: SyncRepo<SlimefunDataDTO> =
        SyncRepo(
            clazz = SlimefunDataDTO::class.java,
            key = "arc.slimefun_data",
            redisManager = checkNotNull(ARC.redisManager) { "Redis manager is not initialized" },
            dataApplier = ::deserializeAndSavePlayerData,
            dataProducer = ::serializePlayerData,
        )

    private val loaded: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val joinTasks = ConcurrentHashMap<UUID, ScheduledTask>()

    override fun playerJoin(uuid: UUID) {
        val task =
            delayed(20.ticks) {
                joinTasks.remove(uuid)
                syncRepo
                    .loadAndApplyData(uuid)
                    .whenComplete { _, failure ->
                        if (failure == null && Bukkit.getPlayer(uuid) != null) {
                            loaded[uuid] = true
                        } else {
                            loaded.remove(uuid)
                        }
                    }
            }
        joinTasks.put(uuid, task)?.cancel()
    }

    override fun playerQuit(uuid: UUID) {
        forceSave(uuid)
        loaded.remove(uuid)
        joinTasks.remove(uuid)?.cancel()
    }

    override fun forceSave(uuid: UUID) {
        if (!loaded.containsKey(uuid)) return
        val context = Context()
        context.put("uuid", uuid)
        if (loaded.getOrDefault(uuid, false)) syncRepo.saveAndPersistData(context)
        else warn("Player data not loaded for {}. Skipping save", uuid)
    }

    override fun shutdown() {
        joinTasks.values.forEach(ScheduledTask::cancel)
        joinTasks.clear()
        loaded.clear()
    }

    private fun serializePlayerData(context: Context): SlimefunDataDTO? {
        val uuid: UUID =
            context.get("uuid") ?: run {
                error("Could not extract uuid for slimefun sync: {}", context)
                return null
            }

        val future = CompletableFuture<SlimefunDataDTO>()
        PlayerProfile.fromUUID(uuid) { pp ->
            future.complete(
                SlimefunDataDTO(
                    uuid = uuid,
                    timestamp = System.currentTimeMillis(),
                    server = ARC.serverName ?: "",
                    researches = pp.researches.map(Research::getID),
                ),
            )
        }
        return future.join()
    }

    private fun deserializeAndSavePlayerData(dto: SlimefunDataDTO) {
        val dtoUuid = dto.uuid ?: return
        PlayerProfile.fromUUID(dtoUuid) { pp ->
            if (pp == null) return@fromUUID
            val researchIds = dto.researches.toHashSet()
            Slimefun.getRegistry().researches
                .filter { it.getID() in researchIds && !pp.hasUnlocked(it) }
                .forEach { pp.setResearched(it, true) }
        }
    }

    data class SlimefunDataDTO(
        @SerializedName("u") val uuid: UUID? = null,
        @SerializedName("ts") val timestamp: Long = 0L,
        @SerializedName("s") val server: String = "",
        @SerializedName("r") val researches: List<Int> = emptyList(),
    ) : SyncData {
        override fun timestamp(): Long = timestamp

        override fun server(): String = server

        override fun uuid(): UUID? = uuid

        override fun trash(): Boolean = researches.isEmpty()
    }
}
