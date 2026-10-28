package ru.arc.treasurechests

import org.bukkit.Bukkit
import ru.arc.aurora.api.ArcAuroraService
import java.util.UUID

/** Presence probe only; calls to the available service remain statically typed below. */
internal fun huntAuroraApiAvailable(loader: ClassLoader): Boolean =
    try {
        Class.forName("ru.arc.aurora.api.ArcAuroraService", false, loader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }

/** Loaded only with ArcAurora enabled, so its optional API is absent safely on other backends. */
internal object HuntAuroraEventAdapter {
    fun start(worldId: UUID): AutoCloseable? =
        Bukkit.getServicesManager().load(ArcAuroraService::class.java)
            ?.startWorldEvent(worldId, "treasure-hunt")
}
