package ru.arc.buildertools

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.autobuild.BuildBookData
import ru.arc.autobuild.BuildBookItems
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BuilderBookAuctionCoordinatorTest : TestBase() {
    private lateinit var fixture: AuctionFixture

    @BeforeEach
    fun setUpAuction() {
        fixture = AuctionFixture()
    }

    @Test
    fun `rejected listing restores the exact book and releases its MySQL lease`() {
        fixture.port.result = BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.REJECTED)

        fixture.sell()

        assertEquals(BuilderBookInstanceStatus.AVAILABLE, fixture.registry.instance.status)
        assertNull(BuilderBookAuctionTokenCodec.read(fixture.player.inventory.itemInMainHand))
        assertEquals(fixture.data, ru.arc.autobuild.BuildBookCodec.read(fixture.player.inventory.itemInMainHand))
        assertFalse(fixture.locked)
        assertTrue(fixture.messages.any { it == "book.auction-rejected" })
    }

    @Test
    fun `successful listing stays locked until the exact auction item is returned`() {
        fixture.port.result = BuilderBookAuctionListingResult.Listed("42")
        fixture.port.removeOnSuccess = true

        fixture.sell()

        assertEquals(BuilderBookInstanceStatus.LISTED, fixture.registry.instance.status)
        assertTrue(fixture.player.inventory.itemInMainHand.type.isAir)
        assertFalse(fixture.locked)
        assertTrue(fixture.messages.any { it == "book.auction-listed" })

        fixture.port.deliver(fixture.player)

        assertEquals(BuilderBookInstanceStatus.AVAILABLE, fixture.registry.instance.status)
        assertEquals(fixture.data, ru.arc.autobuild.BuildBookCodec.read(fixture.player.inventory.itemInMainHand))
        assertNull(BuilderBookAuctionTokenCodec.read(fixture.player.inventory.itemInMainHand))
        assertTrue(fixture.messages.any { it == "book.auction-received" })
    }

    @Test
    fun `a duplicated auction token cannot unlock after the exact lease was released`() {
        fixture.port.result = BuilderBookAuctionListingResult.Listed("42")
        fixture.port.removeOnSuccess = true
        fixture.sell()
        val duplicate = fixture.port.submittedItem()

        fixture.port.deliver(fixture.player)
        fixture.player.inventory.setItemInMainHand(duplicate)
        fixture.port.deliverCurrent(fixture.player)

        assertEquals(BuilderBookInstanceStatus.AVAILABLE, fixture.registry.instance.status)
        assertTrue(BuilderBookAuctionTokenCodec.read(fixture.player.inventory.itemInMainHand) != null)
        assertTrue(fixture.messages.any { it == "book.auction-review" })
    }

    @Test
    fun `ordinary auction guard finds a player book inside a bundle`() {
        val bundle = ItemStack(Material.BUNDLE)
        val meta = bundle.itemMeta as BundleMeta
        meta.setItems(listOf(BuildBookItems.create(fixture.data)))
        bundle.itemMeta = meta

        assertTrue(BuilderBookAuctionItemGuard.containsPlayerCreatedBook(bundle))
        assertFalse(BuilderBookAuctionItemGuard.containsPlayerCreatedBook(ItemStack(Material.STONE)))
    }

    @Test
    fun `moving a listing to expired storage does not release its lease`() {
        fixture.port.result = BuilderBookAuctionListingResult.Listed("42")
        fixture.port.removeOnSuccess = true
        fixture.sell()

        fixture.port.signalRemovalWithoutDelivery(fixture.player)

        assertEquals(BuilderBookInstanceStatus.LISTED, fixture.registry.instance.status)
        assertTrue(fixture.player.inventory.itemInMainHand.type.isAir)
    }

    private inner class AuctionFixture {
        val player = server.addPlayer("BookSeller")
        val blueprint = BuilderBookBlueprint(
            blueprintId = UUID.randomUUID(),
            creatorId = player.uniqueId,
            creatorName = player.name,
            title = "Дом у озера",
            buildingId = "player-${player.uniqueId.toString().replace("-", "")}-auction.schem",
            contentSha256 = "a".repeat(64),
            schematicSha256 = "b".repeat(64),
            blockCount = 24,
            materialTypes = 2,
            materialItems = 24,
            materialCostMinor = 10_000L,
            constructionFeeMinor = 1_500L,
            issuePriceMinor = 11_500L,
            createdAtMillis = 1L,
        ).validated()
        val data = BuildBookData(
            buildingId = blueprint.buildingId,
            title = blueprint.title,
            playerCreated = true,
            creatorId = blueprint.creatorId,
            creatorName = blueprint.creatorName,
            blueprintId = blueprint.blueprintId,
            instanceId = UUID.randomUUID(),
            issuePriceMinor = blueprint.issuePriceMinor,
            contentSha256 = blueprint.contentSha256,
            schematicSha256 = blueprint.schematicSha256,
            blockCount = blueprint.blockCount,
            cooldownSeconds = 0,
        ).validated()
        val registry = AuctionRegistry(blueprint, data)
        val port = AuctionPort()
        val messages = mutableListOf<String>()
        var locked = false
        val coordinator = BuilderBookAuctionCoordinator(
            registry = registry,
            portProvider = { port },
            serverName = "survival",
            runSync = { it() },
            send = { _, path, _ -> messages += path },
            lock = {
                if (locked) false else {
                    locked = true
                    true
                }
            },
            unlock = { locked = false },
            clock = { 10L },
        ).also(BuilderBookAuctionCoordinator::start)

        init {
            player.inventory.setItemInMainHand(BuildBookItems.create(data))
        }

        fun sell() {
            val held = player.inventory.itemInMainHand.clone()
            coordinator.sell(player, data, held, BigDecimal("25000"))
        }
    }
}

