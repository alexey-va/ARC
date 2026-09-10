package ru.arc.sync

import com.google.gson.annotations.SerializedName
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.sync.base.Context
import ru.arc.sync.base.Sync
import ru.arc.sync.base.SyncData
import ru.arc.sync.base.SyncRepo
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class EmSync : Sync {

    companion object {
        private const val PLAYER_DATA_POLL_DELAY_TICKS = 5L
        private const val PLAYER_DATA_POLL_PERIOD_TICKS = 5L
        /** 60 × 5 ticks ≈ 15 s — EliteMobs async DB load can be slow on join. */
        private const val MAX_PLAYER_DATA_WAIT_CYCLES = 60
    }

    private val repo: SyncRepo<EmDataDTO> =
        SyncRepo(
            clazz = EmDataDTO::class.java,
            key = "arc.em_data",
            redisManager = checkNotNull(ARC.redisManager) { "Redis manager is not initialized" },
            dataApplier = ::deserializeAndSavePlayerData,
            dataProducer = ::serializePlayerData,
            applySameServer = { it.lostLootCreditReceipt != null },
        )

    private val saveTails = ConcurrentHashMap<UUID, CompletableFuture<Void>>()
    private val lootCreditReceipts = ConcurrentHashMap<UUID, String>()

    private val loaded: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val joinGenerations: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val joinTasks = ConcurrentHashMap<UUID, ScheduledTask>()

    override fun playerJoin(uuid: UUID) {
        val generation = joinGenerations.merge(uuid, 1L, Long::plus) ?: 1L
        val counter = AtomicInteger(0)
        val task =
            repeating(
                PLAYER_DATA_POLL_PERIOD_TICKS.ticks,
                delay = PLAYER_DATA_POLL_DELAY_TICKS.ticks,
            ) {
                if (Bukkit.getPlayer(uuid) == null) {
                    cancelJoinTask(uuid)
                    return@repeating
                }
                val playerData = PlayerData.getPlayerData(uuid)
                if (playerData == null) {
                    if (counter.incrementAndGet() > MAX_PLAYER_DATA_WAIT_CYCLES) {
                        Logging.warn(
                            "PlayerData is null for {} after {} cycles (~{}s). Cancelling EM sync task.",
                            uuid,
                            MAX_PLAYER_DATA_WAIT_CYCLES,
                            MAX_PLAYER_DATA_WAIT_CYCLES * PLAYER_DATA_POLL_PERIOD_TICKS / 20,
                        )
                        cancelJoinTask(uuid)
                    }
                    return@repeating
                }
                repo
                    .loadAndApplyData(uuid) { isCurrentJoin(uuid, generation) }
                    .whenComplete { _, failure ->
                        if (failure == null && isCurrentJoin(uuid, generation)) {
                            loaded[uuid] = true
                        } else if (isCurrentJoin(uuid, generation)) {
                            loaded.remove(uuid)
                        }
                    }
                cancelJoinTask(uuid)
            }
        joinTasks.put(uuid, task)?.cancel()
    }

    override fun playerQuit(uuid: UUID) {
        joinGenerations.compute(uuid) { _, current -> (current ?: 0L) + 1L }
        forceSave(uuid)
        // Periodic saves retain this receipt; only the quit snapshot has captured the final
        // in-memory value before this server forgets the player.
        lootCreditReceipts.remove(uuid)
        loaded.remove(uuid)
        cancelJoinTask(uuid)
    }

    internal fun isReady(uuid: UUID): Boolean = loaded[uuid] == true && PlayerData.getPlayerData(uuid) != null
    internal fun lootCreditReceipt(uuid: UUID): String? = if (isReady(uuid)) lootCreditReceipts[uuid] else null
    internal fun markLootCredit(uuid: UUID, token: String) { check(isReady(uuid)); lootCreditReceipts[uuid] = token }

    override fun forceSave(uuid: UUID) {
        if (isReady(uuid)) {
            // The producer snapshots the receipt synchronously before the async Redis write.
            persistConfirmed(uuid)
        }
    }

    /** Snapshot currency and its delivery receipt together, behind earlier saves for this player. */
    internal fun persistConfirmed(uuid: UUID): CompletableFuture<Void> {
        if (!isReady(uuid)) return CompletableFuture.failedFuture(IllegalStateException("EliteMobs synchronization is not ready"))
        val context = Context().apply { put("uuid", uuid) }
        val next = repo.saveAndPersistData(context, saveTails[uuid])
        saveTails[uuid] = next
        next.whenComplete { _, _ -> saveTails.remove(uuid, next) }
        return next
    }

    override fun shutdown() {
        joinTasks.values.forEach(ScheduledTask::cancel)
        joinTasks.clear()
        joinGenerations.clear()
        loaded.clear()
        lootCreditReceipts.clear()
        saveTails.clear()
    }

    private fun cancelJoinTask(uuid: UUID) {
        joinTasks.remove(uuid)?.cancel()
    }

    private fun isCurrentJoin(uuid: UUID, generation: Long): Boolean =
        joinGenerations[uuid] == generation && Bukkit.getPlayer(uuid) != null

    private fun deserializeAndSavePlayerData(data: EmDataDTO) {
        val uuid = data.id ?: return
        val pd = PlayerData.getPlayerData(uuid)
        if (pd == null) {
            Logging.warn("PlayerData is not yet loaded for $uuid")
            return
        }

        val onlinePlayer = Bukkit.getPlayer(uuid)

        if (PlayerData.getCurrency(uuid) != data.currency) {
            PlayerData.setCurrency(uuid, data.currency)
        }

        if (PlayerData.getHighestLevelKilled(uuid) != data.highestLevelKilled) {
            PlayerData.setHighestLevelKilled(uuid, data.highestLevelKilled)
        }

        if (onlinePlayer != null) {
            if (PlayerData.getUseBookMenus(uuid) != data.useBookMenu) {
                PlayerData.setUseBookMenus(onlinePlayer, data.useBookMenu)
            }
            if (PlayerData.getDismissEMStatusScreenMessage(uuid) != data.dismissEmStatus) {
                PlayerData.setDismissEMStatusScreenMessage(onlinePlayer, data.dismissEmStatus)
            }
        }

        if (PlayerData.getScore(uuid) != data.score) {
            PlayerData.setDatabaseValue(uuid, "Score", data.score)
        }

        if (PlayerData.getKills(uuid) != data.kills) {
            PlayerData.setDatabaseValue(uuid, "Kills", data.kills)
        }

        if (PlayerData.getDeaths(uuid) != data.deaths) {
            PlayerData.setDatabaseValue(uuid, "Deaths", data.deaths)
        }

        if (data.skillBonusSelections != null &&
            PlayerData.getSkillBonusSelections(uuid) != data.skillBonusSelections
        ) {
            PlayerData.setSkillBonusSelections(uuid, data.skillBonusSelections)
        }

        if (PlayerData.getGamblingDebt(uuid) != data.gamblingDebt) {
            PlayerData.setGamblingDebt(uuid, data.gamblingDebt)
        }

        data.skillXP?.let { incomingXP ->
            val currentXP = PlayerData.getAllSkillXP(uuid)
            SkillType.values().forEachIndexed { i, skillType ->
                if (i < incomingXP.size && currentXP[i] != incomingXP[i]) {
                    PlayerData.setSkillXP(uuid, skillType, incomingXP[i])
                }
            }
        }
        if (data.lostLootCreditReceipt == null) lootCreditReceipts.remove(uuid)
        else lootCreditReceipts[uuid] = data.lostLootCreditReceipt
    }

    private fun serializePlayerData(context: Context): EmDataDTO? {
        Logging.debug("Serializing player data $context")
        val uuid: UUID = context.get("uuid")
        if (PlayerData.getPlayerData(uuid) == null) {
            Logging.warn("PlayerData is null for $uuid")
            return null
        }

        return EmDataDTO(
            ts = System.currentTimeMillis(),
            srv = ARC.serverName ?: "",
            id = uuid,
            currency = PlayerData.getCurrency(uuid),
            highestLevelKilled = PlayerData.getHighestLevelKilled(uuid),
            useBookMenu = PlayerData.getUseBookMenus(uuid),
            dismissEmStatus = PlayerData.getDismissEMStatusScreenMessage(uuid),
            score = PlayerData.getScore(uuid),
            kills = PlayerData.getKills(uuid),
            deaths = PlayerData.getDeaths(uuid),
            playerLevel = PlayerData.getPlayerLevel(uuid),
            skillXP = PlayerData.getAllSkillXP(uuid),
            skillBonusSelections = PlayerData.getSkillBonusSelections(uuid),
            gamblingDebt = PlayerData.getGamblingDebt(uuid),
            questsCompleted = PlayerData.getQuestsCompleted(uuid),
            lostLootCreditReceipt = lootCreditReceipts[uuid],
        )
    }

    data class EmDataDTO(
        @SerializedName("t") val ts: Long = 0L,
        @SerializedName("s") val srv: String = "",
        @SerializedName("u") val id: UUID? = null,
        // Economy
        @SerializedName("c") val currency: Double = 0.0,
        // Combat stats
        @SerializedName("h") val highestLevelKilled: Int = 0,
        @SerializedName("sc") val score: Int = 0,
        @SerializedName("k") val kills: Int = 0,
        @SerializedName("de") val deaths: Int = 0,
        // Settings
        @SerializedName("b") val useBookMenu: Boolean = false,
        @SerializedName("d") val dismissEmStatus: Boolean = false,
        // Progression (new in EliteMobs 10.x)
        @SerializedName("pl") val playerLevel: Int = 0,
        @SerializedName("sx") val skillXP: LongArray? = null,
        @SerializedName("sb") val skillBonusSelections: String? = null,
        @SerializedName("gd") val gamblingDebt: Double = 0.0,
        @SerializedName("qc") val questsCompleted: Int = 0,
        @SerializedName("lr") val lostLootCreditReceipt: String? = null,
    ) : SyncData {
        override fun timestamp(): Long = ts

        override fun server(): String = srv

        override fun uuid(): UUID? = id
    }
}
