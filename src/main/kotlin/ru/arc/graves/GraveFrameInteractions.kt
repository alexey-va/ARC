package ru.arc.graves

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Routes only our viewer's fake frame IDs; ordinary Yamipa images are never intercepted. */
internal class GraveFrameInteractions(
    private val plugin: Plugin,
    private val graveForFrame: (Player, Int) -> UUID?,
    private val interact: (Player, UUID) -> Unit,
    private val attack: (Player, UUID) -> Unit,
) : AutoCloseable {
    @Volatile private var closed = false
    private val listener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        override fun onPacketReceive(event: PacketReceiveEvent) {
            if (closed) return
            val player = event.getPlayer<Any>() as? Player ?: return
            val id: Int
            val rightClick: Boolean
            val leftClick: Boolean
            when (event.packetType) {
                PacketType.Play.Client.INTERACT_ENTITY -> {
                    val packet = WrapperPlayClientInteractEntity(event)
                    id = packet.entityId
                    leftClick = packet.action == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                    rightClick = !leftClick && packet.hand == InteractionHand.MAIN_HAND
                }
                PacketType.Play.Client.ATTACK -> {
                    id = WrapperPlayClientAttack(event).entityId
                    rightClick = false
                    leftClick = true
                }
                else -> return
            }
            val grave = graveForFrame(player, id) ?: return
            val alreadyDenied = event.isCancelled
            event.isCancelled = true
            if (alreadyDenied || (!rightClick && !leftClick)) return
            try {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (!closed && graveForFrame(player, id) == grave) {
                        if (leftClick) attack(player, grave) else interact(player, grave)
                    }
                })
            } catch (_: IllegalPluginAccessException) {
                // A disable racing this packet makes its queued click irrelevant.
            }
        }
    }

    init { PacketEvents.getAPI().eventManager.registerListener(listener) }

    override fun close() {
        if (closed) return
        closed = true
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
    }
}