private class AuctionPort : BuilderBookAuctionPort {
    var result: BuilderBookAuctionListingResult = BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.REJECTED)
    var removeOnSuccess = false
    private var submitted: ItemStack? = null
    private var stored = false
    private var handler: ((org.bukkit.entity.Player, ItemStack) -> Unit)? = null

    override fun submit(
        player: org.bukkit.entity.Player,
        price: BigDecimal,
        tokenizedItem: ItemStack,
    ): CompletableFuture<BuilderBookAuctionListingResult> {
        submitted = tokenizedItem.clone()
        if (removeOnSuccess && result is BuilderBookAuctionListingResult.Listed) {
            stored = true
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        }
        return CompletableFuture.completedFuture(result)
    }

    override fun contains(token: BuilderBookAuctionToken): Boolean =
        stored && submitted?.let(BuilderBookAuctionTokenCodec::read) == token

    override fun setDeliveryHandler(handler: ((org.bukkit.entity.Player, ItemStack) -> Unit)?) {
        this.handler = handler
    }

    fun deliver(player: org.bukkit.entity.Player) {
        val item = requireNotNull(submitted).clone()
        stored = false
        player.inventory.setItemInMainHand(item)
        handler?.invoke(player, item)
    }

    fun submittedItem(): ItemStack = requireNotNull(submitted).clone()

    fun deliverCurrent(player: org.bukkit.entity.Player) {
        handler?.invoke(player, player.inventory.itemInMainHand)
    }

    fun signalRemovalWithoutDelivery(player: org.bukkit.entity.Player) {
        handler?.invoke(player, requireNotNull(submitted).clone())
    }
}

