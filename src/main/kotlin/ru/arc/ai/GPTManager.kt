package ru.arc.ai

import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.ai.config.NpcChatConfig
import ru.arc.ai.llm.ModerationOutcome
import ru.arc.ai.llm.ModerationService
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.npc.NpcChatRpcClient
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.core.repeatingAsync
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import ru.arc.util.TextUtils
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object GPTManager {
    private const val LLM_THREADS = 2

    private val entities = ConcurrentHashMap<String, GPTEntity>()
    private val conversations = ConcurrentHashMap<UUID, CopyOnWriteArrayList<Conversation>>()
    private val responseGate = AwaitingResponseGate()
    private val lastRequestAt = ConcurrentHashMap<UUID, Long>()
    private val threadNumber = AtomicInteger()
    private var cleanupTask: ScheduledTask? = null
    private var requestExecutor: ExecutorService? = null
    private var running = false

    private lateinit var npcChatConfig: NpcChatConfig
    private lateinit var moderationService: ModerationService
    private lateinit var npcChatRpcClient: NpcChatRpcClient

    @JvmStatic
    fun init(
        llmConfig: LlmModuleConfig,
        npcChatConfig: NpcChatConfig,
        llmClient: OpenRouterLlmClient,
        npcChatRpcClient: NpcChatRpcClient,
    ) {
        shutdown()
        this.npcChatConfig = npcChatConfig
        this.npcChatRpcClient = npcChatRpcClient
        val executor =
            Executors.newFixedThreadPool(LLM_THREADS) { runnable ->
                Thread(runnable, "arc-gpt-${threadNumber.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        requestExecutor = executor
        moderationService = ModerationService(llmClient, llmConfig, executor)

        try {
            cleanupTask =
                Tasks.scheduler.repeatingAsync(period = (20 * 30L).ticks, delay = 0.ticks) {
                    val now = System.currentTimeMillis()
                    conversations.forEach { (uuid, convs) ->
                        val removed = convs.removeIf { now - it.lastMessageTime > it.lifeTime }
                        if (removed) info("Removed expired conversations for player {}", uuid)
                        if (convs.isEmpty()) conversations.remove(uuid)
                    }
                }
            running = true
        } catch (error: Exception) {
            executor.shutdownNow()
            requestExecutor = null
            throw error
        }
    }

    @JvmStatic
    fun shutdown() {
        running = false
        cancel()
        entities.clear()
        conversations.clear()
        responseGate.clear()
        lastRequestAt.clear()
        requestExecutor?.shutdownNow()
        requestExecutor = null
    }

    @JvmStatic
    fun cancel() {
        cleanupTask?.cancel()
        cleanupTask = null
    }

    @JvmStatic
    fun getResponse(
        player: Player,
        message: String,
        id: String,
        archetype: String,
    ): CompletableFuture<String?> {
        if (!running) return CompletableFuture.completedFuture(null)
        val entity = entities.computeIfAbsent(id) { createEntity(archetype, id, true) }
        if (entity.archetype != archetype) {
            warn("Entity {} has different archetype {} than expected {}", id, entity.archetype, archetype)
        }
        return entity.getResponse(player.uniqueId, player.name, message)
    }

    @JvmStatic
    fun moderationResponse(message: String): CompletableFuture<ModerResponse?> {
        if (!running || !::moderationService.isInitialized) {
            warn("AI moderation not initialized — skipping message")
            return CompletableFuture.completedFuture(null)
        }
        return moderationService.moderate(message).thenApply { result ->
            result ?: return@thenApply null
            val response =
                when (result.outcome) {
                    ModerationOutcome.OK -> ModerationResponse.OK
                    ModerationOutcome.BAD -> ModerationResponse.BAD
                    ModerationOutcome.UNKNOWN -> return@thenApply null
                }
            ModerResponse(response, result.comment)
        }.exceptionally { failure ->
            error("Error getting moderation response", failure)
            null
        }
    }

    private fun createEntity(archetype: String, id: String, useHistory: Boolean): GPTEntity =
        GPTEntity(npcChatConfig, npcChatRpcClient, archetype, id, useHistory)

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
        if (actualMessage.length > npcChatConfig.maxInputChars) {
            player.sendMessage(npcChatConfig.tooLongMessage)
            return CompletableFuture.completedFuture(null)
        }
        val previousRequest = lastRequestAt[player.uniqueId]
        if (previousRequest != null && now - previousRequest < npcChatConfig.cooldownMillis) {
            return CompletableFuture.completedFuture(null)
        }
        lastRequestAt[player.uniqueId] = now
        return getResponseAndSend(player, actualMessage, conversation, appendCancel)
    }

    private fun getResponseAndSend(
        player: Player,
        message: String,
        conversation: Conversation,
        appendCancel: Boolean,
    ): CompletableFuture<Void> {
        val gptId = conversation.gptId ?: return CompletableFuture.completedFuture(null)
        val responseFuture =
            responseGate.run(player.uniqueId) {
                getResponse(
                    player,
                    message,
                    gptId,
                    conversation.archetype ?: "default",
                )
            } ?: return CompletableFuture.completedFuture(null)

        return responseFuture.handle { response, failure ->
            conversation.lastMessageTime = System.currentTimeMillis()
            Tasks.scheduler.runSync(
                Runnable {
                    if (!player.isOnline) return@Runnable
                    if (failure != null || response == null) {
                        warn("NPC dialogue failed for player={} persona={}", player.name, conversation.archetype, failure)
                        player.sendMessage(npcChatConfig.fallbackMessage)
                        return@Runnable
                    }
                    val responseMessage = formatMessage(response, conversation, appendCancel)
                    displayChatBubble(response, conversation)
                    if (conversation.privateConversation) {
                        player.sendMessage(responseMessage)
                    } else {
                        conversation.location?.getNearbyPlayers(conversation.radius)?.forEach {
                            it.sendMessage(responseMessage)
                        }
                    }
                },
            )
            null
        }
    }

    private fun displayChatBubble(message: String, conversation: Conversation) {
        if (HookRegistry.citizensHook == null || conversation.npcId == null) return
        if (message.length > npcChatConfig.maxBubbleLength) return
        val list =
            message.split("\n").map {
                CitizensHook.HologramLine("&f$it", npcChatConfig.bubbleDurationTicks)
            }
        HookRegistry.citizensHook?.addChatBubble(conversation.npcId, list)
    }

    private fun formatMessage(
        message: String,
        conversation: Conversation,
        appendCancel: Boolean,
    ) =
        TextUtil.mm(
            buildString {
                append(npcChatConfig.messageFormat)
                if (appendCancel) {
                    append(npcChatConfig.cancelAppendix)
                }
            }.replace("%gpt_name%", TextUtils.escapeMM(conversation.talkerName ?: ""))
                .replace("%message%", TextUtils.escapeMM(message))
                .replace("%id%", TextUtils.escapeMM(conversation.gptId ?: "")),
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
        if (!running) {
            warn("Cannot start GPT conversation before AI initialization")
            return
        }
        entities.computeIfAbsent(id) { createEntity(archetype, id, true) }
        val convs = conversations.computeIfAbsent(player.uniqueId) { CopyOnWriteArrayList() }
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
            val responseMessage = formatMessage(initialMessage, conv, appendCancel = true)
            displayChatBubble(initialMessage, conv)
            player.sendMessage(responseMessage)
        }
    }

    @JvmStatic
    fun endConversation(player: Player, id: String) {
        val convs = conversations[player.uniqueId] ?: return
        val conversation = convs.find { it.gptId == id } ?: return
        if (conversation.endMessage != null) {
            val responseMessage = formatMessage(conversation.endMessage, conversation, appendCancel = false)
            displayChatBubble(conversation.endMessage, conversation)
            player.sendMessage(responseMessage)
        }
        convs.remove(conversation)
        player.sendMessage(npcChatConfig.endMessage)
    }

    @JvmStatic
    fun endAllConversations(player: Player) {
        conversations.remove(player.uniqueId)
        lastRequestAt.remove(player.uniqueId)
        player.sendMessage(npcChatConfig.endAllMessage)
    }

    @JvmStatic
    fun getConversations(player: Player): List<Conversation> = conversations[player.uniqueId]?.toList() ?: emptyList()

    internal fun isRunning(): Boolean = running

    internal fun hasActiveExecutor(): Boolean = requestExecutor?.isShutdown == false
}

internal class AwaitingResponseGate {
    private val active = ConcurrentHashMap<UUID, ActiveRequest>()

    fun <T> run(
        playerId: UUID,
        operation: () -> CompletableFuture<T>,
    ): CompletableFuture<T>? {
        val request = ActiveRequest()
        if (active.putIfAbsent(playerId, request) != null) return null
        val future =
            try {
                operation()
            } catch (error: Exception) {
                CompletableFuture.failedFuture(error)
            }
        request.attach(future)
        return future.whenComplete { _, _ -> active.remove(playerId, request) }
    }

    fun isAwaiting(playerId: UUID): Boolean = active.containsKey(playerId)

    fun clear() {
        val requests = active.values.toList()
        active.clear()
        requests.forEach(ActiveRequest::cancel)
    }

    private class ActiveRequest {
        @Volatile
        private var future: CompletableFuture<*>? = null

        @Volatile
        private var cancelled = false

        fun attach(future: CompletableFuture<*>) {
            this.future = future
            if (cancelled) future.cancel(true)
        }

        fun cancel() {
            cancelled = true
            future?.cancel(true)
        }
    }
}
