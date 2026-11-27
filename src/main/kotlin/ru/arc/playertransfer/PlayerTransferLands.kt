package ru.arc.playertransfer

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import org.bukkit.Bukkit
import ru.arc.ARC
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal object PlayerTransferLands {
    private const val DISPLAY_LIMIT = 8
    private const val UNAVAILABLE = "Lands — плагин отсутствует или отключён."

    /** Reads the already-loaded Lands index from the calling Paper thread. */
    fun preview(source: UUID, target: UUID): CompletionStage<String> {
        if (!Bukkit.getPluginManager().isPluginEnabled("Lands")) {
            return CompletableFuture.completedFuture(UNAVAILABLE)
        }
        return try {
            CompletableFuture.completedFuture(EnabledLands.preview(source, target))
        } catch (failure: Exception) {
            CompletableFuture.failedFuture(failure)
        } catch (failure: LinkageError) {
            CompletableFuture.failedFuture(failure)
        }
    }

    private object EnabledLands {
        fun preview(source: UUID, target: UUID): String {
            val owned = LandsIntegration.of(ARC.instance).lands.asSequence().filterIsInstance<Land>()
                .filter { it.exists() && (it.ownerUID == source || it.ownerUID == target) }
                .groupBy { it.ownerUID }
            return "Lands (кеш владений): исходный — ${describeOwned(owned[source].orEmpty())} | " +
                "получатель — ${describeOwned(owned[target].orEmpty())}."
        }

        private fun describeOwned(owned: List<Land>): String {
            if (owned.isEmpty()) return "0"
            val details = owned.sortedBy { it.ulid.toString() }.take(DISPLAY_LIMIT).joinToString { land ->
                "«${transferDisplayLabel(land.name)}» (${land.ulid}, чанков ${land.chunksAmount})"
            }
            val remaining = owned.size - DISPLAY_LIMIT
            val suffix = if (remaining > 0) ", ещё $remaining" else ""
            return "${owned.size} [$details$suffix]"
        }
    }
}
