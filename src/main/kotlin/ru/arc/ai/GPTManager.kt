package ru.arc.ai

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerChatEvent
import ru.arc.ARC
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.ai.llm.ModerationService
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.llm.SimpleChatService
import ru.arc.config.ConfigManager
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

    private val legacyConfig = ConfigManager.of(ARC.instance.dataPath, "gpt.yml")
    private val entities = ConcurrentHashMap<String, GPTEntity>()
    private val conversations = ConcurrentHashMap<UUID, MutableList<Conversation>>()
    private val awaitingResponse = ConcurrentSkipListSet<UUID>()
    private var cleanupTask: Any? = null

    private lateinit var llmConfig: LlmModuleConfig
    private lateinit var moderationService: ModerationService
    private lateinit var chatService: SimpleChatService

    @JvmStatic
    fun init(config: LlmModuleConfig, llmClient: OpenRouterLlmClient) {
        llmConfig = config
        moderationService = ModerationService(llmClient, config)
        chatService = SimpleChatService(llmClient, config)

        entities.clear()
        conversations.clear()
        cancel()

        cleanupTask =
            Tasks.scheduler.repeatingAsync(period = (20 * 30L).ticks, delay = 0.ticks) {
                val now = System.currentTimeMillis()
                conversations.forEach { (uuid, convs) ->
                    val removed = convs.removeIf { now - it.lastMessageTime > it.lifeTime }
                    if (removed) info("Removed expired conversations for player {}", uuid)
                    if (convs.isEmpty()) conversations.remove(uuid)
                }
            }
    }

    @JvmStatic
    fun shutdown() {
        cancel()
    }

    @JvmStatic
    fun cancel() {
        (cleanupTask as? ru.arc.core.ScheduledTask)?.cancel()
    }

    @JvmStatic
    fun getResponse(
        player: Player,
        message: String,
        id: String,
        archetype: String,
    ): CompletableFuture<Optional<String>> {
        val entity = entities.computeIfAbsent(id) { createEntity(archetype, id, true) }
        if (entity.archetype != archetype) {
            warn("Entity {} has different archetype {} than expected {}", id, entity.archetype, archetype)
        }
        return entity.getResponse(player.uniqueId, player.name, message)
    }

    @JvmStatic
    fun moderationResponse(message: String): CompletableFuture<Optional<ModerResponse>> {
        if (!::moderationService.isInitialized) {
            warn("AI moderation not initialized — skipping message")
            return CompletableFuture.completedFuture(Optional.empty())
        }
        return try {
            entities.computeIfAbsent("moderator") { createEntity("moderator", "moderator", false) }
                .getModerResponse(message)
        } catch (e: Exception) {
            error("Error getting moderation response", e)
            CompletableFuture.completedFuture(Optional.empty())
        }
    }

    private fun createEntity(archetype: String, id: String, useHistory: Boolean): GPTEntity =
        GPTEntity(legacyConfig, llmConfig, chatService, moderationService, archetype, id, useHistory)

    @JvmStatic
    fun processMessage(chatEvent: AsyncPlayerChatEvent) {
        if (awaitingResponse.contains(chatEvent.player.uniqueId)) return
        processMessage(chatEvent.message, chatEvent.player, appendCancel = true)
    }

    @JvmStatic
    fun processMessage(
        message: String,
        player: Player,
        appendCancel: Boolean,
    ): CompletableFuture<Void> {
        val conv = conversations[player.uniqueId] ?: return CompletableFuture.completedFuture(null)
        if (conv.isEmpty()) return CompletableFuture.completedFuture(null)

        val playerLocation = player.location
        val now = System.currentTimeMillis()
        val conversation =
            conv.firstOrNull { c ->
                val loc = c.location ?: return@firstOrNull false
                ((loc.world?.name == playerLocation.world?.name && loc.distance(playerLocation) < c.radius) || c.radius < 0) &&
                    now - c.lastMessageTime < c.lifeTime
            } ?: return CompletableFuture.completedFuture(null)

        val actualMessage = if (message.startsWith("!")) message.substring(1) else message
        return getResponseAndSend(player, actualMessage, conversation, appendCancel)
    }

    private fun getResponseAndSend(
        player: Player,
        message: String,
        conversation: Conversation,
        appendCancel: Boolean,
    ): CompletableFuture<Void> {
        awaitingResponse.add(player.uniqueId)
        return getResponse(
            player,
            message,
            conversation.gptId ?: return CompletableFuture.completedFuture(null),
            conversation.archetype ?: "default",
        ).thenAccept { response ->
            conversation.lastMessageTime = System.currentTimeMillis()
            if (response.isEmpty) {
                awaitingResponse.remove(player.uniqueId)
                return@thenAccept
            }
            val responseMessage = formatMessage(response.get(), conversation, appendCancel)
            Tasks.scheduler.runSync(
                Runnable {
                    displayChatBubble(response.get(), conversation)
                    if (conversation.privateConversation) {
                        player.sendMessage(responseMessage)
                    } else {
                        conversation.location?.getNearbyPlayers(conversation.radius)?.forEach {
                            it.sendMessage(responseMessage)
                        }
                    }
                    awaitingResponse.remove(player.uniqueId)
                },
            )
        }
    }

    private fun displayChatBubble(message: String, conversation: Conversation) {
        if (HookRegistry.citizensHook == null || conversation.npcId == null) return
        if (message.length > legacyConfig.integer("max-bubble-length", 50)) return
        val s = TextUtil.mmToLegacy(message)
        val list =
            s.split("\n").map {
                CitizensHook.HologramLine(it, legacyConfig.integer("bubble-duration-ticks", 20 * 20))
            }
        HookRegistry.citizensHook?.addChatBubble(conversation.npcId, list)
    }

    private fun formatMessage(
        message: String,
        conversation: Conversation,
        appendCancel: Boolean,
    ) = TextUtil.mm(
        buildString {
            append(legacyConfig.string("message-format", "<gray><gold>%gpt_name%<gray> » <white>%message%"))
            if (appendCancel) {
                append(
                    legacyConfig.string(
                        "cancel-appendix",
                        "\n<red><hover:show_text:'Нажмите, чтобы закончить'><click:run_command:/arc ai stop %id%>[Нажмите, чтобы закончить разговор]</click></hover>",
                    ),
                )
            }
        }.replace("%gpt_name%", conversation.talkerName ?: "")
            .replace("%message%", message)
            .replace("%id%", conversation.gptId ?: ""),
    )

    @JvmStatic
    fun startConversation(
        player: Player,
        id: String,
        archetype: String,
        talkerName: String,
        location: Location,
        radius: Double,
        lifeTime: Long,
        initialMessage: String?,
        endMessage: String?,
        npcId: Int?,
        privateConversation: Boolean,
    ) {
        entities.computeIfAbsent(id) { createEntity(archetype, id, true) }
        val convs = conversations.computeIfAbsent(player.uniqueId) { mutableListOf() }
        if (convs.any { it.gptId == id }) return

        val conv =
            Conversation(
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
                privateConversation = privateConversation,
            )
        convs.add(conv)
        if (initialMessage != null) {
            getResponseAndSend(player, initialMessage, conv, appendCancel = false)
        }
    }

    @JvmStatic
    fun endConversation(player: Player, id: String) {
        val convs = conversations[player.uniqueId] ?: return
        val conversation = convs.find { it.gptId == id } ?: return
        if (conversation.endMessage != null) {
            processMessage(conversation.endMessage, player, appendCancel = false)
                .thenAccept { player.sendMessage(legacyConfig.component("end-message", "<red>Вы закончили разговор")) }
        } else {
            player.sendMessage(legacyConfig.component("end-message", "<red>Вы закончили разговор"))
        }
        convs.remove(conversation)
    }

    @JvmStatic
    fun endAllConversations(player: Player) {
        conversations.remove(player.uniqueId)
        player.sendMessage(legacyConfig.component("end-all-message", "<red>Вы закончили все разговоры"))
    }

    @JvmStatic
    fun getConversations(player: Player): List<Conversation> = conversations.getOrDefault(player.uniqueId, emptyList())
}
