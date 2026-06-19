package ru.arc.treasure.core

import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import ru.arc.util.TextUtil
import java.util.UUID

/**
 * Where the message should be displayed.
 */
enum class MessageDestination {
    CHAT,
    ACTION_BAR,
    BOSS_BAR,
    TITLE,
}

/**
 * Who should receive the message.
 */
enum class MessageTarget {
    /** Only the player who received the treasure */
    PLAYER,

    /** All players on the same server */
    SERVER,

    /** All players on all servers (cross-server via Redis) */
    GLOBAL,

    /** Players within a certain radius */
    NEARBY,
}

/**
 * Unified message configuration for treasures.
 * Replaces the old message/globalMessage/announce system with a more flexible approach.
 */
data class TreasureMessage(
    val text: String,
    val destination: MessageDestination = MessageDestination.CHAT,
    val target: MessageTarget = MessageTarget.PLAYER,
    val nearbyRadius: Double = 50.0,
    val bossBarColor: BarColor = BarColor.YELLOW,
    val bossBarSeconds: Int = 5,
    val titleFadeIn: Int = 10,
    val titleStay: Int = 70,
    val titleFadeOut: Int = 20,
    val titleSubtitle: String? = null,
) {
    /**
     * Sends this message with the given context.
     */
    fun send(context: MessageContext) {
        when (target) {
            MessageTarget.PLAYER -> sendToPlayer(context.player, context)
            MessageTarget.SERVER -> sendToServer(context)
            MessageTarget.GLOBAL -> sendGlobal(context)
            MessageTarget.NEARBY -> sendNearby(context)
        }
    }

    private fun sendToPlayer(
        player: Player,
        context: MessageContext,
    ) {
        val resolved = resolveText(context)
        when (destination) {
            MessageDestination.CHAT -> player.sendMessage(TextUtil.mm(resolved))
            MessageDestination.ACTION_BAR -> player.sendActionBar(TextUtil.mm(resolved))
            MessageDestination.BOSS_BAR -> sendBossBar(player, resolved)
            MessageDestination.TITLE -> sendTitle(player, resolved, context)
        }
    }

    private fun sendToServer(context: MessageContext) {
        resolveText(context)
        org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
            sendToPlayer(player, context.copy(player = player))
        }
    }

    private fun sendGlobal(context: MessageContext) {
        // For cross-server broadcasts, we use AnnounceManager's global methods
        // which handle XMessage building internally via Java
        val resolved = resolveText(context)

        when (destination) {
            MessageDestination.CHAT -> {
                // Use sendToServer first, then trigger cross-server via simple announce
                org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
                    player.sendMessage(TextUtil.mm(resolved))
                }
                // TODO: Add cross-server support via XActionManager when Kotlin-Lombok interop is resolved
            }

            MessageDestination.ACTION_BAR -> {
                org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
                    player.sendActionBar(TextUtil.mm(resolved))
                }
            }

            MessageDestination.BOSS_BAR -> {
                ru.arc.hooks.HookRegistry.cmiHook?.let { cmi ->
                    org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
                        cmi.sendBossbar(
                            "treasure-global",
                            resolved,
                            player,
                            bossBarColor,
                            bossBarSeconds,
                            0,
                        )
                    }
                }
            }

            MessageDestination.TITLE -> {
                org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
                    sendTitle(player, resolved, context)
                }
            }
        }
    }

    private fun sendNearby(context: MessageContext) {
        resolveText(context)
        val location = context.player.location

        org.bukkit.Bukkit
            .getOnlinePlayers()
            .filter { it.world == location.world }
            .filter { it.location.distance(location) <= nearbyRadius }
            .forEach { player ->
                sendToPlayer(player, context.copy(player = player))
            }
    }

    private fun sendBossBar(
        player: Player,
        text: String,
    ) {
        ru.arc.hooks.HookRegistry.cmiHook?.sendBossbar(
            "treasure-${UUID.randomUUID().toString().take(8)}",
            text,
            player,
            bossBarColor,
            bossBarSeconds,
            0,
        )
    }

    private fun sendTitle(
        player: Player,
        text: String,
        context: MessageContext,
    ) {
        val subtitle = titleSubtitle?.let { resolveText(context.copy(text = it)) } ?: ""
        player.showTitle(
            net.kyori.adventure.title.Title.title(
                TextUtil.mm(text),
                TextUtil.mm(subtitle),
                net.kyori.adventure.title.Title.Times.times(
                    java.time.Duration.ofMillis(titleFadeIn * 50L),
                    java.time.Duration.ofMillis(titleStay * 50L),
                    java.time.Duration.ofMillis(titleFadeOut * 50L),
                ),
            ),
        )
    }

    private fun resolveText(context: MessageContext): String =
        context.text?.let { text.replace("%text%", it) }
            ?: text
                .replace("%player%", context.player.name)
                .replace("%amount%", formatAmount(context.amount))
                .replace("%item%", context.itemName ?: "")
                .replace("%pool%", context.poolId ?: "")

    private fun formatAmount(amount: Number?): String {
        if (amount == null) return ""
        val value = amount.toDouble()
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            "%.2f".format(value)
        }
    }

    /**
     * Serializes to a map for YAML storage.
     */
    fun toMap(): Map<String, Any?> =
        buildMap {
            put("text", text)
            if (destination != MessageDestination.CHAT) put("destination", destination.name.lowercase())
            if (target != MessageTarget.PLAYER) put("target", target.name.lowercase())
            if (target == MessageTarget.NEARBY && nearbyRadius != 50.0) put("radius", nearbyRadius)
            if (destination == MessageDestination.BOSS_BAR) {
                put("bossbar-color", bossBarColor.name.lowercase())
                put("bossbar-seconds", bossBarSeconds)
            }
            if (destination == MessageDestination.TITLE) {
                titleSubtitle?.let { put("subtitle", it) }
            }
        }

    companion object {
        /**
         * Deserializes from a map.
         */
        fun fromMap(map: Map<String, Any?>): TreasureMessage? {
            val text = map["text"] as? String ?: return null

            val destination =
                (map["destination"] as? String)
                    ?.uppercase()
                    ?.let { runCatching { MessageDestination.valueOf(it) }.getOrNull() }
                    ?: MessageDestination.CHAT

            val target =
                (map["target"] as? String)
                    ?.uppercase()
                    ?.let { runCatching { MessageTarget.valueOf(it) }.getOrNull() }
                    ?: MessageTarget.PLAYER

            return TreasureMessage(
                text = text,
                destination = destination,
                target = target,
                nearbyRadius = (map["radius"] as? Number)?.toDouble() ?: 50.0,
                bossBarColor =
                    (map["bossbar-color"] as? String)
                        ?.uppercase()
                        ?.let { runCatching { BarColor.valueOf(it) }.getOrNull() }
                        ?: BarColor.YELLOW,
                bossBarSeconds = (map["bossbar-seconds"] as? Number)?.toInt() ?: 5,
                titleSubtitle = map["subtitle"] as? String,
            )
        }

        /**
         * Creates a simple chat message for the player.
         */
        fun chat(text: String) = TreasureMessage(text, MessageDestination.CHAT, MessageTarget.PLAYER)

        /**
         * Creates a broadcast chat message.
         */
        fun broadcast(
            text: String,
            global: Boolean = false,
        ) = TreasureMessage(
            text = text,
            destination = MessageDestination.CHAT,
            target = if (global) MessageTarget.GLOBAL else MessageTarget.SERVER,
        )

        /**
         * Creates an action bar message.
         */
        fun actionBar(text: String) = TreasureMessage(text, MessageDestination.ACTION_BAR, MessageTarget.PLAYER)

        /**
         * Creates a boss bar message.
         */
        fun bossBar(
            text: String,
            color: BarColor = BarColor.YELLOW,
            seconds: Int = 5,
        ) = TreasureMessage(
            text = text,
            destination = MessageDestination.BOSS_BAR,
            target = MessageTarget.PLAYER,
            bossBarColor = color,
            bossBarSeconds = seconds,
        )

        /**
         * Creates a title message.
         */
        fun title(
            title: String,
            subtitle: String? = null,
        ) = TreasureMessage(
            text = title,
            destination = MessageDestination.TITLE,
            target = MessageTarget.PLAYER,
            titleSubtitle = subtitle,
        )

        /**
         * Migrates old message format to new format.
         * @param message Personal message (nullable)
         * @param globalMessage Global announcement message (nullable)
         * @param announce Whether to announce
         */
        fun fromLegacy(
            message: String?,
            globalMessage: String?,
            announce: Boolean,
        ): List<TreasureMessage> =
            buildList {
                message?.let { add(chat(it)) }
                if (announce && globalMessage != null) {
                    add(broadcast(globalMessage, global = true))
                }
            }
    }
}

/**
 * Context for resolving message placeholders.
 */
data class MessageContext(
    val player: Player,
    val amount: Number? = null,
    val itemName: String? = null,
    val poolId: String? = null,
    val text: String? = null,
)
