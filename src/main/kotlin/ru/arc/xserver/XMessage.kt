package ru.arc.xserver

import com.google.gson.annotations.SerializedName
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.debug
import ru.arc.util.TextUtil
import ru.arc.util.Logging.warn
import ru.arc.xaction.XCondition
import ru.arc.xserver.playerlist.PlayerManager
import java.time.Duration

class XMessage(
    @SerializedName("t")  var type: Type? = null,
    @SerializedName("m")  var serializedMessage: String? = null,
    @SerializedName("st") var serializationType: SerializationType? = null,
    @SerializedName("cond") var conditions: List<XCondition>? = null,
    @SerializedName("td")  var toastData: ToastData? = null,
    @SerializedName("bbn") var bossBarData: BossBarData? = null,
    @SerializedName("p")   var announceData: AnnounceData? = null,
    @SerializedName("ab")  var actionBarData: ActionBarData? = null,
    @SerializedName("ti")  var titleData: TitleData? = null,
) : XAction() {

    fun appliesToServer(serverName: String?): Boolean {
        return targetsCurrentServer(announceData?.targetServers, serverName)
    }

    override fun runInternal() {
        val serverName = XConditionContext.currentServerName()
        if (!appliesToServer(serverName)) {
            debug("XMessage skip server={} reason=server-filter {}", serverName, logSummary())
            return
        }
        val players = filteredPlayers()
        players.forEach(::deliverTo)
    }

    internal fun deliverTo(player: Player) {
        when (type) {
            Type.CHAT -> deliverChat(player)
            Type.ACTION_BAR -> deliverActionBar(player)
            Type.BOSS_BAR -> deliverBossBar(player)
            Type.TITLE -> deliverTitle(player)
            Type.TOAST -> deliverToast(player)
            null -> warn("Cannot deliver xMessage without a type")
        }
    }

    private fun deliverChat(player: Player) {
        val reason = skipReason(player)
        if (reason != null) {
            debug("XMessage CHAT skip player={} reason={} {}", player.name, reason, logSummary())
            return
        }
        debug(
            "XMessage CHAT deliver player={} plainLen={} plain=\"{}\" {}",
            player.name,
            plainText(player).length,
            plainText(player).take(120),
            logSummary(),
        )
        player.sendMessage(component(player))
    }

    private fun deliverActionBar(player: Player) {
        val reason = skipReason(player)
        if (reason != null) {
            debug("XMessage ACTION_BAR skip player={} reason={} {}", player.name, reason, logSummary())
            return
        }
        val durationSeconds = actionBarData?.seconds ?: 0
        val cmi = HookRegistry.cmiHook
        if (durationSeconds > 0 && cmi != null) {
            cmi.sendActionbar(serializedMessage.orEmpty(), listOf(player), durationSeconds)
        } else {
            player.sendActionBar(component(player))
        }
    }

    private fun deliverBossBar(player: Player) {
        val cmi = HookRegistry.cmiHook ?: run { warn("CMILIB is required for BOSS_BAR xMessage"); return }
        val bbd = bossBarData ?: run { warn("BossBarData is required for BOSS_BAR xMessage"); return }
        val reason = skipReason(player)
        if (reason != null) {
            debug("XMessage BOSS_BAR skip player={} reason={} {}", player.name, reason, logSummary())
            return
        }
        debug(
            "XMessage BOSS_BAR deliver player={} plainLen={} plain=\"{}\" {}",
            player.name,
            plainText(player).length,
            plainText(player).take(120),
            logSummary(),
        )
        cmi.sendBossbar(bbd.name ?: "xmessage", serializedMessage, player, bbd.color, bbd.seconds, bbd.keepFor)
    }

    private fun deliverTitle(player: Player) {
        val reason = skipReason(player)
        if (reason != null) {
            debug("XMessage TITLE skip player={} reason={} {}", player.name, reason, logSummary())
            return
        }
        val data = titleData ?: TitleData()
        player.showTitle(
            Title.title(
                component(player),
                component(player, data.subtitle),
                Title.Times.times(
                    Duration.ofMillis(data.fadeInTicks.coerceAtLeast(0) * 50L),
                    Duration.ofMillis(data.stayTicks.coerceAtLeast(0) * 50L),
                    Duration.ofMillis(data.fadeOutTicks.coerceAtLeast(0) * 50L),
                ),
            ),
        )
    }

    private fun deliverToast(player: Player) {
        val cmi = HookRegistry.cmiHook ?: run { warn("CMILIB is required for TOAST xMessage"); return }
        val data = toastData ?: run { warn("ToastData is required for TOAST xMessage"); return }
        cmi.sendToast(serializedMessage, data.title, data.modelData, data.material, player)
    }

    fun filteredPlayers(): List<Player> {
        return PlayerManager.getOnlinePlayersThreadSafe().filter { player ->
            conditions?.all { it.matches(player) } != false
        }
    }

    fun resolvedMessage(player: Player): String = resolvedMessage(player, serializedMessage)

    private fun resolvedMessage(
        player: Player,
        rawMessage: String?,
    ): String {
        var message = rawMessage ?: return ""
        HookRegistry.papiHook?.parse(message, player)?.let { parsed -> message = parsed }
        return message
    }

    fun hasVisibleContent(player: Player): Boolean = skipReason(player) == null

    fun skipReason(player: Player): String? {
        if (resolvedMessage(player).trim().isEmpty()) return "resolved-empty"
        if (plainText(player).isBlank()) return "plain-empty"
        return null
    }

    fun plainText(player: Player): String =
        PlainTextComponentSerializer.plainText().serialize(component(player))

    fun component(player: Player): Component = component(player, serializedMessage)

    private fun component(
        player: Player,
        rawMessage: String?,
    ): Component {
        val message = resolvedMessage(player, rawMessage)
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

    enum class Type { CHAT, ACTION_BAR, BOSS_BAR, TITLE, TOAST }

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

    data class TitleData(
        @SerializedName("s") val subtitle: String? = null,
        @SerializedName("fi") val fadeInTicks: Int = 10,
        @SerializedName("st") val stayTicks: Int = 70,
        @SerializedName("fo") val fadeOutTicks: Int = 20,
    )
}
