@file:Suppress("DEPRECATION")

package ru.arc.sync

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Modules.PlayerOptions.PlayerOption
import com.google.gson.annotations.SerializedName
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.sync.base.Context
import ru.arc.sync.base.Sync
import ru.arc.sync.base.SyncData
import ru.arc.sync.base.SyncRepo
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CMISync : Sync {
    private val key = "arc.cmi_data"
    private val repo: SyncRepo<CMIDataDTO> =
        SyncRepo
            .builder(CMIDataDTO::class.java)
            .key(key)
            .redisManager(ARC.redisManager!!)
            .dataApplier(::applyData)
            .dataProducer(::produceData)
            .build()

    private val loaded: MutableMap<UUID, Boolean> = ConcurrentHashMap()

    override fun playerJoin(uuid: UUID) {
        repo.loadAndApplyData(uuid, false)
        loaded[uuid] = true
    }

    override fun forceSave(uuid: UUID) {
        if (!loaded.containsKey(uuid)) return
        val context = Context()
        context.put("uuid", uuid)
        repo.saveAndPersistData(context, false)
    }

    override fun playerQuit(uuid: UUID) {
        forceSave(uuid)
        loaded.remove(uuid)
    }

    @Suppress("DEPRECATION")
    fun applyData(data: CMIDataDTO) {
        if (data.server() == ARC.serverName) return

        val uuid = data.id ?: return
        val user = CMI.getInstance().playerManager.getUser(uuid)
        if (user == null) {
            Logging.warn("User is null for {}", uuid)
            return
        }

        if (user.prefix != data.prefix) user.namePlatePrefix = data.prefix
        if (user.suffix != data.suffix) user.namePlateSuffix = data.suffix
        if (user.nickName != data.nick) user.setNickName(data.nick, true)

        val onlinePlayer = Bukkit.getPlayer(uuid)
        if (onlinePlayer != null && user.isGod != data.god) {
            CMI.getInstance().nms.changeGodMode(onlinePlayer, data.god)
        }

        if (user.getOptionState(PlayerOption.acceptingMoney) != data.noPay) {
            user.setOptionState(PlayerOption.acceptingMoney, data.noPay)
        }
        if (user.getOptionState(PlayerOption.shiftSignEdit) != data.shift) {
            user.setOptionState(PlayerOption.shiftSignEdit, data.shift)
        }
        if (user.getOptionState(PlayerOption.totemBossBar) != data.totem) {
            user.setOptionState(PlayerOption.totemBossBar, data.totem)
        }
        if (user.getOptionState(PlayerOption.acceptingPM) != data.pm) {
            user.setOptionState(PlayerOption.acceptingPM, data.pm)
        }

        val glowChar = user.glow?.char
        if (glowChar != data.glowColor) {
            if (data.glowColor != null) {
                user.setGlow(ChatColor.getByChar(data.glowColor), true)
            } else {
                user.setGlow(null, true)
            }
        }

        if (user.isCMIVanished != data.vanish) {
            user.isVanished = data.vanish
            user.updateVanishMode()
        }

        data.kitUsage?.forEach { (kitName, usage) ->
            val kit = CMI.getInstance().kitsManager.getKit(user.player, kitName) ?: return@forEach
            if (user.kits.containsKey(kit)) {
                user.kits[kit]?.usedTimes = usage.uses
                user.kits[kit]?.lastUsage = usage.lastUse
            } else {
                user.addKit(kit, usage.lastUse, usage.uses, true)
            }
        }

        if (data.rank != null && user.rank?.name != data.rank) {
            val rank = CMI.getInstance().rankManager.getRank(data.rank)
            if (rank != null) user.rank = rank
        }

        user.clearMails()
        data.mail?.forEachIndexed { i, mail ->
            val playerMail =
                com.Zrips.CMI.Containers
                    .PlayerMail(mail.sender, mail.time, mail.message)
            user.addMail(playerMail, i == (data.mail.size - 1))
        }
    }

    fun produceData(context: Context): CMIDataDTO? {
        val uuid: UUID =
            context.get("uuid") ?: run {
                Logging.error("Could not extract uuid for cmi sync: {}", context)
                return null
            }

        val user =
            CMI.getInstance().playerManager.getUser(uuid) ?: run {
                Logging.error("Could not find user for cmi sync: {}", uuid)
                return null
            }

        val kitUsageMap =
            buildMap {
                user.kits.forEach { (kit, usage) ->
                    if (kit == null) {
                        Logging.warn("Kit is null for user {}", user.name)
                        return@forEach
                    }
                    put(kit.configName, CMIDataDTO.KitUsage(uses = usage.usedTimes, lastUse = usage.lastUsage))
                }
            }

        return CMIDataDTO(
            ts = System.currentTimeMillis(),
            srv = ARC.serverName ?: "",
            id = uuid,
            kitUsage = kitUsageMap,
            prefix = user.prefix,
            suffix = user.suffix,
            rank = user.rank?.name,
            nick = user.nickName,
            god = user.isGod,
            glowColor = user.glow?.char,
            noPay = user.getOptionState(PlayerOption.acceptingMoney),
            shift = user.getOptionState(PlayerOption.shiftSignEdit),
            totem = user.getOptionState(PlayerOption.totemBossBar),
            pm = user.getOptionState(PlayerOption.acceptingPM),
            vanish = user.isCMIVanished,
            mail =
                user.mails?.map { CMIDataDTO.Mail(sender = it.sender, time = it.time, message = it.message) }
                    ?: emptyList(),
        )
    }

    data class CMIDataDTO(
        @SerializedName("ts") val ts: Long = 0L,
        @SerializedName("s") val srv: String = "",
        @SerializedName("u") val id: UUID? = null,
        @SerializedName("p") val prefix: String? = null,
        @SerializedName("sf") val suffix: String? = null,
        @SerializedName("r") val rank: String? = null,
        @SerializedName("n") val nick: String? = null,
        @SerializedName("g") val god: Boolean = false,
        @SerializedName("gc") val glowColor: Char? = null,
        @SerializedName("np") val noPay: Boolean = false,
        @SerializedName("sh") val shift: Boolean = false,
        @SerializedName("t") val totem: Boolean = false,
        @SerializedName("pm") val pm: Boolean = false,
        @SerializedName("v") val vanish: Boolean = false,
        @SerializedName("m") val mail: List<Mail>? = null,
        @SerializedName("ku") val kitUsage: Map<String, KitUsage>? = null,
    ) : SyncData {
        override fun timestamp(): Long = ts

        override fun server(): String = srv

        override fun uuid(): UUID? = id

        data class Mail(
            @SerializedName("s") val sender: String = "",
            @SerializedName("t") val time: Long = 0L,
            @SerializedName("m") val message: String = "",
        )

        data class KitUsage(
            @SerializedName("ku") val uses: Int = 0,
            @SerializedName("kl") val lastUse: Long = 0L,
        )
    }
}
