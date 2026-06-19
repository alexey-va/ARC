@file:Suppress("DEPRECATION")

package ru.arc.ai

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerChatEvent
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.core.Tasks
import ru.arc.core.repeatingAsync
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

object GPTManager {

    private val entities = ConcurrentHashMap<String, GPTEntity>()
    private val config = ConfigManager.of(ARC.instance.dataPath, "gpt.yml")
    private val conversations = ConcurrentHashMap<UUID, MutableList<Conversation>>()
    private val awaitingResponse = ConcurrentSkipListSet<UUID>()
    private var cleanupTask: Any? = null
    private lateinit var moderatorGpt: GPTEntity

    @JvmStatic
    fun init() {
        entities.clear()
        conversations.clear()
        cancel()

        cleanupTask = Tasks.scheduler.repeatingAsync(period = (20 * 30L).ticks, delay = 0.ticks) {
            val now = System.currentTimeMillis()
            conversations.forEach { (uuid, convs) ->
                val removed = convs.removeIf { now - it.lastMessageTime > it.lifeTime }
                if (removed) info("Removed expired conversations for player {}", uuid)
                if (convs.isEmpty()) conversations.remove(uuid)
            }
        }

        moderatorGpt = GPTEntity(config, "moderator", "moderator", false)
    }

    @JvmStatic
    fun cancel() {
        (cleanupTask as? ru.arc.core.ScheduledTask)?.cancel()
    }

    @JvmStatic
    fun getResponse(player: Player, message: String, id: String, archetype: String): CompletableFuture<Optional<String>> {
        val entity = entities.computeIfAbsent(id) { GPTEntity(config, archetype, id, true) }
        if (entity.archetype != archetype) warn("Entity {} has different archetype {} than expected {}", id, entity.archetype, archetype)
        return entity.getResponse(player.uniqueId, player.name, message)
    }

    @JvmStatic
    fun moderationResponse(message: String): CompletableFuture<Optional<ModerResponse>> {
        if (!::moderatorGpt.isInitialized) {
            warn("GPT moderator not initialized — skipping moderation for message: {}", message)
            return CompletableFuture.completedFuture(Optional.empty())
        }
        return try {
            moderatorGpt.getModerResponse(message)
        } catch (e: Exception) {
            error("Error getting moderation response", e)
            CompletableFuture.completedFuture(Optional.empty())
        }
    }

    @JvmStatic
    fun processMessage(chatEvent: AsyncPlayerChatEvent) {
        if (awaitingResponse.contains(chatEvent.player.uniqueId)) return
        processMessage(chatEvent.message, chatEvent.player, appendCancel = true)
    }

    @JvmStatic
    fun processMessage(message: String, player: Player, appendCancel: Boolean): CompletableFuture<Void> {
        val conv = conversations[player.uniqueId] ?: return CompletableFuture.completedFuture(null)
        if (conv.isEmpty()) return CompletableFuture.completedFuture(null)

        val playerLocation = player.location
        info("Looking for conversation for player {} at location {}", player.name, playerLocation)
        val now = System.currentTimeMillis()
        val conversation = conv.firstOrNull { c ->
            val loc = c.location ?: return@firstOrNull false
            ((loc.world?.name == playerLocation.world?.name && loc.distance(playerLocation) < c.radius) || c.radius < 0)
                && now - c.lastMessageTime < c.lifeTime
        } ?: run {
            info("Player {} is not in range of any conversation", player.name)
            return CompletableFuture.completedFuture(null)
        }

        info("Player {} is in range of conversation with entity {}", player.name, conversation.gptId)
        val actualMessage = if (message.startsWith("!")) message.substring(1) else message
        return getResponseAndSend(player, actualMessage, conversation, appendCancel)
    }

