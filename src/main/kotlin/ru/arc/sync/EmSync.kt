package ru.arc.sync

import com.google.gson.annotations.SerializedName
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import ru.arc.ARC
import ru.arc.sync.base.Context
import ru.arc.sync.base.Sync
import ru.arc.sync.base.SyncData
import ru.arc.sync.base.SyncRepo
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class EmSync : Sync {
    private val repo: SyncRepo<EmDataDTO> =
        SyncRepo
            .builder(EmDataDTO::class.java)
            .key("arc.em_data")
            .redisManager(ARC.redisManager!!)
            .dataApplier(::deserializeAndSavePlayerData)
            .dataProducer(::serializePlayerData)
            .build()

    private val loaded: MutableMap<UUID, Boolean> = ConcurrentHashMap()

    override fun playerJoin(uuid: UUID) {
        val counter = AtomicInteger(0)
        object : BukkitRunnable() {
            override fun run() {
                if (Bukkit.getPlayer(uuid) == null) {
                    cancel()
                    return
                }
                val pd = PlayerData.getPlayerData(uuid)
                if (pd == null) {
                    if (counter.incrementAndGet() > 20) {
                        Logging.warn("PlayerData is null for {} for 20 cycles. Cancelling task.", uuid)
                        cancel()
                    }
                    return
                }
                repo.loadAndApplyData(uuid, false)
                loaded[uuid] = true
                cancel()
            }
        }.runTaskTimer(ARC.instance, 5L, 5L)
    }

    override fun playerQuit(uuid: UUID) {
        forceSave(uuid)
        loaded.remove(uuid)
    }

    override fun forceSave(uuid: UUID) {
        if (!loaded.containsKey(uuid)) return
        val context = Context()
        context.put("uuid", uuid)
        repo.saveAndPersistData(context, false)
    }

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
    ) : SyncData {
        override fun timestamp(): Long = ts

        override fun server(): String = srv

        override fun uuid(): UUID? = id
    }
}
