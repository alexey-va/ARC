package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.entity.Entity
import io.mockk.mockk
import java.util.UUID

class FurnitureGalleryPurchaseListenerTest : StringSpec({
    "only main-hand right-clicks in the authored gallery can reach purchase routing" {
        FurnitureGalleryInteractionPolicy.accepts(
            worldName = FURNITURE_GALLERY_WORLD,
            rightClick = true,
            mainHand = true,
        ) shouldBe true
        FurnitureGalleryInteractionPolicy.accepts(
            worldName = "rc_origin_spawn",
            rightClick = true,
            mainHand = true,
        ) shouldBe false
        FurnitureGalleryInteractionPolicy.accepts(
            worldName = FURNITURE_GALLERY_WORLD,
            rightClick = false,
            mainHand = true,
        ) shouldBe false
        FurnitureGalleryInteractionPolicy.accepts(
            worldName = FURNITURE_GALLERY_WORLD,
            rightClick = true,
            mainHand = false,
        ) shouldBe false
    }

    "already-cancelled protection events still reach gallery routing" {
        // No ignoreCancelled opt-out on the Bukkit routes: WorldGuard cancellation
        // remains in place while the exact indexed furniture may open its menu.
        val listenerMethods = FurnitureGalleryPurchaseListener::class.java.declaredMethods
            .associateBy { it.name }
        listOf("onEntityInteract", "onEntityInteractAt", "onBlockInteract").forEach { methodName ->
            val handler = listenerMethods.getValue(methodName).getAnnotation(EventHandler::class.java)
            handler.priority shouldBe EventPriority.HIGHEST
            handler.ignoreCancelled shouldBe false
        }

        listenerMethods.getValue("onItemsAdderFurnitureInteract")
            .getAnnotation(EventHandler::class.java)
            .priority shouldBe EventPriority.HIGHEST
    }

    "ItemsAdder routing prefers its exact furniture root and falls back to the event entity" {
        val furnitureRoot = mockk<Entity>()
        val hitboxChild = mockk<Entity>()

        exactItemsAdderFurnitureRoot(furnitureRoot, hitboxChild) shouldBe furnitureRoot
        exactItemsAdderFurnitureRoot(null, hitboxChild) shouldBe hitboxChild
    }

    "paired entity, block, and ItemsAdder routes open once per global server tick" {
        val deduplicator = FurnitureGalleryClickDeduplicator()
        val playerId = UUID.fromString("7f1f6e80-450e-43c0-91e2-b2b91f7aa9c0")
        val otherPlayerId = UUID.fromString("7f1f6e80-450e-43c0-91e2-b2b91f7aa9c1")
        val key = FurnitureGalleryClickKey(playerId, "arc:oak_chair", "furniture-root")

        deduplicator.first(key, tick = 100) shouldBe true
        deduplicator.first(key, tick = 100) shouldBe false
        deduplicator.first(key.copy(targetIdentity = "other-furniture-root"), tick = 100) shouldBe true
        deduplicator.first(key.copy(playerId = otherPlayerId), tick = 100) shouldBe true
        deduplicator.first(key, tick = 101) shouldBe true
    }
})