    private fun getResponseAndSend(player: Player, message: String, conversation: Conversation, appendCancel: Boolean): CompletableFuture<Void> {
        awaitingResponse.add(player.uniqueId)
        return getResponse(player, message, conversation.gptId ?: return CompletableFuture.completedFuture(null), conversation.archetype ?: "default")
            .thenAccept { response ->
                conversation.lastMessageTime = System.currentTimeMillis()
                if (response.isEmpty) {
                    warn("Empty response from GPT for player {}", player.name)
                    awaitingResponse.remove(player.uniqueId)
                    return@thenAccept
                }
                val responseMessage = formatMessage(response.get(), conversation, appendCancel)
                Tasks.scheduler.runSync(Runnable {
                    displayChatBubble(response.get(), conversation)
                    if (conversation.privateConversation) {
                        player.sendMessage(responseMessage)
                    } else {
                        conversation.location?.getNearbyPlayers(conversation.radius)
                            ?.forEach { it.sendMessage(responseMessage) }
                    }
                    awaitingResponse.remove(player.uniqueId)
                })
            }
    }

    private fun displayChatBubble(message: String, conversation: Conversation) {
        if (HookRegistry.citizensHook == null || conversation.npcId == null) return
        if (message.length > config.integer("max-bubble-length", 50)) {
            info("Message is too long for bubble: {}", message)
            return
        }
        val s = TextUtil.mmToLegacy(message)
        val list = s.split("\n").map { CitizensHook.HologramLine(it, config.integer("bubble-duration-ticks", 20 * 20)) }
        HookRegistry.citizensHook.addChatBubble(conversation.npcId, list)
    }

    private fun formatMessage(message: String, conversation: Conversation, appendCancel: Boolean): Component {
        var format = config.string("message-format", "<gray><gold>%gpt_name%<gray> » <white>%message%")
        if (appendCancel) format += config.string("cancel-appendix", "\n<red><hover:show_text:'Нажмите, чтобы закончить'><click:run_command:/arc ai stop %id%>[Нажмите, чтобы закончить разговор]</click></hover>")
        format = format.replace("%gpt_name%", conversation.talkerName ?: "")
        format = format.replace("%message%", message)
        format = format.replace("%id%", conversation.gptId ?: "")
        return TextUtil.mm(format)
    }

    @JvmStatic
    fun startConversation(player: Player, id: String, archetype: String, talkerName: String,
                          location: Location, radius: Double, lifeTime: Long, initialMessage: String?,
                          endMessage: String?, npcId: Int?, privateConversation: Boolean) {
        entities.computeIfAbsent(id) { GPTEntity(config, archetype, id, true) }
        val convs = conversations.computeIfAbsent(player.uniqueId) { mutableListOf() }
        if (convs.any { it.gptId == id }) {
            info("Player {} already has conversation with entity {}", player.name, id)
            return
        }
        val conv = Conversation(
            playerUuid = player.uniqueId,
            location = location,
            archetype = archetype,
            radius = radius,
            gptId = id,
            lastMessageTime = System.currentTimeMillis(),
            lifeTime = lifeTime,
            talkerName = talkerName,
            npcId = npcId,
            endMessage = endMessage,
            privateConversation = privateConversation
        )
        convs.add(conv)
        info("Player {} started conversation with entity {}", player.name, id)
        if (initialMessage != null) {
            getResponseAndSend(player, initialMessage, convs.last(), appendCancel = false)
        }
    }

    @JvmStatic
    fun endConversation(player: Player, id: String) {
        val convs = conversations[player.uniqueId] ?: return
        val conversation = convs.find { it.gptId == id }
        info("Player {} ended conversation with entity {}", player.name, id)
        if (conversation == null) {
            warn("Player {} tried to end conversation with entity {} but it was not found", player.name, id)
            return
        }
        if (conversation.endMessage != null) {
            processMessage(conversation.endMessage, player, appendCancel = false)
                .thenAccept { player.sendMessage(config.componentDef("end-message", "<red>Вы закончили разговор")) }
        } else {
            player.sendMessage(config.componentDef("end-message", "<red>Вы закончили разговор"))
        }
        convs.remove(conversation)
    }

    @JvmStatic
    fun endAllConversations(player: Player) {
        conversations.remove(player.uniqueId)
        player.sendMessage(config.componentDef("end-all-message", "<red>Вы закончили все разговоры"))
        info("Player {} ended all conversations", player.name)
    }

    @JvmStatic
    fun getConversations(player: Player): List<Conversation> =
        conversations.getOrDefault(player.uniqueId, emptyList())
}
