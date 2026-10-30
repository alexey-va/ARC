package ru.arc.onboarding

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.ScenePart
import java.util.UUID

class StoryQuestWaypointHudTest : StringSpec({
    "far destinations project to 24 metres and receive a direction trail without a false portal ring" {
        val world = mockk<World>()
        val from = Location(world, 0.0, 67.0, 0.0)
        val target = Location(world, 160.0, 67.0, 0.0, 90f, 0f)
        val projected = storyQuestWaypointLocation(from, target)
        projected.distance(from) shouldBe 24.0
        projected.x shouldBe 24.0
        projected.yaw shouldBe 90f
        storyQuestWaypointLocation(from.clone().add(10.0, 0.0, 0.0), target).x shouldBe 34.0
        storyQuestWaypointParticles(from, target, 6.0, 8.4, includeRing = false).size shouldBe 6
        val near = target.clone().apply { x = 48.0 }
        storyQuestWaypointLocation(from, near) shouldBe near
        target.x shouldBe 160.0
    }

    "waypoint stays private and its bounded outline follows the portal plane" {
        val viewer = UUID.randomUUID()
        val world = mockk<World>()
        val target = Location(world, 10.0, 67.0, 20.0, 90f, 0f)
        val frame = storyQuestWaypointFrame(viewer, target, Component.text("Войдите в портал"))
        frame.audience shouldBe SceneAudience.Private(viewer)
        frame.parts.size shouldBe 1
        val instruction = frame.parts.single() as ScenePart.Text<String>
        instruction.pose.location().x shouldBe 10.8
        instruction.pose.location().y shouldBe 70.2
        instruction.pose.location().z shouldBe 20.0
        frame.teleportTicks shouldBe 0

        val points = storyQuestWaypointParticles(Location(world, 0.0, 67.0, 20.0), target, 6.0, 8.4)
        points.size shouldBe 24
        points.take(18).all { kotlin.math.abs(it.x - 10.2) < 1e-9 } shouldBe true
        points.take(18).all { it.y in 67.15..74.1 && kotlin.math.abs(it.z - 20.0) <= 2.64 } shouldBe true
        points.drop(18).all { it.x in 0.5..3.0 && it.y == 67.2 } shouldBe true
        storyQuestWaypointParticles(target, target, 1.2, 2.4).size shouldBe 18
        target shouldBe Location(world, 10.0, 67.0, 20.0, 90f, 0f)
    }
})
