package ru.arc.parkour

import io.github.a5h73y.parkour.Parkour
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope

interface ArcParkourGateway {
    fun readyCourses(player: Player): List<ParkourCourseSnapshot>

    fun join(player: Player, courseId: String): Boolean

    fun activeRun(player: Player): ActiveParkourRun?
}

data class ActiveParkourRun(
    val courseId: String,
    val checkpoint: Int,
    val totalCheckpoints: Int,
    val deaths: Int,
)

class NativeParkourGateway(
    private val parkour: Parkour,
) : ArcParkourGateway {
    override fun readyCourses(player: Player): List<ParkourCourseSnapshot> =
        parkour.courseManager.courseNames.mapNotNull { courseId ->
            val config = parkour.configManager.getCourseConfig(courseId)
            if (!config.readyStatus) return@mapNotNull null
            ParkourCourseSnapshot(
                id = courseId,
                checkpoints = config.checkpointAmount,
                players = parkour.parkourSessionManager.getNumberOfPlayersOnCourse(courseId),
                completed = parkour.configManager.courseCompletionsConfig.hasCompletedCourse(player, courseId),
            )
        }

    override fun join(player: Player, courseId: String): Boolean =
        parkour.playerManager.joinCourse(player, courseId)

    override fun activeRun(player: Player): ActiveParkourRun? {
        val session = parkour.parkourSessionManager.getParkourSession(player) ?: return null
        return ActiveParkourRun(
            courseId = session.courseName,
            checkpoint = session.currentCheckpoint,
            totalCheckpoints = session.course.numberOfCheckpoints,
            deaths = session.deaths,
        )
    }
}

/** Loaded only after Bukkit confirms that the optional Parkour plugin is enabled. */
internal object NativeParkourIntegration {
    fun listeners(
        plugin: Plugin,
        settings: ArcParkourSettings,
        tasks: LifecycleTaskScope,
    ): List<Listener> {
        val parkour = plugin as? Parkour ?: error("plugin named Parkour does not expose the expected API")
        val gateway = NativeParkourGateway(parkour)
        return listOf(
            ArcParkourMenuController(settings, gateway, tasks),
            ArcParkourHudListener(settings, gateway, tasks),
        )
    }
}
