package ru.arc.sync.duels

import com.google.gson.annotations.SerializedName
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.sync.base.Context
import ru.arc.sync.base.Sync
import ru.arc.sync.base.SyncData
import ru.arc.sync.base.SyncRepo
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DuelsSync private constructor(
    private val gateway: DuelStatsGateway,
) : Sync {
    private val loaded = ConcurrentHashMap.newKeySet<UUID>()
    private val changedBeforeLoad = ConcurrentHashMap.newKeySet<UUID>()
    private val repo =
        SyncRepo(
            clazz = DuelsStatsData::class.java,
            key = "arc.duels_data",
            redisManager = checkNotNull(ARC.redisManager) { "Redis manager is not initialized" },
            dataApplier = ::applyData,
            dataProducer = ::produceData,
        )

    override fun playerJoin(uuid: UUID) {
        repo.loadAndApplyData(uuid).whenComplete { _, failure ->
            if (failure == null && Bukkit.getPlayer(uuid) != null) {
                loaded.add(uuid)
                if (changedBeforeLoad.remove(uuid)) forceSave(uuid)
            } else {
                loaded.remove(uuid)
            }
        }
    }

    override fun forceSave(uuid: UUID) {
        if (!loaded.contains(uuid)) return
        val context = Context()
        context.put("uuid", uuid)
        repo.saveAndPersistData(context)
    }

    override fun playerQuit(uuid: UUID) {
        forceSave(uuid)
        loaded.remove(uuid)
        changedBeforeLoad.remove(uuid)
    }

    fun statsChanged(uuid: UUID) {
        if (loaded.contains(uuid)) {
            forceSave(uuid)
        } else {
            changedBeforeLoad.add(uuid)
        }
    }

    override fun shutdown() {
        loaded.clear()
        changedBeforeLoad.clear()
    }

    private fun produceData(context: Context): DuelsStatsData? {
        val uuid: UUID = context.get("uuid") ?: return null
        val stats = gateway.read(uuid)
        return DuelsStatsData(
            id = uuid,
            wins = stats.int("wins"),
            losses = stats.int("losses"),
            rating = stats.int("rating", 1000),
            currentStreak = stats.int("currentStreak"),
            bestStreak = stats.int("bestStreak"),
            ownWins = stats.int("ownWins"),
            ownLosses = stats.int("ownLosses"),
            kitWins = stats.int("kitWins"),
            kitLosses = stats.int("kitLosses"),
            updatedAt = stats.long("updatedAt"),
            serverName = ARC.serverName.orEmpty(),
        )
    }

    private fun applyData(data: DuelsStatsData) {
        val uuid = data.id ?: return
        gateway.apply(uuid, data.toMap())
    }

    data class DuelsStatsData(
        @SerializedName("u") val id: UUID? = null,
        @SerializedName("w") val wins: Int = 0,
        @SerializedName("l") val losses: Int = 0,
        @SerializedName("r") val rating: Int = 1000,
        @SerializedName("cs") val currentStreak: Int = 0,
        @SerializedName("bs") val bestStreak: Int = 0,
        @SerializedName("ow") val ownWins: Int = 0,
        @SerializedName("ol") val ownLosses: Int = 0,
        @SerializedName("kw") val kitWins: Int = 0,
        @SerializedName("kl") val kitLosses: Int = 0,
        @SerializedName("t") val updatedAt: Long = 0L,
        @SerializedName("s") val serverName: String = "",
    ) : SyncData {
        override fun timestamp(): Long = updatedAt

        override fun server(): String = serverName

        override fun uuid(): UUID? = id

        fun toMap(): Map<String, Any?> =
            mapOf(
                "wins" to wins,
                "losses" to losses,
                "rating" to rating,
                "currentStreak" to currentStreak,
                "bestStreak" to bestStreak,
                "ownWins" to ownWins,
                "ownLosses" to ownLosses,
                "kitWins" to kitWins,
                "kitLosses" to kitLosses,
                "updatedAt" to updatedAt,
            )
    }

    companion object {
        fun createOrNull(): DuelsSync? {
            val plugin = Bukkit.getPluginManager().getPlugin("Duels") ?: return null
            if (!plugin.isEnabled) return null
            return try {
                DuelsSync(ReflectiveDuelStatsGateway(plugin))
            } catch (_: NoSuchMethodException) {
                Logging.info("Duels plugin does not expose the RusCrafting stats API; sync is inactive")
                null
            } catch (exception: Exception) {
                Logging.error("Failed to initialize RusCrafting Duels stats sync", exception)
                null
            }
        }
    }
}

private fun Map<String, Any?>.int(
    key: String,
    fallback: Int = 0,
): Int = (get(key) as? Number)?.toInt() ?: fallback

private fun Map<String, Any?>.long(
    key: String,
    fallback: Long = 0L,
): Long = (get(key) as? Number)?.toLong() ?: fallback
