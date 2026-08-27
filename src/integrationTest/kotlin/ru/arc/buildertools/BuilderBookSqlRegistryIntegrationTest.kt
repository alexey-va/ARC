package ru.arc.buildertools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestEndpoint
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.util.UUID
import java.util.concurrent.TimeUnit

class BuilderBookSqlRegistryIntegrationTest : StringSpec({
    "MySQL migrations, delivery, contention, idempotency and open-mint uniqueness are durable" {
        val settings = MySqlTestSettings(image = "mysql:8.4.10", database = "arc_test")
        MySqlTestService.start(settings).use { mysql ->
            val endpoint = mysql.endpoint
            val mint = paidMint()
            val openMintPlayer = UUID.randomUUID()
            openRegistry(endpoint).use { registry ->
                registry.initialize().await()

                val prepared = mint.preparedVersion()
                registry.prepareMint(prepared).await() shouldBe true
                val started = prepared.withdrawalStarted()
                registry.transitionMint(prepared, started).await() shouldBe started
                registry.transitionMint(started, mint).await() shouldBe mint
                val issued = registry.issuePaidMint(mint.transactionId, 10L).await()
                issued.status shouldBe BuilderBookMintStatus.ISSUED

                val deliveries = registry.pendingDeliveries(mint.playerId).await()
                deliveries shouldHaveSize 1
                deliveries.single().instance.transactionId shouldBe mint.transactionId
                deliveries.single().placement shouldBe mint.placement
                registry.markDelivered(mint.instanceId, UUID.randomUUID(), 11L).await() shouldBe false
                registry.markDelivered(mint.instanceId, mint.transactionId, 11L).await() shouldBe true
                registry.markDelivered(mint.instanceId, mint.transactionId, 12L).await() shouldBe true

                val firstOperation = UUID.randomUUID()
                val secondOperation = UUID.randomUUID()
                val firstReservation = registry.reserve(
                    mint.instanceId,
                    BuilderBookInstance.INITIAL_GENERATION,
                    mint.blueprint.blueprintId,
                    mint.blueprint.buildingId,
                    mint.blueprint.schematicSha256,
                    firstOperation,
                    mint.playerId,
                    "survival",
                    20L,
                )
                val secondReservation = registry.reserve(
                    mint.instanceId,
                    BuilderBookInstance.INITIAL_GENERATION,
                    mint.blueprint.blueprintId,
                    mint.blueprint.buildingId,
                    mint.blueprint.schematicSha256,
                    secondOperation,
                    mint.playerId,
                    "survival",
                    20L,
                )
                val reservationOutcomes = listOf(firstReservation.await(), secondReservation.await())
                reservationOutcomes.count { it is BuilderBookReservationResult.Reserved } shouldBe 1
                reservationOutcomes.count { it == BuilderBookReservationResult.Unavailable } shouldBe 1
                val winner = if (reservationOutcomes[0] is BuilderBookReservationResult.Reserved) {
                    firstOperation
                } else {
                    secondOperation
                }

                registry.consume(mint.instanceId, winner, 30L).await() shouldBe true
                registry.consume(mint.instanceId, winner, 31L).await() shouldBe true
                registry.loadInstance(mint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.CONSUMED

                val sourceMint = paidMint()
                val sourcePrepared = sourceMint.preparedVersion()
                val sourceStarted = sourcePrepared.withdrawalStarted()
                registry.prepareMint(sourcePrepared).await() shouldBe true
                registry.transitionMint(sourcePrepared, sourceStarted).await() shouldBe sourceStarted
                registry.transitionMint(sourceStarted, sourceMint).await() shouldBe sourceMint
                registry.issuePaidMint(sourceMint.transactionId, 40L).await().status shouldBe BuilderBookMintStatus.ISSUED
                registry.markDelivered(sourceMint.instanceId, sourceMint.transactionId, 41L).await() shouldBe true

                val copyPlayer = sourceMint.playerId
                val copyTransaction = UUID.randomUUID()
                val copyPaid = BuilderBookMint(
                    transactionId = copyTransaction,
                    kind = BuilderBookMintKind.COPY,
                    playerId = copyPlayer,
                    blueprint = sourceMint.blueprint,
                    instanceId = UUID.randomUUID(),
                    sourceInstanceId = sourceMint.instanceId,
                    placement = BuilderBookPlacement(180, -2, 1, 3),
                    status = BuilderBookMintStatus.FUNDS_WITHDRAWN,
                    createdAtMillis = 42L,
                    updatedAtMillis = 44L,
                    balanceBeforeMinor = 1_000L,
                    balanceAfterMinor = 885L,
                    evidence = "integration_copy_delta",
                ).validated()
                val copyPrepared = copyPaid.preparedVersion()
                val copyStarted = copyPrepared.withdrawalStarted()
                registry.reserve(
                    sourceMint.instanceId,
                    BuilderBookInstance.INITIAL_GENERATION,
                    sourceMint.blueprint.blueprintId,
                    sourceMint.blueprint.buildingId,
                    sourceMint.blueprint.schematicSha256,
                    copyTransaction,
                    copyPlayer,
                    "survival",
                    42L,
                ).await() shouldBe BuilderBookReservationResult.Reserved(sourceMint.blueprint)
                registry.prepareMint(copyPrepared).await() shouldBe true
                registry.transitionMint(copyPrepared, copyStarted).await() shouldBe copyStarted
                registry.transitionMint(copyStarted, copyPaid).await() shouldBe copyPaid
                registry.issuePaidMint(copyTransaction, 45L).await().status shouldBe BuilderBookMintStatus.ISSUED
                registry.loadInstance(sourceMint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.RESERVED
                registry.markDelivered(copyPaid.instanceId, copyTransaction, 46L).await() shouldBe true
                registry.release(sourceMint.instanceId, copyTransaction).await() shouldBe true
                registry.loadInstance(sourceMint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.AVAILABLE

                val auctionLease = UUID.randomUUID()
                registry.reserveForAuction(
                    sourceMint.instanceId,
                    BuilderBookInstance.INITIAL_GENERATION,
                    sourceMint.blueprint.blueprintId,
                    sourceMint.blueprint.buildingId,
                    sourceMint.blueprint.schematicSha256,
                    auctionLease,
                    copyPlayer,
                    "survival",
                    50L,
                ).await() shouldBe BuilderBookAuctionReservationResult.Reserved(sourceMint.blueprint)
                registry.loadInstance(sourceMint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.LISTED
                registry.listedForServer("survival").await().map { it.instanceId } shouldBe listOf(sourceMint.instanceId)
                registry.reserve(
                    sourceMint.instanceId,
                    BuilderBookInstance.INITIAL_GENERATION,
                    sourceMint.blueprint.blueprintId,
                    sourceMint.blueprint.buildingId,
                    sourceMint.blueprint.schematicSha256,
                    UUID.randomUUID(),
                    copyPlayer,
                    "survival",
                    51L,
                ).await() shouldBe BuilderBookReservationResult.Unavailable
                registry.releaseFromAuction(sourceMint.instanceId, UUID.randomUUID()).await() shouldBe false
                val auctionBuyer = UUID.randomUUID()
                registry.beginAuctionTransfer(
                    sourceMint.instanceId,
                    auctionLease,
                    auctionBuyer,
                    "survival",
                    52L,
                ).await() shouldBe BuilderBookAuctionTransferResult.Pending(2)
                registry.beginAuctionTransfer(
                    sourceMint.instanceId,
                    auctionLease,
                    auctionBuyer,
                    "survival",
                    53L,
                ).await() shouldBe BuilderBookAuctionTransferResult.Pending(2)
                val transferring = checkNotNull(registry.loadInstance(sourceMint.instanceId).await())
                transferring.status shouldBe BuilderBookInstanceStatus.TRANSFER_PENDING
                transferring.ownerId shouldBe auctionBuyer
                transferring.generation shouldBe 2
                registry.completeAuctionTransfer(sourceMint.instanceId, auctionLease, auctionBuyer, 2).await() shouldBe true
                registry.completeAuctionTransfer(sourceMint.instanceId, auctionLease, auctionBuyer, 2).await() shouldBe true
                registry.beginAuctionTransfer(
                    sourceMint.instanceId,
                    auctionLease,
                    auctionBuyer,
                    "survival",
                    54L,
                ).await() shouldBe BuilderBookAuctionTransferResult.Completed(2)
                val transferred = checkNotNull(registry.loadInstance(sourceMint.instanceId).await())
                transferred.status shouldBe BuilderBookInstanceStatus.AVAILABLE
                transferred.ownerId shouldBe auctionBuyer
                transferred.generation shouldBe 2
                registry.reserve(
                    sourceMint.instanceId,
                    BuilderBookInstance.INITIAL_GENERATION,
                    sourceMint.blueprint.blueprintId,
                    sourceMint.blueprint.buildingId,
                    sourceMint.blueprint.schematicSha256,
                    UUID.randomUUID(),
                    copyPlayer,
                    "survival",
                    55L,
                ).await() shouldBe BuilderBookReservationResult.Stale
                val buyerOperation = UUID.randomUUID()
                registry.reserve(
                    sourceMint.instanceId,
                    2,
                    sourceMint.blueprint.blueprintId,
                    sourceMint.blueprint.buildingId,
                    sourceMint.blueprint.schematicSha256,
                    buyerOperation,
                    auctionBuyer,
                    "survival",
                    56L,
                ).await() shouldBe BuilderBookReservationResult.Reserved(sourceMint.blueprint)
                registry.release(sourceMint.instanceId, buyerOperation).await() shouldBe true

                val firstMint = preparedMint(openMintPlayer)
                val secondMint = preparedMint(playerId = firstMint.playerId)
                val mintOutcomes = listOf(registry.prepareMint(firstMint), registry.prepareMint(secondMint)).map { it.await() }
                mintOutcomes.count { it } shouldBe 1
                mintOutcomes.count { !it } shouldBe 1
            }

            endpoint.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM arc_builder_books_schema_history WHERE version = 2")
                }
            }
            openRegistry(endpoint).use { retried ->
                retried.initialize().await()
                retried.loadInstance(mint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.CONSUMED
            }

            openRegistry(endpoint).use { reopened ->
                reopened.initialize().await()
                reopened.loadInstance(mint.instanceId).await()?.status shouldBe BuilderBookInstanceStatus.CONSUMED
                val recovered = reopened.openMints().await().filter { it.playerId == openMintPlayer }
                recovered shouldHaveSize 1
                recovered.single().status shouldBe BuilderBookMintStatus.PREPARED
            }
        }
    }
})

private fun openRegistry(endpoint: MySqlTestEndpoint): BuilderBookSqlRegistry = BuilderBookSqlRegistry(
    SqlRuntime.create(
        SqlConnectionConfig(
            host = endpoint.host,
            port = endpoint.port,
            database = endpoint.database,
            username = endpoint.username,
            password = endpoint.password,
            sslMode = SqlSslMode.DISABLED,
            minimumIdle = 0,
            maximumPoolSize = 4,
            connectionTimeoutMs = 5_000,
            socketTimeoutMs = 10_000,
            validationTimeoutMs = 2_000,
            maxLifetimeMs = 60_000,
            failFast = true,
        ),
        "builder-book-it-${UUID.randomUUID().toString().take(8)}",
    ),
)

private fun preparedMint(playerId: UUID = UUID.randomUUID()): BuilderBookMint {
    val blueprint = BuilderBookBlueprint(
        blueprintId = UUID.randomUUID(),
        creatorId = playerId,
        creatorName = "Builder",
        title = "MySQL fixture",
        buildingId = "player-${playerId.toString().replace("-", "")}-${UUID.randomUUID().toString().take(8)}.schem",
        contentSha256 = "a".repeat(64),
        schematicSha256 = "b".repeat(64),
        blockCount = 10,
        materialTypes = 2,
        materialItems = 10,
        materialCostMinor = 100L,
        constructionFeeMinor = 15L,
        issuePriceMinor = 115L,
        createdAtMillis = 1L,
    ).validated()
    return BuilderBookMint(
        transactionId = UUID.randomUUID(),
        kind = BuilderBookMintKind.CREATE,
        playerId = playerId,
        blueprint = blueprint,
        instanceId = UUID.randomUUID(),
        placement = BuilderBookPlacement(90, 1, 2, -3),
        createdAtMillis = 2L,
    ).validated()
}

private fun paidMint(): BuilderBookMint {
    val prepared = preparedMint()
    return prepared.copy(
        status = BuilderBookMintStatus.FUNDS_WITHDRAWN,
        updatedAtMillis = 4L,
        balanceBeforeMinor = 1_000L,
        balanceAfterMinor = 885L,
        evidence = "integration_exact_delta",
    ).validated()
}

private fun BuilderBookMint.preparedVersion(): BuilderBookMint = copy(
    status = BuilderBookMintStatus.PREPARED,
    updatedAtMillis = createdAtMillis,
    balanceBeforeMinor = null,
    balanceAfterMinor = null,
    evidence = null,
).validated()

private fun BuilderBookMint.withdrawalStarted(): BuilderBookMint = copy(
    status = BuilderBookMintStatus.WITHDRAWAL_STARTED,
    updatedAtMillis = createdAtMillis + 1,
    balanceBeforeMinor = 1_000L,
).validated()

private fun <T> java.util.concurrent.CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)
