package ru.arc.landsui

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.VoxelShape
import org.bukkit.util.Vector
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

class ClaimLandMenuTest : FreeSpec({
    "keeps a fixed head-turn plane, escapes and retains an obstacle offset, targets its cell, and cleans packets" {
        val world = mockk<World>(relaxed = true)
        val worldId = UUID.randomUUID()
        every { world.uid } returns worldId
        every { world.minHeight } returns 0
        every { world.maxHeight } returns 256
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        val shape = mockk<VoxelShape>(relaxed = true)
        every { shape.boundingBoxes } returns emptyList()
        val block = mockk<Block>(relaxed = true)
        every { block.collisionShape } returns shape
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns block
        every {
            world.rayTraceBlocks(
                any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>(),
            )
        } returns null

        val playerId = UUID.randomUUID()
        val eye = Location(world, 0.0, 65.62, 0.0, 0f, 0f)
        val body = Location(world, 0.0, 64.0, 0.0, 0f, 0f)
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns playerId
        every { player.world } returns world
        every { player.eyeLocation } returns eye
        every { player.location } returns body
        every { player.isChunkSent(any<Long>()) } returns true

        val packetOwner = mockk<PaperPacketDisplays>(relaxed = true)
        val packetDisplays = List(7) { mockk<PacketTextDisplay>(relaxed = true) }
        var created = 0
        every { packetOwner.spawnText(any<Location>(), any<Component>()) } answers { packetDisplays[created++] }
        val menu = ClaimLandMenu(
            mockk<Plugin>(relaxed = true),
            title = { Component.text(it) },
            labels = LandsUiPanelAction.entries.associateWith { Component.text(it.name) },
            displays = packetOwner,
        )

        val turnedHead = body.clone().apply { yaw = 130f }
        ClaimLandMenuGeometry.bodyMoved(body, turnedHead) shouldBe false
        ClaimLandMenuGeometry.bodyMoved(body, body.clone().add(0.1, 0.0, 0.0)) shouldBe true
        val fixedYaw = ClaimLandMenuGeometry.panelYaw(eye.yaw)
        val turnedPanelYaw = if (ClaimLandMenuGeometry.bodyMoved(body, turnedHead)) {
            ClaimLandMenuGeometry.panelYaw(turnedHead.yaw)
        } else {
            fixedYaw
        }
        val center = ClaimLandMenuGeometry.center(eye, fixedYaw, 2.4, ClaimLandMenuShift())
        ClaimLandMenuGeometry.center(eye.clone().apply { yaw = turnedHead.yaw }, turnedPanelYaw, 2.4, ClaimLandMenuShift()) shouldBe center
        (center.z > eye.z) shouldBe true // camera yaw 0 looks toward +Z
        val leftRect = ClaimLandMenuGeometry.rects.getValue(LandsUiPanelAction.ADD_MEMBER)
        val leftLabel = ClaimLandMenuGeometry.textLocation(center, fixedYaw, leftRect.centerX, leftRect.centerY)
        (leftLabel.x > center.x) shouldBe true // Builder's screen-left points toward +X at camera yaw 0
        leftLabel.yaw shouldBe 180f

        val buttonRect = ClaimLandMenuGeometry.rects.getValue(LandsUiPanelAction.ADD_MEMBER)
        val labelLocation = ClaimLandMenuGeometry.labelLocation(center, 90f, buttonRect)
        (kotlin.math.abs(labelLocation.y - (center.y + buttonRect.centerY - buttonRect.height / 2.0 + 0.025)) < 1e-9) shouldBe true
        (kotlin.math.abs(labelLocation.x - (center.x + kotlin.math.cos(Math.toRadians(90.0)) * buttonRect.centerX)) < 1e-9) shouldBe true
        (kotlin.math.abs(labelLocation.z - (center.z + kotlin.math.sin(Math.toRadians(90.0)) * buttonRect.centerX)) < 1e-9) shouldBe true
        val buttonCenter = ClaimLandMenuGeometry.textLocation(center, fixedYaw, -0.8, 0.12)
        val hit = ClaimLandMenuGeometry.rayPlaneHit(
            eye,
            buttonCenter.toVector().subtract(eye.toVector()),
            center,
            fixedYaw,
        )
        (kotlin.math.abs(hit!!.x + 0.8) < 1e-9) shouldBe true
        (kotlin.math.abs(hit.y - 0.12) < 1e-9) shouldBe true
        ClaimLandMenuGeometry.actionAt(hit.x, hit.y) shouldBe LandsUiPanelAction.ADD_MEMBER

        val titleRect = ClaimLandMenuGeometry.headingRect
        val titleTop = ClaimLandMenuGeometry.textLocation(
            center, fixedYaw, titleRect.centerX, titleRect.centerY + titleRect.height / 2.0,
        )
        val titleHit = ClaimLandMenuGeometry.rayPlaneHit(
            eye, titleTop.toVector().subtract(eye.toVector()), center, fixedYaw,
        )
        (kotlin.math.abs(titleHit!!.y - (titleRect.centerY + titleRect.height / 2.0)) < 1e-9) shouldBe true

        listOf(0f, 45f, 90f, 180f, 270f, 315f).forEach { cameraYaw ->
            val panelYaw = ClaimLandMenuGeometry.panelYaw(cameraYaw)
            val panelCenter = ClaimLandMenuGeometry.center(
                eye, panelYaw, 2.4, ClaimLandMenuShift(side = 0.25, up = 0.25),
            )
            val bounds = ClaimLandMenuGeometry.bounds(panelCenter, panelYaw)
            ClaimLandMenuGeometry.fullPanelRects.forEach { rect ->
                listOf(-rect.width / 2.0, rect.width / 2.0).forEach { x ->
                    listOf(-rect.height / 2.0, rect.height / 2.0).forEach { y ->
                        val corner = ClaimLandMenuGeometry.textLocation(
                            panelCenter, panelYaw, rect.centerX + x, rect.centerY + y,
                        )
                        val contained = corner.x >= bounds.minX - 1e-8 && corner.x <= bounds.maxX + 1e-8 &&
                            corner.y >= bounds.minY - 1e-8 && corner.y <= bounds.maxY + 1e-8 &&
                            corner.z >= bounds.minZ - 1e-8 && corner.z <= bounds.maxZ + 1e-8
                        contained shouldBe true
                    }
                }
            }
        }

        val centerBounds = ClaimLandMenuGeometry.bounds(center, fixedYaw)
        val centerShiftObstacle = ClaimLandMenuBounds(
            center.x - 0.1, centerBounds.minY - 0.1, center.z - 0.05,
            center.x + 0.1, centerBounds.minY + 0.05, center.z + 0.05,
        )
        ClaimLandMenuGeometry.overlapsPanel(center, fixedYaw, centerShiftObstacle) shouldBe true
        val safeOffset = ClaimLandMenuGeometry.chooseShift(null) { shift ->
            val shiftedCenter = ClaimLandMenuGeometry.center(eye, fixedYaw, 2.4, shift)
            !ClaimLandMenuGeometry.overlapsPanel(shiftedCenter, fixedYaw, centerShiftObstacle)
        }
        safeOffset shouldBe ClaimLandMenuShift(up = 0.25)
        ClaimLandMenuGeometry.chooseShift(ClaimLandMenuShift(up = 0.5)) {
            it == ClaimLandMenuShift(up = 0.5) || it == ClaimLandMenuShift()
        } shouldBe ClaimLandMenuShift(up = 0.5)

        menu.show(player, "home", "Дом")
        created shouldBe 7
        menu.contains(playerId) shouldBe true
        menu.landId(playerId) shouldBe "home"
        menu.hide(playerId)
        menu.contains(playerId) shouldBe false
        menu.landId(playerId) shouldBe null
        packetDisplays.forEach { verify(exactly = 1) { it.remove() } }
    }
})
