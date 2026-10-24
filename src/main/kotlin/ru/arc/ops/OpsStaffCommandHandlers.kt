package ru.arc.ops

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import org.bukkit.Bukkit
import org.slf4j.LoggerFactory
import ru.arc.core.Tasks
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.spy.CrossServerSpyModule
import ru.arc.spy.SpyRelayPolicy
import ru.arc.spy.StaffConsoleObservationCodec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class StaffCommandFailure(val status: Int, message: String) : RuntimeException(message)

internal data class StaffCommandRequest(val requestId: UUID, val actorUuid: UUID, val command: String)

/**
 * Trusted-proxy HTTP boundary. LP loading/waiting stays on the HTTP worker; all command and CMI
 * access runs on Paper main. Admission binds an id to its actor/command before scheduling.
 * Loaded LP users remain retained through terminal completion and are rechecked at the sink;
 * cleanup runs off main only after the last overlapping request for that user has finished.
 * This process-local result journal deliberately retains unknown outcomes: the core id-only
 * replay guard cannot atomically retain a fingerprint and a terminal command result.
 * It is bounded to 1024 entries and ten minutes after admission (pending entries never expire).
 * Restart loses this journal: an unknown outcome across a process epoch requires investigation.
 */
class OpsStaffCommandHandlers(
    private val luckPerms: () -> LuckPerms = { LuckPermsProvider.get() },
    private val now: () -> Long = System::currentTimeMillis,
    private val configProvider: () -> OpsHttpConfig = OpsHttpConfig::current,
) {
    private data class Entry(
        val fingerprint: OneTimeUseFingerprint,
        val expiresAt: Long,
        val generation: Long,
        val result: CompletableFuture<Map<String, Any?>> = CompletableFuture(),
        var started: Boolean = false,
    )

    private class HeldUser(val api: LuckPerms, var references: Int = 0, var user: User? = null)
    private class Authorization(val actor: UUID, val held: HeldUser, val user: User) {
        val released = AtomicBoolean(false)
    }

    private val entries = LinkedHashMap<UUID, Entry>()
    private val heldUsers = mutableMapOf<UUID, HeldUser>()
    @Volatile private var accepting = true
    private var generation = 0L
    private val log = LoggerFactory.getLogger(OpsStaffCommandHandlers::class.java)

    fun activate() = synchronized(entries) { accepting = true }

    /** Invalidates queued main-thread work before sockets/executors stop; replay reservations remain. */
    fun invalidate() = synchronized(entries) {
        accepting = false
        generation++
        entries.values.filter { !it.started && !it.result.isDone }.forEach {
            it.result.completeExceptionally(StaffCommandFailure(503, "Staff command lifecycle changed; command was not dispatched"))
        }
    }

    fun execute(raw: String, config: OpsHttpConfig): Map<String, Any?> {
        if (!config.staffCommandsEnabled) throw StaffCommandFailure(403, "Staff command endpoint disabled")
        if (!accepting) throw StaffCommandFailure(503, "Staff command lifecycle unavailable")
        val request = parse(raw)
        if (!SpyRelayPolicy.shouldPublishCommand(request.command, false, emptyList(), emptyList())) {
            throw StaffCommandFailure(403, "Sensitive commands and command-execution wrappers are unsupported")
        }
        if (Bukkit.isPrimaryThread()) throw StaffCommandFailure(503, "Staff authorization requires an HTTP worker")
        val authorization = authorize(request.actorUuid, config.staffCommandAdminGroups)
        var pending: CompletableFuture<Map<String, Any?>>? = null
        try {
            val fingerprint = OneTimeUseFingerprint.sha256Fields(request.actorUuid.toString(), request.command)
            var admitted = false
            val entry = synchronized(entries) {
                if (!accepting) throw StaffCommandFailure(503, "Staff command lifecycle unavailable")
                val timestamp = now()
                entries.entries.removeIf { it.value.result.isDone && it.value.expiresAt <= timestamp }
                entries[request.requestId]?.also {
                    if (it.fingerprint != fingerprint) throw StaffCommandFailure(409, "requestId conflicts with another command")
                } ?: run {
                    if (entries.size >= MAX_ENTRIES) throw StaffCommandFailure(503, "Staff command capacity reached")
                    Entry(fingerprint, timestamp + RETENTION_MILLIS, generation).also {
                        entries[request.requestId] = it
                        admitted = true
                    }
                }
            }
            pending = entry.result
            if (admitted) schedule(request, entry, authorization)
            return try {
                entry.result.get(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                throw StaffCommandFailure(503, "Command outcome unknown; retry the same requestId")
            } catch (failure: ExecutionException) {
                throw failure.cause as? StaffCommandFailure
                    ?: StaffCommandFailure(503, "Command outcome unknown; retry the same requestId")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw StaffCommandFailure(503, "Command outcome unknown; retry the same requestId")
            }
        } finally {
            val result = pending
            if (result == null || result.isDone) release(authorization)
            else result.whenComplete { _, _ ->
                // A timed-out HTTP worker no longer owns completion; never clean LP users on Paper main.
                runCatching { Tasks.scheduler.runAsync(Runnable { release(authorization) }) }
                    .onFailure { log.warn("Staff authorization cleanup scheduling unavailable actor={}", authorization.actor) }
            }
        }
    }

    private fun authorize(actorUuid: UUID, groups: Set<String>): Authorization {
        if (groups.isEmpty()) throw StaffCommandFailure(503, "Staff authorization unavailable")
        var held: HeldUser? = null
        try {
            val api = luckPerms()
            val acquired = synchronized(heldUsers) {
                heldUsers.getOrPut(actorUuid) { HeldUser(api) }.also { it.references++ }
            }
            held = acquired
            val user = api.userManager.loadUser(actorUuid).get(5, TimeUnit.SECONDS)
            if (user.uniqueId != actorUuid) throw StaffCommandFailure(403, "Staff administrator authorization required")
            synchronized(heldUsers) { acquired.user = user }
            val authorization = Authorization(actorUuid, acquired, user)
            if (!isAdmin(authorization, groups)) throw StaffCommandFailure(403, "Staff administrator authorization required")
            return authorization
        } catch (failure: StaffCommandFailure) {
            held?.let { releaseHeld(actorUuid, it) }
            throw failure
        } catch (_: InterruptedException) {
            held?.let { releaseHeld(actorUuid, it) }
            Thread.currentThread().interrupt()
            throw StaffCommandFailure(503, "Staff authorization unavailable")
        } catch (_: LinkageError) {
            held?.let { releaseHeld(actorUuid, it) }
            throw StaffCommandFailure(503, "Staff authorization unavailable")
        } catch (_: Exception) {
            held?.let { releaseHeld(actorUuid, it) }
            log.warn("Staff command authorization unavailable actor={}", actorUuid)
            throw StaffCommandFailure(503, "Staff authorization unavailable")
        }
    }

    private fun isAdmin(authorization: Authorization, groups: Set<String>): Boolean =
        authorization.user.getInheritedGroups(authorization.held.api.contextManager.staticQueryOptions)
            .any { it.name.lowercase(java.util.Locale.ROOT) in groups }

    private fun release(authorization: Authorization) {
        if (authorization.released.compareAndSet(false, true)) releaseHeld(authorization.actor, authorization.held)
    }

    private fun releaseHeld(actor: UUID, held: HeldUser) = synchronized(heldUsers) {
        held.references--
        if (held.references == 0) {
            heldUsers.remove(actor)
            // Serialize final cleanup with admission so one duplicate cannot unload another retained user.
            held.user?.let { user ->
                runCatching { held.api.userManager.cleanupUser(user) }
                    .onFailure { log.warn("Staff authorization cleanup unavailable actor={}", actor) }
            }
        }
    }

    private fun schedule(request: StaffCommandRequest, entry: Entry, authorization: Authorization) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISPATCH_TIMEOUT_SECONDS)
        try {
            Tasks.scheduler.runSync(Runnable {
                try {
                    check(Bukkit.isPrimaryThread())
                    if (System.nanoTime() > deadline) throw StaffCommandFailure(503, "Command was not dispatched before timeout")
                    val command = request.command.trim().removePrefix("/")
                    if (!CrossServerSpyModule.staffCommandAllowed(command)) {
                        throw StaffCommandFailure(403, "Sensitive, blacklisted, aliased execution wrappers or unavailable observation are unsupported")
                    }
                    val output = StaffCommandOutput()
                    val sender = Bukkit.createCommandSender(output::accept)
                    synchronized(entries) {
                        val config = configProvider()
                        if (!accepting || generation != entry.generation || entry.result.isDone || !config.staffCommandsEnabled) {
                            throw StaffCommandFailure(503, "Staff command lifecycle changed; command was not dispatched")
                        }
                        if (!isAdmin(authorization, config.staffCommandAdminGroups)) {
                            throw StaffCommandFailure(403, "Staff administrator authorization required")
                        }
                        entry.started = true
                    }
                    // Bukkit.dispatchCommand does not emit the native console/RCON events.
                    CrossServerSpyModule.publishStaffCommand(request.requestId, request.actorUuid, command)
                    val dispatched = Bukkit.dispatchCommand(sender, command)
                    entry.result.complete(mapOf(
                        "requestId" to request.requestId.toString(), "dispatched" to dispatched, "output" to output.snapshot(),
                    ))
                    log.info("Staff command completed requestId={} actor={} dispatched={}", request.requestId, request.actorUuid, dispatched)
                } catch (failure: StaffCommandFailure) {
                    entry.result.completeExceptionally(failure)
                } catch (_: Exception) {
                    // Plugin exception text may contain the command or secret-bearing provider state.
                    log.warn("Staff command outcome unknown requestId={} actor={}", request.requestId, request.actorUuid)
                    entry.result.completeExceptionally(StaffCommandFailure(503, "Command outcome unknown; retry the same requestId"))
                }
            })
        } catch (_: Exception) {
            entry.result.completeExceptionally(StaffCommandFailure(503, "Staff command scheduler unavailable"))
        }
    }

    companion object {
        private const val MAX_ENTRIES = 1024
        private const val RETENTION_MILLIS = 10 * 60 * 1000L
        private const val DISPATCH_TIMEOUT_SECONDS = 15L
        private val codec = BoundedJsonCodec(
            Gson(), JsonObject::class.java, JsonObjectContract(setOf("requestId", "actorUuid", "command")),
            JsonResourceBounds(4096, 2, 3, 4, 512),
        ) { body ->
            listOf("requestId", "actorUuid", "command").forEach { key ->
                val value = body.get(key)
                require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString)
            }
            require(StaffConsoleObservationCodec.canonicalUuid(body.get("requestId").asString) != null)
            require(StaffConsoleObservationCodec.canonicalUuid(body.get("actorUuid").asString) != null)
            val command = body.get("command").asString
            require(command.length in 1..512 && command.trim().removePrefix("/").isNotBlank())
            require(command.none(StaffConsoleObservationCodec::unsafeLineCharacter))
        }

        internal fun parse(raw: String): StaffCommandRequest = try {
            codec.decode(raw).let { body ->
                StaffCommandRequest(UUID.fromString(body.get("requestId").asString), UUID.fromString(body.get("actorUuid").asString), body.get("command").asString)
            }
        } catch (_: Exception) {
            throw StaffCommandFailure(400, "Invalid staff command request")
        }
    }
}

/** Only direct native sender feedback is collected; global logs and exception text are never captured. */
internal class StaffCommandOutput {
    private val lines = mutableListOf<String>()
    private var characters = 0
    private var closed = false

    @Synchronized
    fun accept(component: Component) {
        if (closed || lines.size >= 20 || characters >= 2000) return
        val text = PlainTextComponentSerializer.plainText().serialize(component)
            .replace(ANSI_ESCAPE, "").replace(LEGACY_COLOR, "")
        text.lineSequence().forEach { raw ->
            if (lines.size >= 20 || characters >= 2000) return
            val line = raw.filterNot(StaffConsoleObservationCodec::unsafeLineCharacter).take(2000 - characters)
            if (line.isNotBlank()) {
                lines += line
                characters += line.length
            }
        }
    }

    @Synchronized
    fun snapshot(): List<String> {
        closed = true
        return lines.toList()
    }

    private companion object {
        val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        val LEGACY_COLOR = Regex("(?i)§[0-9a-fk-orx]")
    }
}
