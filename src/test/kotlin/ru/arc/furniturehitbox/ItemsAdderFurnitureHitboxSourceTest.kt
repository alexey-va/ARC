package ru.arc.furniturehitbox

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class ItemsAdderFurnitureHitboxSourceTest : StringSpec({
    "native cache freshness matches IA's tick, eye-position, direction, and reach window" {
        val origin = Vector(0.0, 64.0, 0.0)
        val direction = Vector(0.0, 0.0, 1.0)

        nativeRayCacheFreshness(
            cachedTick = 100L,
            currentTick = 102L,
            cachedOrigin = origin,
            cachedDirection = direction,
            cachedReach = 4.5,
            currentOrigin = origin.clone(),
            currentDirection = direction.clone(),
            currentReach = 4.5,
        ) shouldBe 4.5

        nativeRayCacheFreshness(
            cachedTick = 100L,
            currentTick = 103L,
            cachedOrigin = origin,
            cachedDirection = direction,
            cachedReach = 4.5,
            currentOrigin = origin.clone(),
            currentDirection = direction.clone(),
            currentReach = 4.5,
        ) shouldBe null

        nativeRayCacheFreshness(
            cachedTick = 100L,
            currentTick = 101L,
            cachedOrigin = origin,
            cachedDirection = direction,
            cachedReach = 4.5,
            currentOrigin = origin.clone(),
            currentDirection = Vector(sin(Math.toRadians(5.0)), 0.0, cos(Math.toRadians(5.0))),
            currentReach = 4.5,
        ) shouldBe null

        nativeRayCacheFreshness(
            cachedTick = 100L,
            currentTick = 101L,
            cachedOrigin = origin,
            cachedDirection = direction,
            cachedReach = 4.5,
            currentOrigin = Vector(0.21, 64.0, 0.0),
            currentDirection = direction.clone(),
            currentReach = 4.5,
        ) shouldBe null

        nativeRayCacheFreshness(
            cachedTick = 100L,
            currentTick = 101L,
            cachedOrigin = origin,
            cachedDirection = direction,
            cachedReach = 4.5,
            currentOrigin = origin.clone(),
            currentDirection = direction.clone(),
            currentReach = 5.0,
        ) shouldBe null
    }

    "native outline accepts aim on model or wider IA interaction box, but not through a wall" {
        // IA expands the narrower horizontal dimension to max(widthX, widthZ) for its Interaction.
        val model = BoundingBox(-1.0, 0.0, -0.2, 1.0, 2.0, 0.2)
        val interaction = BoundingBox(-1.0, 0.0, -1.0, 1.0, 2.0, 1.0)
        val origin = Vector(-4.0, 1.0, 0.75)
        val direction = Vector(1.0, 0.0, 0.0)

        nativeHitboxVisibleFromRay(
            modelBounds = model,
            interactionBounds = interaction,
            origin = origin,
            direction = direction,
            reach = 5.0,
            blockDistanceSquared = 25.0,
        ) shouldBe true

        nativeHitboxVisibleFromRay(
            modelBounds = model,
            interactionBounds = interaction,
            origin = origin,
            direction = direction,
            reach = 5.0,
            blockDistanceSquared = 1.0,
        ) shouldBe false

        nativeHitboxVisibleFromRay(
            modelBounds = model,
            interactionBounds = interaction,
            origin = Vector(-4.0, 1.0, 0.0),
            direction = direction,
            reach = 5.0,
            blockDistanceSquared = 25.0,
        ) shouldBe true
    }

    "native block targeting returns only the exact opaque IA-owned block bounds" {
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val box = BoundingBox(2.0, 64.0, 3.0, 3.0, 65.0, 4.0)
        val block = mockk<Block> {
            every { this@mockk.world } returns world
            every { isPassable } returns false
            every { boundingBox } returns box
        }
        val root = mockk<Entity> {
            every { isValid } returns true
            every { this@mockk.world } returns world
        }
        var resolvedBlock: Block? = null

        nativeFurnitureBlockTarget(block) {
            resolvedBlock = it
            root
        } shouldBe FurnitureHitboxTarget(root, box)
        resolvedBlock shouldBe block

        val passable = mockk<Block> {
            every { isPassable } returns true
        }
        nativeFurnitureBlockTarget(passable) { error("passable blocks must not resolve furniture") } shouldBe null
        nativeFurnitureBlockTarget(block) { null } shouldBe null
    }

    "native Interaction dimensions use square horizontal float bounds without model approximations" {
        val native = BoundingBox(10.0, 64.0, 20.0, 12.0, 64.1, 20.4)
        val box = nativeInteractionBounds(native)!!
        box.widthX shouldBe 2.0
        box.widthZ shouldBe 2.0
        box.minY shouldBe 64.0
        box.maxY shouldBe 64.0 + native.height.toFloat().toDouble()
        box.centerX shouldBe native.centerX
        box.centerZ shouldBe native.centerZ
        nativeInteractionBounds(BoundingBox(0.0, 1.0, 0.0, 2.0, 1.0, 2.0)) shouldBe null
    }

    "click outline excludes the square Interaction excess rejected by IA server ray validation" {
        val model = BoundingBox(-1.0, 0.0, -0.2, 1.0, 2.0, 0.2)
        val physical = nativeInteractionBounds(model)!!
        val clickable = nativeFurnitureClickBounds(model, physical)!!
        clickable shouldBe model
        clickable.rayTrace(Vector(-4.0, 1.0, 0.75), Vector(1.0, 0.0, 0.0), 5.0) shouldBe null
        (clickable.rayTrace(Vector(-4.0, 1.0, 0.0), Vector(1.0, 0.0, 0.0), 5.0) != null) shouldBe true
        nativeFurnitureClickBounds(model, physical.clone().shift(0.0, 5.0, 0.0)) shouldBe null
    }

    val jarPath = System.getenv("ITEMSADDER_4_0_18_JAR")
    if (!jarPath.isNullOrBlank()) {
        "ItemsAdder 4.0.18 artifact matches every cached-reflection binding" {
            val artifact = Path.of(jarPath)
            check(Files.isRegularFile(artifact)) { "ITEMSADDER_4_0_18_JAR is not a file: $artifact" }
            ItemsAdderJarClassLoader(artifact.toUri().toURL(), javaClass.classLoader).use { loader ->
                val bindings = ItemsAdderFurnitureHitboxSource.Bindings.bind(loader)

                bindings.managerSingleton.declaringClass.name shouldBe "itemsadder.m.d"
                bindings.managerSingleton.returnType.name shouldBe "itemsadder.m.d"
                bindings.managerField.declaringClass.name shouldBe "itemsadder.m.d"
                bindings.managerField.type.name shouldBe "itemsadder.m.co"
                bindings.furnitureManagerField.type.name shouldBe "itemsadder.m.br"
                bindings.hitboxManagerField.type.name shouldBe "itemsadder.m.ce"
                bindings.viewerContextMethod.returnType.name shouldBe "itemsadder.m.ci"
                bindings.furnitureAtLocationMethod.returnType.name shouldBe "itemsadder.m.cj"
                bindings.viewerHitboxMethod.returnType.name shouldBe "itemsadder.m.cg"
                bindings.lastTargetField.type.name shouldBe "itemsadder.m.cl"
                bindings.lastBlockField.type.name shouldBe "org.bukkit.block.Block"
                bindings.cachedFurnitureMethod.returnType.name shouldBe "itemsadder.m.cj"
                bindings.rootDisplayField.type.name shouldBe "org.bukkit.entity.ItemDisplay"
                bindings.modelBoxMethod.returnType.name shouldBe "itemsadder.m.ck"
                bindings.nativeModelBoundsMethod.returnType.name shouldBe "org.bukkit.util.BoundingBox"
                bindings.modelRayTraceMethod.returnType.name shouldBe "org.bukkit.util.Vector"
                bindings.viewerLocationField.type.name shouldBe "org.bukkit.Location"
                bindings.viewerModelBoxField.type.name shouldBe "org.bukkit.util.BoundingBox"
                bindings.viewerInteractionField.type.name shouldBe "org.bukkit.entity.Interaction"
                bindings.customFurnitureByEntity.returnType.name shouldBe "dev.lone.itemsadder.api.CustomFurniture"
                bindings.customFurnitureEntityMethod.returnType.name shouldBe "org.bukkit.entity.Entity"
            }
        }
    }
})

private class ItemsAdderJarClassLoader(url: URL, parent: ClassLoader) : URLClassLoader(arrayOf(url), parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!name.startsWith("itemsadder.m.") && !name.startsWith("dev.lone.itemsadder.")) {
            return super.loadClass(name, resolve)
        }
        synchronized(getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name)
            if (loaded == null) {
                loaded = try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    super.loadClass(name, false)
                }
            }
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
