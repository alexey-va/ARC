package ru.arc.onboarding

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.ruscrafting.onboarding.api.PrimisQuestGuidanceService

/** Uses the same persisted Origin entrance anchors and native Primis quest authority. */
internal class OptionalQuestGuidance(private val config: OptionalQuestConfig) : AutoCloseable {
    private val displays = StoryQuestWaypointHud(ARC.instance)
    private val origin = Config(ARC.instance.dataPath, ConfigManager.moduleYamlRelative(ARC.instance.dataPath, "origin-spawn.yml"))

    fun refresh(player: Player, view: OptionalQuestView?) {
        val step = view?.let(::optionalQuestNextStep)
        if (view == null || step == null) { displays.clear(player); return }
        val target = when (view.kind) {
            OptionalQuestKind.ADVENTURE -> adventure(player, step)
            OptionalQuestKind.CASINO -> if (player.world.name != "rc_atelier_casino") entrance("casino") else {
                config.areas[step]?.let { area -> Target(area.world, (area.minX + area.maxX) / 2,
                    area.minY + 1, (area.minZ + area.maxZ) / 2, 0f, "Подойдите сюда", 1.2, 2.4) }
            }
            OptionalQuestKind.FARM -> if (player.world.name != config.areas[OptionalQuestStep.FARM_VISITED]?.world)
                entrance("farm") else null
        }
        val world = target?.let { Bukkit.getWorld(it.world) }
        val location = if (target != null && world != null) Location(world, target.x, target.y, target.z, target.yaw, 0f) else null
        displays.refresh(player, location, Component.text(target?.instruction.orEmpty(), TextColor.color(0x77EE94)),
            target?.width ?: 6.0, target?.height ?: 8.4)
    }

    private fun adventure(player: Player, step: OptionalQuestStep): Target? = when (player.world.name) {
        "rc_origin_spawn" -> entrance("adventure_guild")
        "em_adventurers_guild" -> Target("em_adventurers_guild", 293.5, 95.0, 290.5, 90f,
            "Войдите в портал Примиса", 3.0, 4.0)
        "em_primis" -> if (step == OptionalQuestStep.PRIMIS_INTRO_COMPLETE) {
            when (Bukkit.getServicesManager().load(PrimisQuestGuidanceService::class.java)?.stage(player.uniqueId)) {
                PrimisQuestGuidanceService.Stage.NOT_ACCEPTED -> Target("em_primis", 1398.64, 19.0, 372.32, -124.84f,
                    "Возьмите задание у капитана", 1.2, 2.4)
                PrimisQuestGuidanceService.Stage.ACTIVE_OBJECTIVE -> Target("em_primis", 1240.21, 37.5, 308.5, 90.36f,
                    "Поговорите с главой деревни", 1.2, 2.4)
                PrimisQuestGuidanceService.Stage.READY_TO_TURN_IN -> Target("em_primis", 1236.5, 37.0, 310.83, -126.09f,
                    "Сдайте задание первому помощнику", 1.2, 2.4)
                else -> null
            }
        } else null
        else -> null
    }

    private fun entrance(id: String): Target {
        val prefix = "origin-portals.anchors.$id"
        return Target(origin.string("$prefix.world", "rc_origin_spawn"), origin.double("$prefix.x", 0.0),
            origin.double("$prefix.y", 71.0), origin.double("$prefix.z", 0.0),
            origin.double("$prefix.yaw", 0.0).toFloat(), "Войдите в портал", origin.double("$prefix.width", 6.0), origin.double("$prefix.height", 8.4))
    }

    override fun close() = displays.close()
    fun clear(playerId: java.util.UUID) = displays.clear(playerId)

    private data class Target(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float,
        val instruction: String, val width: Double, val height: Double)
}
