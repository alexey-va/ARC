package ru.arc.lands

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.player.OfflinePlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Normalizes the generic signatures stripped from the obfuscated Lands runtime JAR. */
internal fun LandPlayer.currentLands(): Sequence<Land> =
    lands.asSequence().filterIsInstance<Land>()

internal fun Land.trustedPlayerIds(): Set<UUID> =
    trustedPlayers.asSequence().filterIsInstance<UUID>().toSet()

@Suppress("UNCHECKED_CAST")
internal fun LandsIntegration.offlineLandPlayer(playerId: UUID): CompletableFuture<OfflinePlayer?> =
    getOfflineLandPlayer(playerId) as CompletableFuture<OfflinePlayer?>
