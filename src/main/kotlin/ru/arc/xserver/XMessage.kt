package ru.arc.xserver

import com.google.gson.annotations.SerializedName
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import ru.arc.util.TextUtil
import ru.arc.util.Logging.warn
import ru.arc.xserver.playerlist.PlayerManager

class XMessage(
    @SerializedName("t")  var type: Type? = null,
    @SerializedName("m")  var serializedMessage: String? = null,
    @SerializedName("st") var serializationType: SerializationType? = null,
    @SerializedName("cond") var conditions: List<XCondition>? = null,
    @SerializedName("td")  var toastData: ToastData? = null,
    @SerializedName("bbn") var bossBarData: BossBarData? = null,
    @SerializedName("p")   var announceData: AnnounceData? = null,
    @SerializedName("ab")  var actionBarData: ActionBarData? = null
) : XAction() {

    fun appliesToServer(serverName: String?): Boolean {
        val targets = announceData?.targetServers
        if (targets.isNullOrEmpty()) return true
        if (serverName.isNullOrBlank()) return false
        return targets.any { it.equals(serverName, ignoreCase = true) }
    }

    override fun runInternal() {
        if (!appliesToServer(XCondition.currentServerName())) return
        val players = filteredPlayers()
        when (type) {
            Type.CHAT ->
                players.forEach { player ->
                    if (hasVisibleContent(player)) {
                        player.sendMessage(component(player))
                    }
                }
            Type.TOAST -> {
                val cmi = HookRegistry.cmiHook ?: run { warn("CMILIB is required for TOAST xMessage"); return }
                val td = toastData ?: run { warn("ToastData is required for TOAST xMessage"); return }
                cmi.sendToast(serializedMessage, td.title, td.modelData, td.material, *players.toTypedArray())
            }
            Type.BOSS_BAR -> {
                val cmi = HookRegistry.cmiHook ?: run { warn("CMILIB is required for BOSS_BAR xMessage"); return }
                val bbd = bossBarData ?: run { warn("BossBarData is required for BOSS_BAR xMessage"); return }
                players.forEach { p ->
                    if (!hasVisibleContent(p)) return@forEach
                    cmi.sendBossbar(bbd.name ?: "xmessage", serializedMessage, p, bbd.color, bbd.seconds, bbd.keepFor)
                }
            }
            else -> {}
        }
    }

    fun filteredPlayers(): List<Player> {
        return PlayerManager.getOnlinePlayersThreadSafe().filter { player ->
            conditions?.all { it.test(player) } != false
        }
    }

    fun resolvedMessage(player: Player): String {
        var message = serializedMessage ?: return ""
        HookRegistry.papiHook?.parse(message, player)?.let { parsed -> message = parsed }
        return message
    }

    fun hasVisibleContent(player: Player): Boolean {
        if (resolvedMessage(player).trim().isEmpty()) return false
        val plain = PlainTextComponentSerializer.plainText().serialize(component(player))
        return plain.isNotBlank()
    }

    fun component(player: Player): Component {
        val message = resolvedMessage(player)
        return when (serializationType) {
            SerializationType.MINI_MESSAGE -> TextUtil.mm(message)
            SerializationType.LEGACY -> TextUtil.legacy(message)
            else -> TextUtil.plain(message)
        }
    }

    fun logSummary(): String {
        val typeName = type?.name ?: "UNKNOWN"
        val text = serializedMessage?.replace('\n', ' ')?.trim()?.take(160) ?: "<empty>"
        val weight = announceData?.weight?.takeIf { it > 0 }
        return if (weight != null) {
            "type=$typeName weight=$weight text=\"$text\""
        } else {
            "type=$typeName text=\"$text\""
        }
    }

    enum class Type { CHAT, ACTION_BAR, BOSS_BAR, TOAST }

    enum class SerializationType { MINI_MESSAGE, LEGACY, PLAIN }

    data class BossBarData(
        @SerializedName("n") val name: String? = null,
        @SerializedName("c") val color: BarColor? = null,
        @SerializedName("s") val seconds: Int = 0,
        @SerializedName("t") val keepFor: Int = 0
    )

    data class ToastData(
        @SerializedName("m") val material: Material = Material.STONE,
        @SerializedName("md") val modelData: Int = 0,
        @SerializedName("t") val title: String? = null
    )

    data class AnnounceData(
        @SerializedName("m") val weight: Int = 0,
        @SerializedName("p") val personal: Boolean = false,
        /** null = all servers; otherwise at least one name must match redis.server-name */
        @SerializedName("srv") val targetServers: Set<String>? = null,
    )

    data class ActionBarData(
        @SerializedName("s") val seconds: Int = 0
    )
}
