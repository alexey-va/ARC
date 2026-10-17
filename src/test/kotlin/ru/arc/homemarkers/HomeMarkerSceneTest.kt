package ru.arc.homemarkers

import com.google.gson.GsonBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.config.ConfigManager
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SpatialControl
import ru.arc.paper.scene.SpatialControlController
import ru.arc.paper.scene.SpatialControlFrame
import ru.arc.paper.scene.SpatialInputProfile
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.paper.scene.SpatialSceneScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.math.cos
import kotlin.math.sin

class HomeMarkerSceneTest : StringSpec({
    "large loot marker stays above the saved floor and label clears its top" {
        val settings = HomeMarkerSettings()
        val shape = homeMarkerGeometry(settings.scale, true)
        shape.width shouldBe (1.6 plusOrMinus 0.00001)
        shape.height shouldBe (2.4 plusOrMinus 0.00001)
        (shape.itemY - settings.scale) shouldBe (0.05 plusOrMinus 0.00001)
        (shape.labelY - shape.floorY - shape.height) shouldBe (0.12 plusOrMinus 0.00001)
        shape.centerY shouldBe (1.25 plusOrMinus 0.00001)

        // Optional source-derived fixture for the offline model/texture preview; no runtime mutation.
        System.getenv("HOME_MARKER_PREVIEW_OUTPUT")?.let { destination ->
            val path = Path.of(destination)
            Files.createDirectories(path.parent)
            val marker = HomeMarker(HomeMarkerKind.HOME, "preview", UUID(0, 1), "Уютный дом", "world", 0.0, 0.0, 0.0)
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "source" to "HomeMarkerSceneTest: actual homeMarkerGeometry + HomeMarkerSettings",
                "scale" to settings.scale,
                "labelScale" to settings.labelScale,
                "floorY" to shape.floorY,
                "height" to shape.height,
                "itemY" to shape.itemY,
                "labelY" to shape.labelY,
                "color" to settings.homeColor.asRGB(),
                "label" to PlainTextComponentSerializer.plainText().serialize(settings.label(marker)),
                "scope" to "Offline source geometry and resource textures; excludes native packets, client shading and interaction",
            )))
        }
    }

    "portable beacon fallback has a centered block origin instead of the loot model offset" {
        val shape = homeMarkerGeometry(1.6f, false)
        shape.height shouldBe (1.6 plusOrMinus 0.00001)
        (shape.itemY - shape.height / 2.0) shouldBe (0.05 plusOrMinus 0.00001)
        shape.centerY shouldBe shape.itemY
    }

    "shared ray accepts the beam edges and caption from every side but retains reach and obstruction checks" {
        MockBukkitTestRuntime.open().use {
            val plugin = mockk<Plugin>(relaxed = true)
            val world = mockk<World>(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            val owner = UUID(0, 1)
            val keys = listOf(SpatialSceneKey("marker-body"), SpatialSceneKey("marker-label"))
            val anchor = Location(world, 0.0, 64.0, 0.0)
            val shape = homeMarkerGeometry(1.6f, true)
            var eye = anchor.clone()
            var wall: BoundingBox? = null
            val frames = mutableMapOf<SpatialSceneKey, SpatialControlFrame<String, String>>()
            val native = mutableListOf<Interaction>()
            every { world.uid } returns UUID(0, 2)
            every { world.minHeight } returns -64
            every { world.maxHeight } returns 320
            every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
            every { world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } answers {
                wall?.rayTrace(firstArg<Location>().toVector(), secondArg<Vector>(), thirdArg<Double>())
            }
            every { player.uniqueId } returns owner
            every { player.world } returns world
            every { player.isOnline } returns true
            every { player.isDead } returns false
            every { player.isChunkSent(any<Long>()) } returns true
            every { player.eyeLocation } answers { eye.clone() }
            every { player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE) } returns mockk { every { value } returns 3.0 }
            every { plugin.server.onlinePlayers } returns listOf(player)
            every { plugin.server.getPlayer(owner) } returns player
            every { world.spawn(any<Location>(), eq(Interaction::class.java), any<Consumer<Interaction>>()) } answers {
                var at = firstArg<Location>().clone()
                val entity = mockk<Interaction>(relaxed = true)
                every { entity.uniqueId } returns UUID.randomUUID()
                every { entity.isValid } returns true
                every { entity.location } answers { at.clone() }
                every { entity.teleport(any<Location>()) } answers { at = firstArg<Location>().clone(); true }
                thirdArg<Consumer<Interaction>>().accept(entity)
                native += entity
                entity
            }
            val controls = PaperSpatialControls(plugin, mockk<PaperPacketDisplays>(relaxed = true),
                object : SpatialControlController<String, String>() {
                    override fun current(player: Player, scene: SpatialSceneKey) = frames[scene]
                    override fun activate(player: Player, scene: SpatialSceneKey, context: String, action: String) = Unit
                })
            controls.use {
                fun apply(sneaking: Boolean) {
                    keys.forEachIndexed { index, key ->
                        val box = homeMarkerHitBox(shape, 0.6f, index == 1)
                        val frame = SpatialControlFrame("home", SceneAudience.Private(owner, null), SpatialSceneScope.Viewer(owner),
                            listOf(SpatialControl("home", "settings", box.planes(anchor),
                                if (sneaking) box.nativeTarget(anchor) else null, sneaking, true)),
                            SpatialInputProfile.ENTITY_RIGHT, SpatialReach.NativeInteraction, sneaking, emptyList())
                        frames[key] = frame
                        controls.apply(key, frame)
                    }
                    controls.captureNativeForTest(player)
                }
                fun target(x: Double, y: Double, yaw: Float = 0f, eyeHeight: Double = 1.27,
                    distance: Double = 2.0, depth: Double = 0.0): String? {
                    val angle = Math.toRadians(yaw.toDouble())
                    val right = Vector(cos(angle), 0.0, sin(angle))
                    val forward = Vector(-sin(angle), 0.0, cos(angle))
                    val point = anchor.toVector().add(right.clone().multiply(x)).add(Vector(0.0, y, 0.0))
                        .add(forward.clone().multiply(depth))
                    eye = anchor.clone().add(right.clone().multiply(x)).subtract(forward.clone().multiply(distance))
                        .add(0.0, eyeHeight, 0.0)
                    eye.direction = point.clone().subtract(eye.toVector())
                    // Turning the camera must keep working without reapplying the shown scene.
                    if (keys.firstNotNullOfOrNull { controls.target(it, player)?.action } == null) return null
                    val visualKey = SpatialSceneKey("marker-visual-check")
                    val visual = SpatialControlFrame("home", SceneAudience.Private(owner, null), SpatialSceneScope.Viewer(owner),
                        listOf(SpatialControl("home", "settings", homeMarkerVisualHitPlanes(anchor, eye.yaw, shape, 0.6f),
                            null, true, true)), SpatialInputProfile.EXTERNAL_RAY, SpatialReach.NativeInteraction, true, emptyList())
                    frames[visualKey] = visual
                    return try {
                        controls.apply(visualKey, visual)
                        controls.target(visualKey, player)?.action
                    } finally {
                        controls.clear(visualKey)
                        frames.remove(visualKey)
                    }
                }
                apply(false)
                native.size shouldBe 0
                apply(true)
                native.size shouldBe 2
                for (yaw in listOf(0f, 37f, 90f, 180f, 270f)) {
                    for (eyeHeight in listOf(1.27, 1.62)) {
                        for ((x, y) in listOf(0.0 to 1.25, -0.95 to 1.25, 0.95 to 1.25, 0.0 to 2.55,
                            0.0 to 2.75, -1.75 to 2.75, 1.75 to 2.75, 0.0 to 3.05)) {
                            withClue("yaw=$yaw eye=$eyeHeight point=($x,$y)") {
                                target(x, y, yaw, eyeHeight) shouldBe "settings"
                            }
                        }
                        target(0.85, 0.05, yaw, eyeHeight, depth = -0.85) shouldBe "settings"
                        target(2.0, 1.25, yaw, eyeHeight) shouldBe null
                        target(3.0, 2.75, yaw, eyeHeight) shouldBe null
                    }
                }
                target(0.0, 1.25, distance = 4.5) shouldBe null
                target(0.0, 1.25, distance = 3.1) shouldBe null

                // The wide native front is in front of this wall, while the actual marker is behind it.
                anchor.z = 0.2
                apply(true)
                wall = BoundingBox(-5.0, 64.0, -1.0, 5.0, 68.0, 0.0)
                target(0.0, 1.25, distance = 2.8) shouldBe null
                controls.target(keys.first(), player)?.action shouldBe "settings"
                wall = null
                target(0.0, 1.25, distance = 2.8) shouldBe "settings"
                every { player.uniqueId } returns UUID(0, 3)
                target(0.0, 1.25) shouldBe null
                every { player.uniqueId } returns owner
                apply(false)
                target(0.0, 1.25) shouldBe null
                native.forEach { entity ->
                    verify { entity.isVisibleByDefault = false; player.showEntity(plugin, entity); entity.remove() }
                }
            }
        }
    }

    "private selection removes foreign owners and worlds and bounds nearest markers" {
        val owner = UUID(0, 1)
        val world = mockk<World> { every { name } returns "world" }
        val points = (1..70).map { index ->
            HomeMarker(HomeMarkerKind.HOME, index.toString(), owner, "Дом $index", "world", index * 0.1, 64.0, 0.0)
        }.reversed()
        val visible = nearbyHomeMarkers(owner, Location(world, 0.0, 64.0, 0.0), points + listOf(
            points.last().copy(id = "foreign", ownerId = UUID(0, 2)),
            points.last().copy(id = "other-world", world = "other"),
            points.last().copy(id = "far", x = 33.0),
        ), 32.0)
        visible.size shouldBe 64
        visible.first().id shouldBe "1"
        visible.last().id shouldBe "64"
        visible.all { it.ownerId == owner && it.world == "world" } shouldBe true
    }

    "home and warp names stay literal and source defaults do not require a resource pack" {
        val settings = HomeMarkerConfig(ConfigManager.empty()).snapshot()
        val marker = HomeMarker(HomeMarkerKind.HOME, "literal", UUID(0, 1), "<red>дом", "world", 0.0, 64.0, 0.0)
        settings.modelId shouldBe ""
        settings.refreshTicks shouldBe 100L
        PlainTextComponentSerializer.plainText().serialize(settings.label(marker)) shouldBe
            "Дом · <red>дом\nShift + ПКМ · Настройки"
        PlainTextComponentSerializer.plainText().serialize(settings.label(marker.copy(kind = HomeMarkerKind.WARP))) shouldBe
            "Варп · <red>дом\nShift + ПКМ · Настройки"
        shouldThrow<IllegalArgumentException> { settings.copy(scale = Float.NaN) }
        shouldThrow<IllegalArgumentException> { settings.copy(viewDistance = 0.0) }
    }
})

