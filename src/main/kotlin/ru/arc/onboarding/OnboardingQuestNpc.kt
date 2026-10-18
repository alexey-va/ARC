package ru.arc.onboarding

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCDespawnEvent
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.event.NPCSpawnEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.packetevents.PacketEventsHook
import ru.arc.paper.display.PacketDisplayAudienceGroup
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SceneTextStyle
import java.lang.ref.WeakReference
import java.util.UUID

/** One gold quest cue, above the NPC's own label, with no world entity or persisted NPC change. */
internal fun onboardingQuestMarkerFrame(
    playerId: UUID,
    npcLocation: Location,
    scale: Float = 4f,
    height: Double = 2.65,
    range: Double = 40.0,
    audience: PacketDisplayAudienceGroup? = null,
): SceneFrame<String> {
    require(scale.isFinite() && scale in 0.5f..8f)
    require(height.isFinite() && height in 2.0..8.0)
    require(range.isFinite() && range in 8.0..128.0)
    val anchor = npcLocation.clone().add(0.0, height, 0.0).apply { yaw = 0f; pitch = 0f }
    return SceneFrame(
        listOf(ScenePart.Text(
            key = "quest_offer",
            pose = ScenePose(anchor, Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())),
            style = SceneDisplayStyle(
                billboard = Display.Billboard.CENTER,
                brightness = Display.Brightness(15, 15),
                viewRange = (range / 64.0).toFloat(),
                displayWidth = scale * 0.2f,
                displayHeight = scale * 0.3f,
            ),
            text = Component.text("!", TextColor.color(0xF4D87A)).decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false),
            textStyle = SceneTextStyle(lineWidth = 32, background = Color.fromARGB(0), shadowed = true),
        )),
        SceneAudience.Private(playerId, audience),
    )
}

/** The existing Citizens click remains untouched for every player outside this quest audience. */
internal class OnboardingQuestNpc(
    private val plugin: Plugin,
    private val npcId: Int,
    private val visible: (Player) -> Boolean,
    private val eligible: (Player) -> Boolean,
    private val open: (Player) -> Unit,
    private val scale: Float = 4f,
    private val height: Double = 2.65,
    private val range: Double = 40.0,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "onboarding-quest"),
) : Listener, AutoCloseable {
    private data class GlowTarget(val entity: WeakReference<LivingEntity>, val packets: PacketEventsHook)
    private val scenes = mutableMapOf<UUID, PacketDisplayScene<String>>()
    private val glowing = mutableMapOf<UUID, GlowTarget>()
    private val audience = displays.createAudienceGroup { eligible(it) && visible(it) }
    private var started = false
    private var closed = false

    fun start() {
        check(!closed)
        if (started) return
        started = true
        plugin.server.pluginManager.registerEvents(this, plugin)
        refreshAll()
    }

    /** Call after progress load, accept, restart and stop. Packet owner handles chunk/range replay. */
    fun refresh(player: Player) {
        if (closed) return
        val id = player.uniqueId
        if (!player.isOnline || !eligible(player) || !visible(player)) { clear(id); return }
        val npc = CitizensAPI.getNPCRegistry().getById(npcId)
        val entity = npc?.takeIf { it.isSpawned }?.entity
        val location = entity?.location
        if (location?.world == null || location.world != player.world) { clear(id); return }
        val scene = scenes.getOrPut(id) {
            PacketDisplayScene<String>(displays).also { fresh ->
                fresh.refreshWith {
                    plugin.server.getPlayer(id)?.let(::refresh) ?: clear(id)
                }
            }
        }
        scene.apply(onboardingQuestMarkerFrame(id, location, scale, height, range, audience))
        refreshGlow(player, entity as? LivingEntity)
    }

    private fun refreshGlow(player: Player, entity: LivingEntity?) {
        val packets = HookRegistry.packetEventsHook
        val previous = glowing[player.uniqueId]
        if (previous != null && (previous.entity.get() !== entity || previous.packets !== packets)) {
            clearGlow(player.uniqueId)
        }
        if (entity == null || packets == null) return
        // The shared owner reapplies the private flag to native metadata and handles disconnects.
        packets.setEntityGlowingFor(entity, player, true)
        glowing[player.uniqueId] = GlowTarget(WeakReference(entity), packets)
    }

    private fun clearGlow(playerId: UUID) {
        val target = glowing.remove(playerId) ?: return
        val entity = target.entity.get() ?: return
        val player = plugin.server.getPlayer(playerId) ?: return
        target.packets.restoreEntityGlowingFor(entity, player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onNpcClick(event: NPCRightClickEvent) {
        if (closed || event.npc.id != npcId || !eligible(event.clicker)) return
        // Citizens checks cancellation before dispatching its unchanged CommandTrait.
        event.isCancelled = true
        open(event.clicker)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNpcSpawn(event: NPCSpawnEvent) {
        if (!closed && event.npc.id == npcId) refreshAll()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNpcDespawn(event: NPCDespawnEvent) {
        if (event.npc.id == npcId) scenes.keys.toList().forEach(::clear)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) { refresh(event.player) }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { clear(event.player.uniqueId) }

    private fun refreshAll() { plugin.server.onlinePlayers.forEach(::refresh) }

    fun clear(playerId: UUID) {
        try { clearGlow(playerId) } finally { scenes.remove(playerId)?.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        try {
            glowing.keys.toList().forEach(::clearGlow)
        } finally {
            try {
                scenes.values.forEach { it.close() }
            } finally {
                glowing.clear()
                scenes.clear()
                try { audience.close() } finally { displays.close() }
            }
        }
    }
}
