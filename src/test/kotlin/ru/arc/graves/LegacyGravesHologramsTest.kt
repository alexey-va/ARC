package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class LegacyGravesHologramsTest : FunSpec({
    test("only exact GravesX hologram ownership is retired; player decoration survives") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("GraveCleanup")
            val world = paper.addSimpleWorld("grave_cleanup")
            val location = Location(world, .5, 65.0, .5)
            val personal = world.spawn(location, ArmorStand::class.java)
            val unowned = world.spawn(location, ArmorStand::class.java).apply { addScoreboardTag("graveHologram") }
            val retired = world.spawn(location, ArmorStand::class.java).apply {
                addScoreboardTag("graveHologram")
                persistentDataContainer.set(NamespacedKey("gravesx", "grave_uuid"), PersistentDataType.STRING, UUID.randomUUID().toString())
            }
            val conflicting = world.spawn(location, ArmorStand::class.java).apply {
                addScoreboardTag("graveHologram")
                addScoreboardTag("graveHologramGraveUUID:${UUID.randomUUID()}")
                persistentDataContainer.set(NamespacedKey("gravesx", "grave_uuid"), PersistentDataType.STRING, UUID.randomUUID().toString())
            }
            LegacyGravesHolograms.owns(personal) shouldBe false
            LegacyGravesHolograms.owns(unowned) shouldBe false
            LegacyGravesHolograms.owns(retired) shouldBe true
            LegacyGravesHolograms.owns(conflicting) shouldBe false
            LegacyGravesHolograms(plugin).use { cleanup -> cleanup.start() }
            retired.isDead shouldBe true
            personal.isDead shouldBe false
            unowned.isDead shouldBe false
            conflicting.isDead shouldBe false
        }
    }
})
