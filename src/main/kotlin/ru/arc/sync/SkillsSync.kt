package ru.arc.sync

import com.google.gson.annotations.SerializedName
import dev.aurelium.auraskills.api.AuraSkillsApi
import dev.aurelium.auraskills.api.registry.NamespacedId
import org.bukkit.Bukkit
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

class SkillsSync : Sync {

    private val syncRepo: SyncRepo<UserSkillData> = SyncRepo.builder(UserSkillData::class.java)
        .key("arc.skills_data")
        .redisManager(ARC.redisManager!!)
        .dataApplier(::applySkillData)
        .dataProducer(::getSkillData)
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
                val user = AuraSkillsApi.get().getUser(uuid)
                if (user == null) {
                    if (counter.incrementAndGet() > 20) {
                        Logging.warn("SkillsUser is null for {} for 20 cycles. Cancelling task.", uuid)
                        cancel()
                    }
                    return
                }
                syncRepo.loadAndApplyData(uuid, false)
                loaded[uuid] = true
                cancel()
            }
        }.runTaskTimer(ARC.instance, 5L, 5L)
    }

    override fun forceSave(uuid: UUID) {
        if (!loaded.containsKey(uuid)) return
        val context = Context()
        context.put("uuid", uuid)
        syncRepo.saveAndPersistData(context, false)
    }

    override fun playerQuit(uuid: UUID) {
        forceSave(uuid)
        loaded.remove(uuid)
    }

    fun getSkillData(context: Context): UserSkillData? {
        val uuid: UUID = context.get("uuid")
        val user = AuraSkillsApi.get().getUser(uuid)
        if (user == null) {
            Logging.warn("User with uuid {} not found while getting skill data", uuid)
            return null
        }

        val skills = AuraSkillsApi.get().globalRegistry.skills
        val skillInfoList = skills.map { skill ->
            SkillInfo(id = skill.id.toString(), level = user.getSkillLevel(skill), xp = user.getSkillXp(skill))
        }

        return UserSkillData(
            id = uuid,
            skills = skillInfoList,
            mana = user.mana,
            ts = System.currentTimeMillis(),
            srv = ARC.serverName ?: "",
        )
    }

    fun applySkillData(data: UserSkillData) {
        val uuid = data.id ?: return
        val user = AuraSkillsApi.get().getUser(uuid)
        if (user == null) {
            Logging.warn("User with uuid {} not found while applying skill data", uuid)
            return
        }

        data.skills.forEach { skillInfo ->
            val id = NamespacedId.fromString(skillInfo.id)
            val skill = AuraSkillsApi.get().globalRegistry.getSkill(id)
            if (skill == null) {
                Logging.warn("Skill with id {} not found", skillInfo.id)
                return@forEach
            }
            if (user.getSkillLevel(skill) != skillInfo.level) user.setSkillLevel(skill, skillInfo.level)
            if (user.getSkillXp(skill) != skillInfo.xp) user.setSkillXp(skill, skillInfo.xp)
        }

        if (user.mana != data.mana) user.mana = data.mana
    }

    data class SkillInfo(
        @SerializedName("i") val id: String = "",
        @SerializedName("l") val level: Int = 0,
        @SerializedName("x") val xp: Double = 0.0,
    )

    data class UserSkillData(
        @SerializedName("u") val id: UUID? = null,
        @SerializedName("sk") val skills: List<SkillInfo> = emptyList(),
        @SerializedName("m") val mana: Double = 0.0,
        @SerializedName("t") val ts: Long = 0L,
        @SerializedName("s") val srv: String = "",
    ) : SyncData {
        override fun timestamp(): Long = ts
        override fun server(): String = srv
        override fun uuid(): UUID? = id
    }
}
