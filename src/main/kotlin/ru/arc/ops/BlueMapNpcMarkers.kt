package ru.arc.ops

import com.flowpowered.math.vector.Vector3d
import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.markers.POIMarker
import ru.arc.core.delayed
import ru.arc.core.ticks
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Publishes the existing Citizens registry as a dynamic BlueMap marker layer. */
object BlueMapNpcMarkers {
    private const val MARKER_SET_ID = "ruscrafting-citizens"
    private val started = AtomicBoolean(false)

    @Volatile
    private var api: BlueMapAPI? = null

    private val onEnable =
        Consumer<BlueMapAPI> { enabledApi ->
            api = enabledApi
            // Citizens finishes loading its persisted registry at the end of
            // server startup, after dependent plugins have been enabled.
            delayed(40.ticks) {
                refresh()
            }
        }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            BlueMapAPI.onEnable(onEnable)
            BlueMapAPI.onDisable { disabledApi ->
                if (api === disabledApi) api = null
            }
        }.onFailure {
            warn("BlueMap API unavailable; Citizens markers are disabled", it)
        }
    }

    fun available(): Boolean = api != null

    fun refresh() {
        val currentApi = api ?: return
        try {
            val npcsByWorld = OpsNpcHandlers.summariesByWorld()
            currentApi.maps.forEach { it.markerSets.remove(MARKER_SET_ID) }
            npcsByWorld.forEach { (world, npcs) ->
                val markerSet =
                    MarkerSet.builder()
                        .label("RusCrafting NPC")
                        .toggleable(true)
                        .defaultHidden(false)
                        .sorting(-100)
                        .build()
                npcs.forEach { npc ->
                    val location = npc.getStoredLocation() ?: return@forEach
                    val marker =
                        POIMarker(
                            "#${npc.id} ${npc.name}",
                            Vector3d(location.x, location.y, location.z),
                        )
                    marker.detail =
                        buildString {
                            append("<b>#")
                            append(npc.id)
                            append(" ")
                            append(escapeHtml(npc.name))
                            append("</b><br>")
                            append(escapeHtml(world.name))
                            append(" · ")
                            append("%.1f, %.1f, %.1f".format(location.x, location.y, location.z))
                        }
                    marker.maxDistance = 5000.0
                    markerSet.markers["citizens-${npc.id}"] = marker
                }
                currentApi.getWorld(world).ifPresent { blueMapWorld ->
                    blueMapWorld.maps.forEach { map -> map.markerSets[MARKER_SET_ID] = markerSet }
                }
            }
            info("Published {} Citizens NPC marker world(s) to BlueMap", npcsByWorld.size)
        } catch (t: Throwable) {
            warn("Failed to refresh Citizens markers in BlueMap", t)
        }
    }

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
