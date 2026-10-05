package ru.arc.furniturehitbox

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

class ItemsAdderFurnitureHitboxSourceTest : StringSpec({
    "foreground visible furniture wins over a background native hit and its barrier" {
        val foreground = FurnitureHitboxTarget(mockk(), BoundingBox(0.0, 0.0, 0.0, 0.2, 1.0, 0.2))
        val background = FurnitureHitboxTarget(mockk(), BoundingBox(1.0, 0.0, 1.0, 2.0, 1.0, 2.0))
        val candidates = listOf(
            FurnitureRayCandidate(foreground, nativeDistance = null, visualDistance = 1.0),
            FurnitureRayCandidate(background, nativeDistance = 3.0, visualDistance = 3.0),
        )
        nearestFurnitureHitboxTarget(candidates, background, 2.5) shouldBe foreground
        nearestFurnitureHitboxTarget(candidates.take(1), background, 2.5) shouldBe foreground
        nearestFurnitureHitboxTarget(candidates.take(1), background, 0.5) shouldBe background
        nearestFurnitureHitboxTarget(emptyList(), null, null) shouldBe null
    }

    "furniture uses the exact configured root box, never a square crop Interaction" {
        val native = BoundingBox(10.0, 64.0, 20.0, 12.0, 64.1, 20.4)
        val box = nativeFurnitureEntityBounds(native)!!
        box shouldBe native
        box.widthZ shouldBe 0.3999999999999986
        box.rayTrace(Vector(9.0, 64.05, 20.7), Vector(1.0, 0.0, 0.0), 5.0) shouldBe null
        box.shift(0.0, 5.0, 0.0)
        native.minY shouldBe 64.0
        nativeFurnitureEntityBounds(BoundingBox(0.0, 1.0, 0.0, 2.0, 1.0, 2.0)) shouldBe null
        nativeFurnitureEntityBounds(BoundingBox(0.0, 0.0, 0.0, 17.0, 1.0, 2.0)) shouldBe null
    }

    "barrier and entity hits keep the same whole furniture box while sweeping across its blocks" {
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val box = BoundingBox(2.0, 64.0, 3.0, 5.0, 66.0, 4.0)
        val root = mockk<Entity> {
            every { isValid } returns true
            every { this@mockk.world } returns world
            every { boundingBox } returns box
        }
        val nativeTarget = FurnitureHitboxTarget(root, nativeFurnitureEntityBounds(box)!!)
        for (x in 2..4) {
            val block = mockk<Block> {
                every { this@mockk.world } returns world
                every { isPassable } returns false
                every { type } returns Material.BARRIER
                every { boundingBox } returns BoundingBox(x.toDouble(), 64.0, 3.0, x + 1.0, 65.0, 4.0)
            }
            val blockTarget = nativeFurnitureBlockTarget(block) { root }
            blockTarget shouldBe nativeTarget
            // The model, native entity and collision block can win on consecutive gaze updates.
            for ((nativeDistance, visualDistance) in listOf(1.0 to null, 3.0 to null, null to 1.0, null to null)) {
                val candidate = FurnitureRayCandidate(nativeTarget, nativeDistance, visualDistance)
                nearestFurnitureHitboxTarget(listOf(candidate), blockTarget, 2.0) shouldBe nativeTarget
            }
        }
    }

    "barrier lookup rejects ordinary supports and roots whose box does not contain the barrier center" {
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val box = BoundingBox(2.0, 64.0, 3.0, 5.0, 66.0, 4.0)
        val block = mockk<Block> {
            every { this@mockk.world } returns world
            every { isPassable } returns false
            every { type } returns Material.BARRIER
            every { boundingBox } returns BoundingBox(2.0, 64.0, 3.0, 3.0, 65.0, 4.0)
        }
        val root = mockk<Entity> {
            every { isValid } returns true
            every { this@mockk.world } returns world
            every { boundingBox } returns box
        }
        var resolvedBlock: Block? = null
        nativeFurnitureBlockTarget(block) {
            resolvedBlock = it
            root
        } shouldBe FurnitureHitboxTarget(root, box)
        resolvedBlock shouldBe block
        nativeFurnitureBlockTarget(mockk { every { isPassable } returns true }) {
            error("passable blocks must not resolve furniture")
        } shouldBe null
        nativeFurnitureBlockTarget(block) { null } shouldBe null
        every { root.boundingBox } returns box.clone().shift(3.0, 0.0, 0.0)
        nativeFurnitureBlockTarget(block) { root } shouldBe null
        every { block.type } returns Material.STONE
        nativeFurnitureBlockTarget(block) { error("ordinary support must not resolve furniture") } shouldBe null
    }

    "large fountain barriers on the maximum native borders keep the whole outline" {
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val box = BoundingBox(73.5, 67.001, 4.5, 77.5, 71.001, 8.5)
        val root = mockk<Entity> {
            every { isValid } returns true
            every { this@mockk.world } returns world
            every { boundingBox } returns box
        }
        for ((x, z) in listOf(74 to 5, 77 to 5, 74 to 8, 77 to 8)) {
            val barrier = BoundingBox(x.toDouble(), 70.0, z.toDouble(), x + 1.0, 71.0, z + 1.0)
            val block = mockk<Block> {
                every { this@mockk.world } returns world
                every { isPassable } returns false
                every { type } returns Material.BARRIER
                every { boundingBox } returns barrier
            }
            nativeFurnitureBlockTarget(block) { root } shouldBe FurnitureHitboxTarget(root, box)
            nativeFurnitureRayUnblocked(box, 3.0, 2.5, barrier, true) shouldBe true
            barrier.widthX shouldBe 1.0
            barrier.height shouldBe 1.0
            barrier.widthZ shouldBe 1.0
        }
        val outside = BoundingBox(78.0, 70.0, 8.0, 79.0, 71.0, 9.0)
        nativeFurnitureRayUnblocked(box, 3.0, 2.5, outside, true) shouldBe false
    }

    "wall occlusion rejects hidden furniture but permits its enclosed support" {
        val furniture = BoundingBox(0.0, 64.0, 0.0, 2.0, 66.0, 2.0)
        val wall = BoundingBox(-2.0, 64.0, 0.0, -1.0, 65.0, 1.0)
        nativeFurnitureRayUnblocked(furniture, 3.0, 1.0, wall, false) shouldBe false
        nativeFurnitureRayUnblocked(furniture, 1.0, 3.0, wall, false) shouldBe true
        nativeFurnitureRayUnblocked(furniture, 3.0, null, null, false) shouldBe true
        val support = BoundingBox(0.0, 64.0, 0.0, 1.0, 65.0, 1.0)
        nativeFurnitureRayUnblocked(furniture, 3.0, 2.0, support, false) shouldBe true
    }

    "IA barrier targeting requires its center inside the furniture root box" {
        val native = BoundingBox(0.1, 64.1, 0.1, 0.9, 64.9, 0.9)
        val owned = BoundingBox(0.0, 64.0, 0.0, 1.0, 65.0, 1.0)
        nativeFurnitureRayUnblocked(native, 3.0, 2.5, owned, true) shouldBe true
        val foreign = owned.clone().shift(3.0, 0.0, 0.0)
        nativeFurnitureRayUnblocked(native, 3.0, 4.0, foreign, true) shouldBe false
    }

    val jarPath = System.getenv("ITEMSADDER_4_0_18_JAR")
    if (!jarPath.isNullOrBlank()) {
        "exact IA artifact connects public furniture API to native entity bounds and arm-swing targeting" {
            val artifact = Path.of(jarPath)
            check(Files.isRegularFile(artifact))
            // This semantic path check intentionally starts at the public furniture API. Merely
            // checking plausible obfuscated signatures previously accepted the unrelated crop system.
            val api = disassemble(artifact, "dev.lone.itemsadder.api.CustomFurniture")
            api shouldContain "Field behaviour:Litemsadder/m/js;"
            api shouldContain "// String furniture"
            val behaviour = disassemble(artifact, "itemsadder.m.js")
            behaviour shouldContain "itemsadder/m/afz.a:(Lorg/bukkit/entity/Entity;Lorg/bukkit/util/BoundingBox;)V"
            val selection = disassemble(artifact, "itemsadder.m.jl")
            selection shouldContain "org/bukkit/entity/Entity.getBoundingBox:()Lorg/bukkit/util/BoundingBox;"
            selection shouldContain "// double 5.0d"
            val clicks = disassemble(artifact, "itemsadder.m.kv")
            clicks shouldContain "itemsadder/m/jl.ao:(Lorg/bukkit/entity/Player;)Lorg/bukkit/entity/Entity;"
            clicks shouldContain "dev/lone/itemsadder/api/CustomFurniture.byAlreadySpawned:(Lorg/bukkit/entity/Entity;)"
            listOf(api, behaviour, selection, clicks).forEach {
                it shouldNotContain "org/bukkit/entity/Interaction"
                it shouldNotContain "itemsadder/m/co"
            }
        }
    }
})

private fun disassemble(artifact: Path, className: String): String {
    val output = Files.createTempFile("ia-furniture-contract-", ".txt")
    return try {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "javap").toString(),
            "-p", "-c", "-classpath", artifact.toString(), className,
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "javap timed out" }
        check(process.exitValue() == 0) { Files.readString(output) }
        Files.readString(output)
    } finally {
        Files.deleteIfExists(output)
    }
}