private class AuctionRegistry(
    private val blueprint: BuilderBookBlueprint,
    data: BuildBookData,
) : BuilderBookRegistry {
    var instance = BuilderBookInstance(
        instanceId = requireNotNull(data.instanceId),
        blueprintId = requireNotNull(data.blueprintId),
        transactionId = UUID.randomUUID(),
        mintedBy = blueprint.creatorId,
        deliveryPlayerId = blueprint.creatorId,
        status = BuilderBookInstanceStatus.AVAILABLE,
        createdAtMillis = 1L,
    ).validated()

    override fun reserveForAuction(
        instanceId: UUID,
        expectedBlueprintId: UUID,
        expectedBuildingId: String,
        expectedSchematicSha256: String,
        leaseId: UUID,
        sellerId: UUID,
        serverName: String,
        now: Long,
    ): CompletableFuture<BuilderBookAuctionReservationResult> {
        if (
            instance.status != BuilderBookInstanceStatus.AVAILABLE || instance.instanceId != instanceId ||
            blueprint.blueprintId != expectedBlueprintId || blueprint.buildingId != expectedBuildingId ||
            blueprint.schematicSha256 != expectedSchematicSha256
        ) {
            return CompletableFuture.completedFuture(BuilderBookAuctionReservationResult.Unavailable)
        }
        instance = instance.copy(
            status = BuilderBookInstanceStatus.LISTED,
            reservationOperationId = leaseId,
            reservationPlayerId = sellerId,
            reservationServer = serverName,
            reservedAtMillis = now,
        ).validated()
        return CompletableFuture.completedFuture(BuilderBookAuctionReservationResult.Reserved(blueprint))
    }

    override fun releaseFromAuction(instanceId: UUID, leaseId: UUID): CompletableFuture<Boolean> {
        if (
            instance.instanceId != instanceId || instance.status != BuilderBookInstanceStatus.LISTED ||
            instance.reservationOperationId != leaseId
        ) return CompletableFuture.completedFuture(false)
        instance = instance.copy(
            status = BuilderBookInstanceStatus.AVAILABLE,
            reservationOperationId = null,
            reservationPlayerId = null,
            reservationServer = null,
            reservedAtMillis = null,
        ).validated()
        return CompletableFuture.completedFuture(true)
    }

    override fun listedForServer(serverName: String): CompletableFuture<List<BuilderBookInstance>> =
        CompletableFuture.completedFuture(listOf(instance).filter {
            it.status == BuilderBookInstanceStatus.LISTED && it.reservationServer == serverName
        })

    override fun initialize() = CompletableFuture.completedFuture(Unit)
    override fun prepareMint(mint: BuilderBookMint) = unsupported<Boolean>()
    override fun hasOpenMint(playerId: UUID) = unsupported<Boolean>()
    override fun transitionMint(expected: BuilderBookMint, next: BuilderBookMint) = unsupported<BuilderBookMint?>()
    override fun issuePaidMint(transactionId: UUID, now: Long) = unsupported<BuilderBookMint>()
    override fun markDelivered(instanceId: UUID, transactionId: UUID, now: Long) = unsupported<Boolean>()
    override fun loadBlueprint(blueprintId: UUID) = CompletableFuture.completedFuture(this.blueprint.takeIf { it.blueprintId == blueprintId })
    override fun loadInstance(instanceId: UUID) = CompletableFuture.completedFuture(instance.takeIf { it.instanceId == instanceId })
    override fun pendingDeliveries(playerId: UUID) = unsupported<List<BuilderBookDelivery>>()
    override fun openMints() = unsupported<List<BuilderBookMint>>()
    override fun reserve(
        instanceId: UUID,
        expectedBlueprintId: UUID,
        expectedBuildingId: String,
        expectedSchematicSha256: String,
        operationId: UUID,
        playerId: UUID,
        serverName: String,
        now: Long,
    ) = unsupported<BuilderBookReservationResult>()
    override fun consume(instanceId: UUID, operationId: UUID, now: Long) = unsupported<Boolean>()
    override fun release(instanceId: UUID, operationId: UUID) = unsupported<Boolean>()
    override fun reservedForServer(serverName: String) = unsupported<List<BuilderBookInstance>>()
    override fun loadMint(transactionId: UUID) = unsupported<BuilderBookMint?>()
    override fun close() = Unit

    private fun <T> unsupported(): CompletableFuture<T> =
        CompletableFuture.failedFuture(UnsupportedOperationException("not used by fixture"))
}
