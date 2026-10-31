package ru.arc.helpcenter

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.network.BackendServerId
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.ArcTaskRedisRequestTimeoutScheduler
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.arc.redis.safety.RedisWireCodec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class HelpCenterEnchantmentAcquisitionMessage(
    val id: String = "",
    val replyTo: String? = null,
    val type: String = "",
    val originServer: String = "",
    val targetServer: String = "",
    val topicId: String = "",
    val createdAtMillis: Long = 0L,
    val facts: List<HelpCenterEnchantmentSourceFact> = emptyList(),
    val relatedTopics: List<String> = emptyList(),
)

/**
 * On-demand external reward facts from Spawn, where the native crates are loaded.
 * The provider is invoked only on the owning Paper thread; Redis publication is
 * asynchronous. No player, world, item or reward state is transported or mutated.
 * The core channel owns correlation, bounded pending requests and the 10s timeout.
 * Closing also settles callers queued before their asynchronous publication.
 */
internal class HelpCenterEnchantmentAcquisitionNetwork(
    redis: RedisOperations,
    localServer: String,
    private val describeLocal: (String) -> CompletableFuture<HelpCenterEnchantmentAcquisition>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    scheduler: TaskScheduler = Tasks.scheduler,
    timeoutScheduler: RedisRequestTimeoutScheduler = ArcTaskRedisRequestTimeoutScheduler,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(HelpCenterEnchantmentAcquisitionNetwork::class.java)
    private val serverId = BackendServerId.of(localServer).value
    private val lifecycle = LifecycleTaskScope(scheduler, initiallyActive = true)
    private val token = lifecycle.token()
    private val closed = AtomicBoolean(false)
    private val failures = ConcurrentHashMap.newKeySet<String>()
    private val callers = mutableSetOf<CompletableFuture<HelpCenterEnchantmentAcquisition?>>()
    val isSourceServer: Boolean get() = serverId == HelpCenterEnchantmentAcquisitionWire.SOURCE_SERVER

    private val channel = RedisRequestReplyChannel(
        redis = redis,
        channel = HelpCenterEnchantmentAcquisitionWire.CHANNEL,
        codec = HelpCenterEnchantmentAcquisitionWire.codec,
        originAllowed = { BackendServerId.parseOrNull(it) != null },
        requestId = HelpCenterEnchantmentAcquisitionMessage::id,
        replyTo = HelpCenterEnchantmentAcquisitionMessage::replyTo,
        replyAllowed = { request, reply, origin ->
            HelpCenterEnchantmentAcquisitionWire.isExactReply(request, reply, origin, nowMillis())
        },
        timeoutMillis = HelpCenterEnchantmentAcquisitionWire.TTL_MILLIS,
        maxPending = MAX_CALLERS,
        timeoutScheduler = timeoutScheduler,
        embeddedOrigin = HelpCenterEnchantmentAcquisitionMessage::originServer,
        replay = RedisReplayPolicy(HelpCenterEnchantmentAcquisitionMessage::id, 60_000L, 4096),
        clockMillis = nowMillis,
        onMessage = ::receive,
        onRejected = {},
        onReplyRejected = {},
        onHandlerFailure = { report("redis-handler", it) },
    )

    /** Null means there is no fresh authoritative answer; callers retain their local facts. */
    fun describe(topicId: String): CompletableFuture<HelpCenterEnchantmentAcquisition?> {
        val now = nowMillis()
        if (closed.get() || isSourceServer || !HelpCenterEnchantmentAcquisitionWire.isTopic(topicId) || now < 0L) {
            return CompletableFuture.completedFuture(null)
        }
        val request = HelpCenterEnchantmentAcquisitionMessage(
            id = UUID.randomUUID().toString(), type = "QUERY", originServer = serverId,
            targetServer = HelpCenterEnchantmentAcquisitionWire.SOURCE_SERVER, topicId = topicId, createdAtMillis = now,
        )
        val result = CompletableFuture<HelpCenterEnchantmentAcquisition?>()
        synchronized(callers) {
            if (closed.get() || callers.size >= MAX_CALLERS) return CompletableFuture.completedFuture(null)
            callers += result
        }
        result.whenComplete { _, _ -> synchronized(callers) { callers.remove(result) } }
        try {
            val task = lifecycle.runAsync(token) {
                if (closed.get() || !HelpCenterEnchantmentAcquisitionWire.isFresh(request.createdAtMillis, nowMillis())) {
                    result.complete(null)
                    return@runAsync
                }
                channel.request(request).whenComplete { reply, failure ->
                    val acquisition = if (!closed.get() && failure == null && reply is RedisRequestResult.Reply &&
                        HelpCenterEnchantmentAcquisitionWire.isFresh(request.createdAtMillis, nowMillis())) {
                        if (failures.remove("request")) log.info("HELP_ACQUISITION_NETWORK phase=request server={} recovered", serverId)
                        HelpCenterEnchantmentAcquisition(reply.message.facts, reply.message.relatedTopics)
                    } else {
                        if (failure != null) report("request", failure)
                        if (reply is RedisRequestResult.InfrastructureFailure) report("request", reply.cause)
                        null
                    }
                    result.complete(acquisition)
                }
            }
            if (task == null) result.complete(null)
        } catch (failure: RuntimeException) {
            report("dispatch-request", failure)
            result.complete(null)
        }
        return result
    }

    private fun receive(message: HelpCenterEnchantmentAcquisitionMessage, origin: String) {
        if (closed.get() || !HelpCenterEnchantmentAcquisitionWire.isInboundQuery(message, origin, serverId, nowMillis())) return
        lifecycle.callSync(token) {
            if (closed.get() || !HelpCenterEnchantmentAcquisitionWire.isInboundQuery(message, origin, serverId, nowMillis())) {
                CompletableFuture.completedFuture<HelpCenterEnchantmentAcquisition?>(null)
            } else describeLocal(message.topicId).thenApply<HelpCenterEnchantmentAcquisition?> { it }
        }.thenCompose { it }.whenComplete { acquisition, failure ->
            if (closed.get()) return@whenComplete
            if (failure != null) {
                report("source-snapshot", failure)
                return@whenComplete
            }
            if (acquisition == null) return@whenComplete
            val reply = message.copy(
                id = UUID.randomUUID().toString(), replyTo = message.id, type = "RESULT",
                originServer = serverId, targetServer = message.originServer,
                facts = acquisition.facts, relatedTopics = acquisition.relatedTopics,
            )
            try {
                lifecycle.runAsync(token) {
                    if (!closed.get() && HelpCenterEnchantmentAcquisitionWire.isFresh(message.createdAtMillis, nowMillis())) {
                        try { channel.publish(reply) }
                        catch (publishFailure: RuntimeException) { report("publish-reply", publishFailure) }
                    }
                }
            } catch (dispatchFailure: RuntimeException) { report("dispatch-reply", dispatchFailure) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { channel.close() }
        finally {
            try { lifecycle.close() }
            finally {
                val abandoned = synchronized(callers) { callers.toList().also { callers.clear() } }
                abandoned.forEach { it.complete(null) }
            }
        }
    }

    private fun report(phase: String, failure: Throwable) {
        if (!closed.get() && failures.add(phase)) {
            log.warn("HELP_ACQUISITION_NETWORK phase={} server={}", phase, serverId, failure)
        }
    }

    private companion object { const val MAX_CALLERS = 32 }
}

/** Closed, bounded protocol carrying only case/quest acquisition facts. Backend ids stay code-only. */
internal object HelpCenterEnchantmentAcquisitionWire {
    const val CHANNEL = "arc:help:enchantment-acquisition:v1"
    const val SOURCE_SERVER = "spawn"
    const val TTL_MILLIS = 10_000L
    private const val MAX_FACTS = 2048
    private const val MAX_TEXT = 256
    private const val MAX_PAYLOAD_BYTES = 256 * 1024
    private val chanceFormat = Regex("(?:0|[1-9][0-9]{0,2})(?:,[0-9]{1,4})?")
    private val factValueFields = setOf("source", "item", "amount", "crate", "chance", "quest")
    private val valuesContract = object : JsonRootContract {
        private val fields = JsonObjectContract(factValueFields, emptySet(), emptyMap())
        override fun validate(value: com.google.gson.JsonElement) {
            fields.validate(value)
            require(value.asJsonObject.entrySet().all { (_, entry) -> entry.isJsonPrimitive && entry.asJsonPrimitive.isString }) {
                "Acquisition metadata must contain scalar strings"
            }
        }
    }
    private val factContract = JsonObjectContract(
        allowedFields = setOf("key", "values"), requiredFields = setOf("key", "values"),
        fieldContracts = mapOf("values" to valuesContract),
    )
    private val json = BoundedJsonCodec(
        gson = Gson(), type = HelpCenterEnchantmentAcquisitionMessage::class.java,
        rootContract = JsonObjectContract(
            allowedFields = setOf("id", "replyTo", "type", "originServer", "targetServer", "topicId", "createdAtMillis", "facts", "relatedTopics"),
            requiredFields = setOf("id", "type", "originServer", "targetServer", "topicId", "createdAtMillis", "facts", "relatedTopics"),
            fieldContracts = mapOf(
                "facts" to JsonArrayContract(0, MAX_FACTS, factContract),
                "relatedTopics" to JsonArrayContract(0, HelpCenterEnchantmentGuideTopic.entries.size, null),
            ),
        ),
        bounds = JsonResourceBounds(128 * 1024, 6, MAX_FACTS, 20_000, MAX_TEXT),
        validate = ::validate,
    )
    val codec: RedisWireCodec<HelpCenterEnchantmentAcquisitionMessage> = object : RedisWireCodec<HelpCenterEnchantmentAcquisitionMessage> {
        override fun encode(value: HelpCenterEnchantmentAcquisitionMessage): String = json.encode(value).also(::boundBytes)
        override fun decode(raw: String): HelpCenterEnchantmentAcquisitionMessage { boundBytes(raw); return json.decode(raw) }
    }

    fun isTopic(topic: String): Boolean = topic == "overview" || topic in HelpCenterEnchantmentGuideTopic.byId

    fun isFresh(createdAt: Long, now: Long): Boolean = createdAt >= 0L && now >= createdAt && now - createdAt <= TTL_MILLIS

    fun isInboundQuery(message: HelpCenterEnchantmentAcquisitionMessage, origin: String, local: String, now: Long): Boolean =
        local == SOURCE_SERVER && message.type == "QUERY" && message.replyTo == null &&
            message.originServer == origin && message.originServer != local && message.targetServer == local &&
            isFresh(message.createdAtMillis, now)

    fun isExactReply(
        request: HelpCenterEnchantmentAcquisitionMessage, reply: HelpCenterEnchantmentAcquisitionMessage, origin: String, now: Long,
    ): Boolean = request.type == "QUERY" && request.replyTo == null && reply.type == "RESULT" &&
        reply.replyTo == request.id && reply.id != request.id && origin == SOURCE_SERVER &&
        reply.originServer == request.targetServer && reply.targetServer == request.originServer &&
        reply.topicId == request.topicId && reply.createdAtMillis == request.createdAtMillis && isFresh(request.createdAtMillis, now)

    private fun validate(message: HelpCenterEnchantmentAcquisitionMessage) {
        require(uuid(message.id) && (message.replyTo == null || uuid(message.replyTo))) { "Invalid acquisition correlation id" }
        require(BackendServerId.parseOrNull(message.originServer) != null && BackendServerId.parseOrNull(message.targetServer) != null &&
            message.originServer != message.targetServer) { "Invalid acquisition backend ids" }
        require(isTopic(message.topicId) && message.createdAtMillis >= 0L) { "Invalid acquisition topic or time" }
        require(message.facts.size <= MAX_FACTS && message.relatedTopics.size <= HelpCenterEnchantmentGuideTopic.entries.size) { "Too many acquisition facts" }
        when (message.type) {
            "QUERY" -> require(message.replyTo == null && message.targetServer == SOURCE_SERVER &&
                message.facts.isEmpty() && message.relatedTopics.isEmpty()) { "Invalid acquisition query" }
            "RESULT" -> require(message.replyTo != null && message.originServer == SOURCE_SERVER) { "Invalid acquisition result" }
            else -> error("Unknown acquisition message type")
        }
        message.facts.forEach { fact ->
            val fields = when (fact.key) {
                "crate-source" -> setOf("source", "item", "amount", "crate", "chance")
                "quest-source" -> setOf("source", "item", "amount", "quest")
                "unavailable" -> setOf("source")
                else -> error("Unexpected external acquisition fact")
            }
            require(fact.values.keys == fields && fact.values.values.all(::safeText)) { "Invalid acquisition fact values" }
            fact.values["item"]?.let { item ->
                require(if (message.topicId == "overview") item in HelpCenterEnchantmentSources.boxTopics else item == message.topicId) { "Acquisition fact topic mismatch" }
            }
            fact.values["amount"]?.let { amount ->
                val range = amount.split('–')
                val parsed = range.map { it.toIntOrNull() }
                require(range.size in 1..2 && parsed.all { it != null && it > 0 } &&
                    range.zip(parsed).all { (text, value) -> text == value.toString() } && parsed.first()!! <= parsed.last()!!) { "Invalid acquisition amount" }
            }
            fact.values["chance"]?.let { chance ->
                val value = chance.replace(',', '.').toDoubleOrNull()
                require(chanceFormat.matches(chance) &&
                    value != null && value.isFinite() && value in 0.0..100.0) { "Invalid acquisition chance" }
            }
        }
        require(message.relatedTopics.distinct().size == message.relatedTopics.size && message.relatedTopics.all { it in HelpCenterEnchantmentGuideTopic.byId }) { "Invalid acquisition related topics" }
        require(if (message.topicId == "overview") {
            message.relatedTopics.toSet() == message.facts.mapNotNull { it.values["item"] }.toSet()
        } else message.relatedTopics.isEmpty()) { "External acquisition links do not match facts" }
    }

    private fun safeText(value: String): Boolean = value.length in 1..MAX_TEXT && value.isNotBlank() && value.none(Char::isISOControl)
    private fun uuid(value: String): Boolean = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun boundBytes(raw: String) { require(raw.length <= MAX_PAYLOAD_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Acquisition payload exceeds its byte limit" } }
}
