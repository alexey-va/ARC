package ru.arc.xserver

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.Tasks
import ru.arc.rtp.BackendRtpRequest
import ru.arc.rtp.FirstRtpCoordinator
import ru.arc.rtp.FirstRtpRouteResult
import ru.arc.rtp.FirstRtpResult
import ru.arc.rtp.NetworkRtpMode
import ru.arc.rtp.NetworkRtpRequest
import ru.arc.rtp.RegularRtpService
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Locale

class PluginMessenger : PluginMessageListener {

    init {
        Bukkit.getServer().messenger.registerOutgoingPluginChannel(ARC.instance, "BungeeCord")
        Bukkit.getServer().messenger.registerOutgoingPluginChannel(
            ARC.instance,
            BackendRtpRequest.CHANNEL,
        )
        Bukkit.getServer().messenger.registerIncomingPluginChannel(
            ARC.instance,
            NetworkRtpRequest.CHANNEL,
            this,
        )
    }

    override fun onPluginMessageReceived(
        channel: String,
        player: Player,
        bytes: ByteArray,
    ) {
        if (channel != NetworkRtpRequest.CHANNEL) return
        val request =
            runCatching { NetworkRtpRequest.decode(bytes) }
                .getOrElse { failure ->
                    warn("Rejected malformed network RTP request for {}: {}", player.name, failure.message)
                    return
                }

        if (request.playerId != player.uniqueId) {
            warn(
                "Rejected network RTP request {}: carrier {} does not match requested player {}",
                request.requestId,
                player.uniqueId,
                request.playerId,
            )
            return
        }
        val currentServer = ARC.serverName?.trim()?.lowercase(Locale.ROOT)
        if (currentServer != request.targetServer) {
            warn(
                "Rejected network RTP request {} for server {} on {}",
                request.requestId,
                request.targetServer,
                currentServer,
            )
            return
        }

        val run = Runnable { handleNetworkRtp(player, request) }
        if (Bukkit.isPrimaryThread()) run.run() else Tasks.scheduler.runSync(run)
    }

    private fun handleNetworkRtp(
        player: Player,
        request: NetworkRtpRequest,
    ) {
        if (!player.isOnline) return
        val config = ConfigManager.of(ARC.instance.dataPath, "misc.yml")
        val allowedWorlds =
            config
                .stringList("rtp-respawn.allowed-worlds", listOf("survival", "mining", "vanilla"))
                .map { it.trim().lowercase(Locale.ROOT) }
                .toSet()
        val worldName = resolveNetworkRtpWorld(request.worldName, player.world.name)
        if (worldName !in allowedWorlds) {
            player.sendMessage(TextUtil.mm("<red>Для этого мира RTP недоступен."))
            warn("Rejected network RTP request {} for disallowed world {}", request.requestId, worldName)
            return
        }
        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            player.sendMessage(TextUtil.mm("<red>Мир временно недоступен. Попробуйте позже."))
            warn("Rejected network RTP request {} because world {} is not loaded", request.requestId, worldName)
            return
        }

        when (request.mode) {
            NetworkRtpMode.FIRST_ENTRY -> handleFirstEntryRtp(player, request, world, worldName)
            NetworkRtpMode.REGULAR -> handleRegularRtp(player, request, world, worldName)
        }
    }

    private fun handleFirstEntryRtp(
        player: Player,
        request: NetworkRtpRequest,
        world: org.bukkit.World,
        worldName: String,
    ) {
        val result = FirstRtpCoordinator.route(player, world)
        when (result) {
            FirstRtpRouteResult.ReturnedToWorld -> {
                info(
                    "Network first-entry request {} returned {} to {} without RTP",
                    request.requestId,
                    player.name,
                    worldName,
                )
            }

            is FirstRtpRouteResult.Started ->
                info(
                    "Network RTP request {} started for {} in {} through {}",
                    request.requestId,
                    player.name,
                    worldName,
                    result.result.provider,
                )

            is FirstRtpRouteResult.Rejected -> {
                warn(
                    "Network RTP request {} rejected for {}: {}",
                    request.requestId,
                    player.name,
                    result.reason,
                )
            }
        }
        firstEntryPlayerMessage(result)?.let { player.sendMessage(it) }
    }

    private fun handleRegularRtp(
        player: Player,
        request: NetworkRtpRequest,
        world: org.bukkit.World,
        worldName: String,
    ) {
        when (val result = RegularRtpService.start(player, world)) {
            is FirstRtpResult.Started ->
                info(
                    "Regular network RTP request {} started for {} in {} through {}",
                    request.requestId,
                    player.name,
                    worldName,
                    result.provider,
                )

            is FirstRtpResult.Rejected -> {
                player.sendMessage(TextUtil.mm("<red>Не удалось запустить RTP: <white>${result.reason}"))
                warn(
                    "Regular network RTP request {} rejected for {}: {}",
                    request.requestId,
                    player.name,
                    result.reason,
                )
            }
        }
    }

    fun shutdown() {
        Bukkit.getServer().messenger.unregisterIncomingPluginChannel(
            ARC.instance,
            NetworkRtpRequest.CHANNEL,
            this,
        )
        Bukkit.getServer().messenger.unregisterOutgoingPluginChannel(
            ARC.instance,
            BackendRtpRequest.CHANNEL,
        )
        Bukkit.getServer().messenger.unregisterOutgoingPluginChannel(ARC.instance, "BungeeCord")
    }

    fun sendBackendRtpRequest(
        player: Player,
        request: BackendRtpRequest,
    ) {
        player.sendPluginMessage(
            ARC.instance,
            BackendRtpRequest.CHANNEL,
            request.encode(),
        )
    }

    fun sendBungeeCord(player: Player, bytes: ByteArray) {
        player.sendPluginMessage(ARC.instance, "BungeeCord", bytes)
    }

    fun sendPlayerToServer(player: Player, server: String) {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        try {
            out.writeUTF("Connect")
            out.writeUTF(server)
        } catch (e: Exception) {
            error("Error in sendPlayerToServer", e)
            return
        }
        sendBungeeCord(player, bytes.toByteArray())
    }
}

internal fun resolveNetworkRtpWorld(
    requestedWorld: String,
    playerWorld: String,
): String =
    if (requestedWorld == NetworkRtpRequest.CURRENT_WORLD) {
        playerWorld.trim().lowercase(Locale.ROOT)
    } else {
        requestedWorld
    }

internal fun firstEntryPlayerMessage(result: FirstRtpRouteResult): Component? =
    when (result) {
        FirstRtpRouteResult.ReturnedToWorld,
        is FirstRtpRouteResult.Started,
        -> null

        is FirstRtpRouteResult.Rejected ->
            TextUtil.mm("<red>Не удалось запустить RTP: <white>${result.reason}")
    }
