package ru.arc.onboarding

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.util.Logging.warn
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Public packet API only. Packets carry no plugin owner: match Lands' configured border dust palette. */
internal class ClaimGuideParticles(private val colors: Set<Int>) : AutoCloseable {
    private val viewers = ConcurrentHashMap.newKeySet<UUID>()
    private val listener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.packetType != PacketType.Play.Server.PARTICLE || event.user.uuid !in viewers) return
            val particle = WrapperPlayServerParticle(event).particle
            if (particle.type != ParticleTypes.DUST) return
            val dust = particle.data as? ParticleDustData ?: return
            if (dust.color.asRGB() in colors) event.isCancelled = true
        }
    }

    init { PacketEvents.getAPI().eventManager.registerListener(listener) }

    fun holding(player: UUID, holding: Boolean) {
        if (holding) viewers.add(player) else viewers.remove(player)
    }

    override fun close() {
        viewers.clear()
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
    }

    companion object {
        fun create(): ClaimGuideParticles? {
            if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) return null
            val lands = Bukkit.getPluginManager().getPlugin("Lands") ?: return null
            val config = YamlConfiguration.loadConfiguration(File(lands.dataFolder, "config.yml"))
            val colors = claimGuideParticleColors(config)
            if (colors.isEmpty()) {
                warn("Claim guide: no Lands border dust palette found; particle filter disabled")
                return null
            }
            return ClaimGuideParticles(colors)
        }
    }
}

internal fun claimGuideParticleColors(config: ConfigurationSection): Set<Int> = buildSet {
    // Selection is deliberately excluded: creating a region is a separate tool mode.
    for (name in listOf("wilderness", "own", "trusted", "untrusted")) {
        val section = config.getConfigurationSection("visualization.type.$name") ?: continue
        val particle = section.getKeys(false).firstOrNull { it == "particle" || it.startsWith("particle_") }
            ?.let(section::getString)
        if (particle != "REDSTONE" && particle != "DUST") continue
        val color = section.getKeys(false).firstOrNull { it == "color" || it.startsWith("color_") }
            ?.let(section::getString)?.removePrefix("#")
        if (color?.length == 6) color.toIntOrNull(16)?.let(::add)
    }
}