/** A relaxed display mock has no capture pump; execute Core's real native participant. */
private fun PaperSpatialControls<*, *>.captureNativeForTest(player: Player) {
    val participant = javaClass.getDeclaredField("participant").apply { isAccessible = true }.get(this)
    val capture = participant.javaClass.getDeclaredMethod("captureSteps", List::class.java).apply { isAccessible = true }
    // Preserve the visibility oracle too: a native capture includes its exact online audience.
    val viewerType = Class.forName("ru.arc.paper.display.PacketDisplayViewer")
    val viewerConstructor = viewerType.getDeclaredConstructor(Player::class.java, UUID::class.java,
        UUID::class.java, Int::class.javaPrimitiveType, Set::class.java, Double::class.javaPrimitiveType,
        Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Set::class.java, List::class.java)
    val eye = player.eyeLocation
    val viewer = viewerConstructor.newInstance(player, player.uniqueId, player.world.uid, player.entityId,
        emptySet<UUID>(), eye.x, eye.y - player.eyeHeight, eye.z, emptySet<Long>(), emptyList<Int>())
    val steps = capture.invoke(participant, listOf(viewer)) as Iterator<*>
    val clock = TestTaskScheduler()
    LifecycleTaskScope(clock).use { scope ->
        BudgetedTaskQueue(scope, maximumSteps = 2, nanoTime = { 0 }).use { work ->
            val completed = work.submit({ true }) {
                if (steps.hasNext()) { steps.next(); false } else true
            }
            repeat(200) { if (!completed.isDone) clock.tick() }
            completed.get(1, TimeUnit.SECONDS)
        }
    }
}
