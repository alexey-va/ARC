package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import dev.lone.itemsadder.api.Events.FurniturePlaceSuccessEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

class FurnitureGalleryPurchaseListenerTest : StringSpec({
    "plugin-spawned furniture without a player is ignored by purchase placement tracking" {
        val purchases = mockk<ShopPurchaseService>()
        val gallery = mockk<FurnitureGalleryInteractionRuntime>()
        val placed = mockk<FurniturePlaceSuccessEvent>()
        every { placed.player as Player? } returns null
        val listener = FurnitureGalleryPurchaseListener(purchases, gallery,
            currentTick = { error("Plugin placement must not update player placement ticks") })

        listener.onFurniturePlaced(placed)

        verify { purchases wasNot Called }
        verify { gallery wasNot Called }
    }

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

    "WorldGuard pre-cancellation requires an exact gallery target with a current buy offer" {
        FurnitureGalleryInteractionPolicy.shouldPreCancelExactPurchaseClick(
            worldName = FURNITURE_GALLERY_WORLD,
            rightClick = true,
            mainHand = true,
            exactGalleryFurniture = true,
            hasCurrentPurchaseOffer = true,
        ) shouldBe true

        FurnitureGalleryInteractionPolicy.shouldPreCancelExactPurchaseClick(
            worldName = "rc_origin_spawn",
            rightClick = true,
            mainHand = true,
            exactGalleryFurniture = true,
            hasCurrentPurchaseOffer = true,
        ) shouldBe false
        FurnitureGalleryInteractionPolicy.shouldPreCancelExactPurchaseClick(
            worldName = FURNITURE_GALLERY_WORLD,
            rightClick = true,
            mainHand = true,
            exactGalleryFurniture = false,
            hasCurrentPurchaseOffer = true,
        ) shouldBe false
        FurnitureGalleryInteractionPolicy.shouldPreCancelExactPurchaseClick(
            worldName = FURNITURE_GALLERY_WORLD,
            rightClick = true,
            mainHand = true,
            exactGalleryFurniture = true,
            hasCurrentPurchaseOffer = false,
        ) shouldBe false
        FurnitureGalleryInteractionPolicy.shouldPreCancelExactPurchaseClick(
            worldName = FURNITURE_GALLERY_WORLD,
            rightClick = true,
            mainHand = false,
            exactGalleryFurniture = true,
            hasCurrentPurchaseOffer = true,
        ) shouldBe false
        FurnitureGalleryInteractionPolicy.shouldPreCancelExactPurchaseClick(
            worldName = FURNITURE_GALLERY_WORLD,
            rightClick = false,
            mainHand = true,
            exactGalleryFurniture = true,
            hasCurrentPurchaseOffer = true,
        ) shouldBe false
    }

    "already-cancelled protection events still reach gallery routing" {
        // No ignoreCancelled opt-out on the Bukkit routes: WorldGuard cancellation
        // remains in place while the exact indexed furniture may open its menu.
        // The LOWEST gate makes the exact shop click cancelled before WG's
        // NORMAL ignoreCancelled handler; HIGHEST still observes it afterwards.
        val listenerMethods = FurnitureGalleryPurchaseListener::class.java.declaredMethods
            .associateBy { it.name }
        listOf("onEntityInteractProtection", "onEntityInteractAtProtection").forEach { methodName ->
            val handler = listenerMethods.getValue(methodName).getAnnotation(EventHandler::class.java)
            handler.priority shouldBe EventPriority.LOWEST
            handler.ignoreCancelled shouldBe false
        }
        listenerMethods.values
            .filter { it.getAnnotation(EventHandler::class.java)?.priority == EventPriority.LOWEST }
            .map { it.name }
            .toSet() shouldBe setOf("onEntityInteractProtection", "onEntityInteractAtProtection", "onBlockInteractCapture")

        listOf("onEntityInteract", "onEntityInteractAt", "onBlockInteract").forEach { methodName ->
            val handler = listenerMethods.getValue(methodName).getAnnotation(EventHandler::class.java)
            handler.priority shouldBe EventPriority.HIGHEST
            handler.ignoreCancelled shouldBe false
        }

        listenerMethods.getValue("onItemsAdderFurnitureInteract")
            .getAnnotation(EventHandler::class.java)
            .priority shouldBe EventPriority.HIGHEST
    }

    "placing furniture cannot open a purchase even if the ray already intersects a listed model" {
        val purchases = mockk<ShopPurchaseService>()
        val gallery = mockk<FurnitureGalleryInteractionRuntime>()
        val player = mockk<Player>()
        val world = mockk<World>()
        val event = mockk<PlayerInteractEvent>(relaxed = true)
        every { player.world } returns world
        every { player.uniqueId } returns UUID.randomUUID()
        every { world.name } returns FURNITURE_GALLERY_WORLD
        every { event.player } returns player
        val listener = FurnitureGalleryPurchaseListener(purchases, gallery, { true }, { 100 })

        listener.onBlockInteractCapture(event)
        listener.onBlockInteract(event)

        verify(exactly = 0) { gallery.targetInSight(any(), any()) }
        verify(exactly = 0) { purchases.openFurniturePurchaseMenu(any(), any()) }
        verify(exactly = 0) { event.isCancelled = true }
    }

    "a floor that becomes furniture during a click is not retroactively a purchase target" {
        val purchases = mockk<ShopPurchaseService>()
        val gallery = mockk<FurnitureGalleryInteractionRuntime>()
        val player = mockk<Player>()
        val world = mockk<World>()
        val event = mockk<PlayerInteractEvent>(relaxed = true)
        every { player.world } returns world
        every { player.uniqueId } returns UUID.randomUUID()
        every { world.name } returns FURNITURE_GALLERY_WORLD
        every { event.player } returns player
        every { event.hand } returns EquipmentSlot.HAND
        every { event.action } returns Action.RIGHT_CLICK_AIR
        every { event.clickedBlock } returns null
        every { gallery.targetInSight(player, 5.0) } returns null
        val listener = FurnitureGalleryPurchaseListener(purchases, gallery, { false }, { 100 })
        listener.onBlockInteractCapture(event)
        every { gallery.targetInSight(player, 5.0) } returns FurnitureGallerySightTarget(
            mockk(), "arc:oak_chair", "native:new-chair", 2.0,
        )
        listener.onBlockInteract(event)
        verify(exactly = 1) { gallery.targetInSight(player, 5.0) }
        verify(exactly = 0) { purchases.openFurniturePurchaseMenu(any(), any()) }
    }

    "a separate right click opens the ray-selected offer after placement, without any Interaction entity" {
        val purchases = mockk<ShopPurchaseService>()
        val gallery = mockk<FurnitureGalleryInteractionRuntime>()
        val player = mockk<Player>()
        val world = mockk<World>()
        val root = mockk<Entity>()
        val placed = mockk<FurniturePlaceSuccessEvent>()
        val event = mockk<PlayerInteractEvent>(relaxed = true)
        var tick = 100
        every { player.world } returns world
        every { player.uniqueId } returns UUID.randomUUID()
        every { world.name } returns FURNITURE_GALLERY_WORLD
        every { placed.player } returns player
        every { event.player } returns player
        every { event.hand } returns EquipmentSlot.HAND
        every { event.action } returns Action.RIGHT_CLICK_AIR
        every { event.clickedBlock } returns null
        every { gallery.targetInSight(player, 5.0) } returns FurnitureGallerySightTarget(
            root, "arc:oak_chair", "native:chair", 2.0,
        )
        every { purchases.hasFurniturePurchaseMenu("arc:oak_chair") } returns true
        every { purchases.openFurniturePurchaseMenu(player, "arc:oak_chair") } returns FurnitureShopMenuOpenResult.OPENED
        val listener = FurnitureGalleryPurchaseListener(purchases, gallery, { false }, { tick })
        listener.onBlockInteractCapture(event)
        listener.onFurniturePlaced(placed)
        listener.onBlockInteract(event)
        verify(exactly = 0) { purchases.openFurniturePurchaseMenu(any(), any()) }

        tick++
        listener.onBlockInteractCapture(event)
        listener.onBlockInteract(event)
        verify(exactly = 1) { purchases.openFurniturePurchaseMenu(player, "arc:oak_chair") }
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
