package ru.arc.pvp

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.dungeons.api.ArcPvpService
import ru.arc.dungeons.api.ArcPvpService.Result
import ru.arc.logging.ArcLogging
import ru.arc.util.TextUtil

/** One ARC lifecycle owns combat policy; the host service delegates to the current generation. */
object PvpModule : PluginModule, ArcPvpService {
    override val name = "Pvp"
    override val priority = 84
    private var runtime: PvpPolicy? = null

    fun prepareLandsFlag(plugin: Plugin) {
        if (Bukkit.getPluginManager().getPlugin("Lands") == null) return
        try {
            LandsPvpFlag.install(plugin)
        } catch (failure: RuntimeException) {
            ArcLogging.error("ARC PvP flag needs a full Paper restart for its first registration", failure)
        } catch (failure: LinkageError) {
            ArcLogging.error("ARC PvP flag could not link to the installed Lands API", failure)
        }
    }

    override fun init() {
        val manager = Bukkit.getPluginManager()
        val settlement: (Location) -> Boolean = if (manager.isPluginEnabled("Lands")) LandsPvpFlag::prohibits else { _ -> false }
        val native = manager.getPlugin("justTeams")?.takeIf { it.isEnabled }
        val teamFailure = runCatching { native?.let { JustTeamsPvpBridge(it as JavaPlugin) } }
        val team: (Player, Player, Entity) -> Result = { attacker, victim, source ->
            if (native?.isEnabled != true) Result.ALLOWED
            else teamFailure.getOrElse { throw IllegalStateException("The native justTeams cached PvP API is incompatible", it) }
                ?.check(attacker, victim, source) ?: Result.ALLOWED
        }
        val messages = try {
            val config = ConfigManager.ofModule(ARC.instance.dataPath, "pvp.yml")
            config.mergeMissingFromBundled("modules/pvp.yml")
            DEFAULT_MESSAGES.mapValues { (reason, fallback) ->
                TextUtil.mm(config.string("messages.${reason.name.lowercase()}", fallback))
            }
        } catch (failure: Exception) {
            if (runtime != null) throw failure
            ArcLogging.error("PvP message configuration rejected; protection uses bundled messages. Repair modules/pvp.yml and reload ARC.", failure)
            DEFAULT_MESSAGES.mapValues { TextUtil.mm(it.value) }
        }
        val policy = PvpPolicy(settlement, team, { messages.getValue(it) })
        try {
            manager.registerEvents(policy, ARC.instance)
        } catch (failure: RuntimeException) {
            HandlerList.unregisterAll(policy)
            policy.close()
            throw failure
        }
        val previous = runtime
        runtime = policy
        previous?.let { HandlerList.unregisterAll(it); it.close() }
        ArcLogging.info("PvP policy ready: settlement flag arc_no_pvp={}, justTeams={}",
            when { !manager.isPluginEnabled("Lands") -> "absent"; LandsPvpFlag.isRegistered() -> "registered"; else -> "unavailable: restart Paper" },
            when { native == null -> "absent"; teamFailure.isSuccess -> "cached API"; else -> "unavailable" })
    }

    override fun reload() = init()

    override fun shutdown() {
        runtime?.let { HandlerList.unregisterAll(it); it.close() }
        runtime = null
    }

    override fun check(attacker: Player, victim: Player, source: Entity): Result =
        runtime?.check(attacker, victim, source) ?: Result.UNAVAILABLE

    override fun notifyDenied(attacker: Player, reason: Result) { runtime?.notifyDenied(attacker, reason) }

    private val DEFAULT_MESSAGES = mapOf(
        Result.SETTLEMENT to "<#e7a9a9>В этом поселении PvP запрещено.",
        Result.TEAM to "<#e7a9a9>В вашем клане отключён урон своим.",
        Result.ALLY to "<#e7a9a9>Союзный клан защищён от вашего урона.",
        Result.TEAM_PROJECTILE to "<#e7a9a9>В вашем клане запрещён урон по своим от снарядов.",
        Result.TEAM_TNT to "<#e7a9a9>В вашем клане запрещён урон по своим от TNT.",
        Result.UNAVAILABLE to "<#e7a9a9>Проверка защиты PvP сейчас недоступна.",
    )
}
