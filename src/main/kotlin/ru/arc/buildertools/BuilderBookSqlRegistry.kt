package ru.arc.buildertools

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Authoritative network-wide book ledger. Implementations complete every
 * future off the Paper thread; callers must marshal Bukkit work themselves.
 * Reserve/consume/release and mint transitions are exact, idempotent database
 * compare-and-set operations. An exceptional future has an unknown outcome.
 */
internal interface BuilderBookRegistry : AutoCloseable {
    fun initialize(): CompletableFuture<Unit>
    fun prepareMint(mint: BuilderBookMint): CompletableFuture<Boolean>
    fun hasOpenMint(playerId: UUID): CompletableFuture<Boolean>
    fun transitionMint(expected: BuilderBookMint, next: BuilderBookMint): CompletableFuture<BuilderBookMint?>
    fun issuePaidMint(transactionId: UUID, now: Long): CompletableFuture<BuilderBookMint>
    fun markDelivered(instanceId: UUID, transactionId: UUID, now: Long): CompletableFuture<Boolean>
    fun loadBlueprint(blueprintId: UUID): CompletableFuture<BuilderBookBlueprint?>
    fun loadInstance(instanceId: UUID): CompletableFuture<BuilderBookInstance?>
    fun pendingDeliveries(playerId: UUID): CompletableFuture<List<BuilderBookDelivery>>
    fun openMints(): CompletableFuture<List<BuilderBookMint>>
    fun reserve(
        instanceId: UUID,
        expectedGeneration: Int,
        expectedBlueprintId: UUID,
        expectedBuildingId: String,
        expectedSchematicSha256: String,
        operationId: UUID,
        playerId: UUID,
        serverName: String,
        now: Long,
    ): CompletableFuture<BuilderBookReservationResult>
    fun consume(instanceId: UUID, operationId: UUID, now: Long): CompletableFuture<Boolean>
    fun release(instanceId: UUID, operationId: UUID): CompletableFuture<Boolean>
    fun reservedForServer(serverName: String): CompletableFuture<List<BuilderBookInstance>>
    fun reserveForAuction(
        instanceId: UUID,
        expectedGeneration: Int,
        expectedBlueprintId: UUID,
        expectedBuildingId: String,
        expectedSchematicSha256: String,
        leaseId: UUID,
        sellerId: UUID,
        serverName: String,
        now: Long,
    ): CompletableFuture<BuilderBookAuctionReservationResult>
    fun releaseFromAuction(instanceId: UUID, leaseId: UUID): CompletableFuture<Boolean>
    fun beginAuctionTransfer(
        instanceId: UUID,
        leaseId: UUID,
        recipientId: UUID,
        serverName: String,
        now: Long,
    ): CompletableFuture<BuilderBookAuctionTransferResult>
    fun completeAuctionTransfer(
        instanceId: UUID,
        leaseId: UUID,
        recipientId: UUID,
        generation: Int,
    ): CompletableFuture<Boolean>
    fun listedForServer(serverName: String): CompletableFuture<List<BuilderBookInstance>>
    fun loadMint(transactionId: UUID): CompletableFuture<BuilderBookMint?>
}

/** MySQL owner for the three `arc_builder_book_*` tables and their migration. */
internal class BuilderBookSqlRegistry(
    private val runtime: SqlRuntime,
) : BuilderBookRegistry {
    override fun initialize(): CompletableFuture<Unit> = runtime.executor.submit {
        MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(MIGRATIONS)
        Unit
    }

    override fun prepareMint(mint: BuilderBookMint): CompletableFuture<Boolean> = runtime.executor.write { connection ->
        val checked = mint.validated()
        require(checked.status == BuilderBookMintStatus.PREPARED) { "New builder-book mint must start PREPARED" }
        connection.prepareStatement(INSERT_MINT).use { statement ->
            var index = 1
            statement.setString(index++, checked.transactionId.toString())
            statement.setString(index++, checked.kind.name)
            statement.setString(index++, checked.playerId.toString())
            statement.setString(index++, checked.blueprint.blueprintId.toString())
            statement.setString(index++, checked.instanceId.toString())
            statement.setString(index++, checked.sourceInstanceId?.toString())
            statement.setInt(index++, checked.placement.rotation)
            statement.setInt(index++, checked.placement.offsetX)
            statement.setInt(index++, checked.placement.offsetY)
            statement.setInt(index++, checked.placement.offsetZ)
            statement.setString(index++, checked.blueprint.creatorId.toString())
            statement.setString(index++, checked.blueprint.creatorName)
            statement.setString(index++, checked.blueprint.title)
            statement.setString(index++, checked.blueprint.buildingId)
            statement.setString(index++, checked.blueprint.contentSha256)
            statement.setString(index++, checked.blueprint.schematicSha256)
            statement.setInt(index++, checked.blueprint.blockCount)
            statement.setInt(index++, checked.blueprint.materialTypes)
            statement.setInt(index++, checked.blueprint.materialItems)
            statement.setLong(index++, checked.blueprint.materialCostMinor)
            statement.setLong(index++, checked.blueprint.constructionFeeMinor)
            statement.setLong(index++, checked.blueprint.issuePriceMinor)
            statement.setLong(index++, checked.blueprint.createdAtMillis)
            statement.setString(index++, checked.status.name)
            statement.setString(index++, checked.playerId.toString())
            statement.setLong(index++, checked.createdAtMillis)
            statement.setLong(index, checked.updatedAtMillis)
            try {
                statement.executeUpdate() == 1
            } catch (failure: SQLException) {
                if (!failure.isDuplicateKey()) throw failure
                loadMint(connection, checked.transactionId)?.let { existing ->
                    existing == checked
                } ?: false
            }
        }
    }

    override fun hasOpenMint(playerId: UUID): CompletableFuture<Boolean> = runtime.executor.read { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM arc_builder_book_mints WHERE open_player_uuid = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use(ResultSet::next)
        }
    }

    override fun transitionMint(
        expected: BuilderBookMint,
        next: BuilderBookMint,
    ): CompletableFuture<BuilderBookMint?> = runtime.executor.write { connection ->
        val checkedExpected = expected.validated()
        val checkedNext = next.validated()
        require(checkedExpected.transactionId == checkedNext.transactionId) { "Builder-book mint identity changed" }
        require(checkedExpected.copy(status = checkedNext.status, updatedAtMillis = checkedNext.updatedAtMillis,
            balanceBeforeMinor = checkedNext.balanceBeforeMinor, balanceAfterMinor = checkedNext.balanceAfterMinor,
            refundBalanceBeforeMinor = checkedNext.refundBalanceBeforeMinor,
            refundBalanceAfterMinor = checkedNext.refundBalanceAfterMinor,
            providerTransactionId = checkedNext.providerTransactionId, evidence = checkedNext.evidence) == checkedNext) {
            "Builder-book mint immutable fields changed"
        }
        require(checkedNext.status == checkedExpected.status || checkedNext.status in BuilderBookMint.transitions(checkedExpected.status)) {
            "Illegal builder-book mint transition ${checkedExpected.status} -> ${checkedNext.status}"
        }
        connection.prepareStatement(UPDATE_MINT).use { statement ->
            statement.setString(1, checkedNext.status.name)
            statement.setString(2, checkedNext.playerId.toString().takeUnless { checkedNext.status.terminal })
            statement.setLong(3, checkedNext.updatedAtMillis)
            statement.setNullableLong(4, checkedNext.balanceBeforeMinor)
            statement.setNullableLong(5, checkedNext.balanceAfterMinor)
            statement.setNullableLong(6, checkedNext.refundBalanceBeforeMinor)
            statement.setNullableLong(7, checkedNext.refundBalanceAfterMinor)
            statement.setString(8, checkedNext.providerTransactionId)
            statement.setString(9, checkedNext.evidence)
            statement.setString(10, checkedExpected.transactionId.toString())
            statement.setString(11, checkedExpected.status.name)
            statement.setLong(12, checkedExpected.updatedAtMillis)
            if (statement.executeUpdate() == 1) checkedNext else loadMint(connection, checkedExpected.transactionId)?.takeIf { it == checkedNext }
        }
    }

    override fun issuePaidMint(transactionId: UUID, now: Long): CompletableFuture<BuilderBookMint> = runtime.executor.transaction { connection ->
        val current = requireNotNull(loadMint(connection, transactionId, forUpdate = true)) { "Builder-book mint is missing" }
        if (current.status == BuilderBookMintStatus.ISSUED || current.status == BuilderBookMintStatus.COMPLETED) {
            return@transaction current
        }
        require(current.status == BuilderBookMintStatus.FUNDS_WITHDRAWN) { "Builder-book mint is not paid" }
        when (current.kind) {
            BuilderBookMintKind.CREATE -> insertOrVerifyBlueprint(connection, current.blueprint)
            BuilderBookMintKind.COPY -> {
                require(loadBlueprint(connection, current.blueprint.blueprintId, true) == current.blueprint) {
                    "Builder-book copy blueprint changed"
                }
                val source = loadInstance(connection, checkNotNull(current.sourceInstanceId), true)
                require(
                    source?.blueprintId == current.blueprint.blueprintId &&
                        source.status == BuilderBookInstanceStatus.RESERVED &&
                        source.reservationOperationId == current.transactionId &&
                        source.reservationPlayerId == current.playerId,
                ) { "Builder-book copy source is not reserved by this mint" }
            }
        }
        insertOrVerifyInstance(
            connection,
            BuilderBookInstance(
                instanceId = current.instanceId,
                blueprintId = current.blueprint.blueprintId,
                transactionId = current.transactionId,
                mintedBy = current.playerId,
                deliveryPlayerId = current.playerId,
                ownerId = current.playerId,
                status = BuilderBookInstanceStatus.PENDING_DELIVERY,
                createdAtMillis = now,
            ).validated(),
            current.transactionId,
        )
        val issued = current.advance(BuilderBookMintStatus.ISSUED, now, "instance_pending_delivery")
        check(updateMint(connection, current, issued)) { "Builder-book paid mint transition raced" }
        issued
    }

    override fun markDelivered(instanceId: UUID, transactionId: UUID, now: Long): CompletableFuture<Boolean> =
        runtime.executor.transaction { connection ->
            val instance = loadInstance(connection, instanceId, true) ?: return@transaction false
            if (instance.transactionId != transactionId) return@transaction false
            if (instance.status == BuilderBookInstanceStatus.AVAILABLE) {
                val mint = loadMint(connection, transactionId, true) ?: return@transaction false
                if (mint.status == BuilderBookMintStatus.COMPLETED) return@transaction true
                require(mint.status == BuilderBookMintStatus.ISSUED) { "Builder-book delivery mint state is invalid" }
                return@transaction updateMint(
                    connection,
                    mint,
                    mint.advance(BuilderBookMintStatus.COMPLETED, now, "item_delivery_verified"),
                )
            }
            if (instance.status != BuilderBookInstanceStatus.PENDING_DELIVERY) return@transaction false
            connection.prepareStatement(
                "UPDATE arc_builder_book_instances SET status = 'AVAILABLE' " +
                    "WHERE instance_uuid = ? AND transaction_uuid = ? AND status = 'PENDING_DELIVERY'",
            ).use { statement ->
                statement.setString(1, instanceId.toString())
                statement.setString(2, transactionId.toString())
                check(statement.executeUpdate() == 1) { "Builder-book delivery instance transition raced" }
            }
            val mint = requireNotNull(loadMint(connection, transactionId, true)) { "Builder-book delivery mint is missing" }
            require(mint.status == BuilderBookMintStatus.ISSUED) { "Builder-book delivery mint is not issued" }
            check(updateMint(connection, mint, mint.advance(BuilderBookMintStatus.COMPLETED, now, "item_delivery_verified"))) {
                "Builder-book delivery mint transition raced"
            }
            true
        }

    override fun loadBlueprint(blueprintId: UUID): CompletableFuture<BuilderBookBlueprint?> =
        runtime.executor.read { connection -> loadBlueprint(connection, blueprintId, false) }

    override fun loadInstance(instanceId: UUID): CompletableFuture<BuilderBookInstance?> =
        runtime.executor.read { connection -> loadInstance(connection, instanceId, false) }

    override fun pendingDeliveries(playerId: UUID): CompletableFuture<List<BuilderBookDelivery>> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                "SELECT b.*, " +
                    "i.instance_uuid AS instance_instance_uuid, i.blueprint_uuid AS instance_blueprint_uuid, " +
                    "i.transaction_uuid AS instance_transaction_uuid, " +
                    "i.minted_by_uuid AS instance_minted_by_uuid, i.delivery_player_uuid AS instance_delivery_player_uuid, " +
                    "i.owner_uuid AS instance_owner_uuid, i.generation AS instance_generation, " +
                    "i.status AS instance_status, i.created_at_ms AS instance_created_at_ms, " +
                    "i.reservation_operation_uuid AS instance_reservation_operation_uuid, " +
                    "i.reservation_player_uuid AS instance_reservation_player_uuid, " +
                    "i.reservation_server AS instance_reservation_server, i.reserved_at_ms AS instance_reserved_at_ms, " +
                    "i.last_auction_lease_uuid AS instance_last_auction_lease_uuid, " +
                    "i.consumed_operation_uuid AS instance_consumed_operation_uuid, " +
                    "i.consumed_at_ms AS instance_consumed_at_ms, " +
                    "m.delivery_rotation, m.delivery_offset_x, m.delivery_offset_y, m.delivery_offset_z, " +
                    "m.source_instance_uuid " +
                    "FROM arc_builder_book_instances i " +
                    "JOIN arc_builder_book_blueprints b ON b.blueprint_uuid = i.blueprint_uuid " +
                    "JOIN arc_builder_book_mints m ON m.transaction_uuid = i.transaction_uuid " +
                    "WHERE i.delivery_player_uuid = ? AND i.status = 'PENDING_DELIVERY' AND b.revoked_at_ms IS NULL " +
                    "ORDER BY i.created_at_ms LIMIT 32",
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                BuilderBookDelivery(
                                    blueprint = result.readBlueprint(),
                                    instance = result.readInstance("instance_"),
                                    placement = result.readPlacement(),
                                    sourceInstanceId = result.getString("source_instance_uuid")?.let(UUID::fromString),
                                ).validated(),
                            )
                        }
                    }
                }
            }
        }

    override fun openMints(): CompletableFuture<List<BuilderBookMint>> = runtime.executor.read { connection ->
        connection.prepareStatement(
            "SELECT * FROM arc_builder_book_mints WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'REFUNDED') " +
                "ORDER BY created_at_ms",
        ).use { statement ->
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.readMint()) } }
        }
    }

    override fun reserve(
        instanceId: UUID,
        expectedGeneration: Int,
        expectedBlueprintId: UUID,
        expectedBuildingId: String,
        expectedSchematicSha256: String,
        operationId: UUID,
        playerId: UUID,
        serverName: String,
        now: Long,
    ): CompletableFuture<BuilderBookReservationResult> = runtime.executor.transaction { connection ->
        val instance = loadInstance(connection, instanceId, true) ?: return@transaction BuilderBookReservationResult.Missing
        if (instance.blueprintId != expectedBlueprintId) return@transaction BuilderBookReservationResult.Mismatch
        val blueprint = loadBlueprint(connection, expectedBlueprintId, true) ?: return@transaction BuilderBookReservationResult.Missing
        if (blueprint.buildingId != expectedBuildingId || blueprint.schematicSha256 != expectedSchematicSha256) {
            return@transaction BuilderBookReservationResult.Mismatch
        }
        if (instance.ownerId != playerId || instance.generation != expectedGeneration) {
            return@transaction BuilderBookReservationResult.Stale
        }
        if (instance.status != BuilderBookInstanceStatus.AVAILABLE) return@transaction BuilderBookReservationResult.Unavailable
        require(serverName.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Builder-book server name is invalid" }
        connection.prepareStatement(
            "UPDATE arc_builder_book_instances SET status = 'RESERVED', reservation_operation_uuid = ?, " +
                "reservation_player_uuid = ?, reservation_server = ?, reserved_at_ms = ? " +
                "WHERE instance_uuid = ? AND status = 'AVAILABLE'",
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.setString(2, playerId.toString())
            statement.setString(3, serverName)
            statement.setLong(4, now)
            statement.setString(5, instanceId.toString())
            if (statement.executeUpdate() != 1) BuilderBookReservationResult.Unavailable
            else BuilderBookReservationResult.Reserved(blueprint)
        }
    }

    override fun consume(instanceId: UUID, operationId: UUID, now: Long): CompletableFuture<Boolean> =
        runtime.executor.transaction { connection ->
            val current = loadInstance(connection, instanceId, true) ?: return@transaction false
            if (current.status == BuilderBookInstanceStatus.CONSUMED) {
                return@transaction current.consumedOperationId == operationId
            }
            if (current.status != BuilderBookInstanceStatus.RESERVED || current.reservationOperationId != operationId) {
                return@transaction false
            }
            connection.prepareStatement(
                "UPDATE arc_builder_book_instances SET status = 'CONSUMED', consumed_operation_uuid = ?, consumed_at_ms = ?, " +
                    "reservation_operation_uuid = NULL, reservation_player_uuid = NULL, reservation_server = NULL, reserved_at_ms = NULL " +
                    "WHERE instance_uuid = ? AND status = 'RESERVED' AND reservation_operation_uuid = ?",
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.setLong(2, now)
                statement.setString(3, instanceId.toString())
                statement.setString(4, operationId.toString())
                statement.executeUpdate() == 1
            }
        }

    override fun release(instanceId: UUID, operationId: UUID): CompletableFuture<Boolean> =
        runtime.executor.transaction { connection ->
            val current = loadInstance(connection, instanceId, true) ?: return@transaction false
            if (current.status == BuilderBookInstanceStatus.AVAILABLE) return@transaction true
            if (current.status != BuilderBookInstanceStatus.RESERVED || current.reservationOperationId != operationId) {
                return@transaction false
            }
            connection.prepareStatement(
                "UPDATE arc_builder_book_instances SET status = 'AVAILABLE', reservation_operation_uuid = NULL, " +
                    "reservation_player_uuid = NULL, reservation_server = NULL, reserved_at_ms = NULL " +
                    "WHERE instance_uuid = ? AND status = 'RESERVED' AND reservation_operation_uuid = ?",
            ).use { statement ->
                statement.setString(1, instanceId.toString())
                statement.setString(2, operationId.toString())
                statement.executeUpdate() == 1
            }
        }

    override fun reservedForServer(serverName: String): CompletableFuture<List<BuilderBookInstance>> = runtime.executor.read { connection ->
        connection.prepareStatement(
            "SELECT * FROM arc_builder_book_instances WHERE status = 'RESERVED' AND reservation_server = ? " +
                "ORDER BY reserved_at_ms",
        ).use { statement ->
            statement.setString(1, serverName)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.readInstance()) } }
        }
    }

    override fun reserveForAuction(
        instanceId: UUID,
        expectedGeneration: Int,
        expectedBlueprintId: UUID,
        expectedBuildingId: String,
        expectedSchematicSha256: String,
        leaseId: UUID,
        sellerId: UUID,
        serverName: String,
        now: Long,
    ): CompletableFuture<BuilderBookAuctionReservationResult> = runtime.executor.transaction { connection ->
        val instance = loadInstance(connection, instanceId, true)
            ?: return@transaction BuilderBookAuctionReservationResult.Missing
        if (instance.blueprintId != expectedBlueprintId) {
            return@transaction BuilderBookAuctionReservationResult.Mismatch
        }
        val blueprint = loadBlueprint(connection, expectedBlueprintId, true)
            ?: return@transaction BuilderBookAuctionReservationResult.Missing
        if (blueprint.buildingId != expectedBuildingId || blueprint.schematicSha256 != expectedSchematicSha256) {
            return@transaction BuilderBookAuctionReservationResult.Mismatch
        }
        if (instance.ownerId != sellerId || instance.generation != expectedGeneration) {
            return@transaction BuilderBookAuctionReservationResult.Stale
        }
        if (instance.status != BuilderBookInstanceStatus.AVAILABLE) {
            return@transaction BuilderBookAuctionReservationResult.Unavailable
        }
        require(serverName.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Builder-book server name is invalid" }
        connection.prepareStatement(
            "UPDATE arc_builder_book_instances SET status = 'LISTED', reservation_operation_uuid = ?, " +
                "reservation_player_uuid = ?, reservation_server = ?, reserved_at_ms = ? " +
                "WHERE instance_uuid = ? AND status = 'AVAILABLE'",
        ).use { statement ->
            statement.setString(1, leaseId.toString())
            statement.setString(2, sellerId.toString())
            statement.setString(3, serverName)
            statement.setLong(4, now)
            statement.setString(5, instanceId.toString())
            if (statement.executeUpdate() != 1) BuilderBookAuctionReservationResult.Unavailable
            else BuilderBookAuctionReservationResult.Reserved(blueprint)
        }
    }

    override fun releaseFromAuction(instanceId: UUID, leaseId: UUID): CompletableFuture<Boolean> =
        runtime.executor.transaction { connection ->
            val current = loadInstance(connection, instanceId, true) ?: return@transaction false
            if (current.status != BuilderBookInstanceStatus.LISTED || current.reservationOperationId != leaseId) {
                return@transaction false
            }
            connection.prepareStatement(
                "UPDATE arc_builder_book_instances SET status = 'AVAILABLE', reservation_operation_uuid = NULL, " +
                    "reservation_player_uuid = NULL, reservation_server = NULL, reserved_at_ms = NULL " +
                    "WHERE instance_uuid = ? AND status = 'LISTED' AND reservation_operation_uuid = ?",
            ).use { statement ->
                statement.setString(1, instanceId.toString())
                statement.setString(2, leaseId.toString())
                statement.executeUpdate() == 1
            }
        }

    override fun beginAuctionTransfer(
        instanceId: UUID,
        leaseId: UUID,
        recipientId: UUID,
        serverName: String,
        now: Long,
    ): CompletableFuture<BuilderBookAuctionTransferResult> = runtime.executor.transaction { connection ->
        require(serverName.matches(BuilderBookInstance.SERVER_NAME)) { "Builder-book server name is invalid" }
        val current = loadInstance(connection, instanceId, true)
            ?: return@transaction BuilderBookAuctionTransferResult.Rejected
        if (
            current.status == BuilderBookInstanceStatus.AVAILABLE &&
            current.lastAuctionLeaseId == leaseId && current.ownerId == recipientId
        ) {
            return@transaction BuilderBookAuctionTransferResult.Completed(current.generation)
        }
        if (
            current.status == BuilderBookInstanceStatus.TRANSFER_PENDING &&
            current.reservationOperationId == leaseId && current.reservationPlayerId == recipientId &&
            current.reservationServer == serverName && current.lastAuctionLeaseId == leaseId
        ) {
            return@transaction BuilderBookAuctionTransferResult.Pending(current.generation)
        }
        if (
            current.status != BuilderBookInstanceStatus.LISTED || current.reservationOperationId != leaseId ||
            current.reservationServer != serverName
        ) {
            return@transaction BuilderBookAuctionTransferResult.Rejected
        }
        val nextGeneration = Math.addExact(current.generation, 1)
        connection.prepareStatement(
            "UPDATE arc_builder_book_instances SET status = 'TRANSFER_PENDING', owner_uuid = ?, generation = ?, " +
                "reservation_player_uuid = ?, reserved_at_ms = ?, last_auction_lease_uuid = ? " +
                "WHERE instance_uuid = ? AND status = 'LISTED' AND reservation_operation_uuid = ?",
        ).use { statement ->
            statement.setString(1, recipientId.toString())
            statement.setInt(2, nextGeneration)
            statement.setString(3, recipientId.toString())
            statement.setLong(4, now)
            statement.setString(5, leaseId.toString())
            statement.setString(6, instanceId.toString())
            statement.setString(7, leaseId.toString())
            check(statement.executeUpdate() == 1) { "Builder-book auction transfer transition raced" }
        }
        BuilderBookAuctionTransferResult.Pending(nextGeneration)
    }

    override fun completeAuctionTransfer(
        instanceId: UUID,
        leaseId: UUID,
        recipientId: UUID,
        generation: Int,
    ): CompletableFuture<Boolean> = runtime.executor.transaction { connection ->
        val current = loadInstance(connection, instanceId, true) ?: return@transaction false
        if (
            current.status == BuilderBookInstanceStatus.AVAILABLE && current.lastAuctionLeaseId == leaseId &&
            current.ownerId == recipientId && current.generation == generation
        ) {
            return@transaction true
        }
        if (
            current.status != BuilderBookInstanceStatus.TRANSFER_PENDING ||
            current.reservationOperationId != leaseId || current.reservationPlayerId != recipientId ||
            current.lastAuctionLeaseId != leaseId || current.generation != generation
        ) {
            return@transaction false
        }
        connection.prepareStatement(
            "UPDATE arc_builder_book_instances SET status = 'AVAILABLE', reservation_operation_uuid = NULL, " +
                "reservation_player_uuid = NULL, reservation_server = NULL, reserved_at_ms = NULL " +
                "WHERE instance_uuid = ? AND status = 'TRANSFER_PENDING' AND reservation_operation_uuid = ? " +
                "AND owner_uuid = ? AND generation = ? AND last_auction_lease_uuid = ?",
        ).use { statement ->
            statement.setString(1, instanceId.toString())
            statement.setString(2, leaseId.toString())
            statement.setString(3, recipientId.toString())
            statement.setInt(4, generation)
            statement.setString(5, leaseId.toString())
            statement.executeUpdate() == 1
        }
    }

    override fun listedForServer(serverName: String): CompletableFuture<List<BuilderBookInstance>> = runtime.executor.read { connection ->
        connection.prepareStatement(
            "SELECT * FROM arc_builder_book_instances WHERE status IN ('LISTED', 'TRANSFER_PENDING') AND reservation_server = ? " +
                "ORDER BY reserved_at_ms",
        ).use { statement ->
            statement.setString(1, serverName)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.readInstance()) } }
        }
    }

    override fun loadMint(transactionId: UUID): CompletableFuture<BuilderBookMint?> =
        runtime.executor.read { connection -> loadMint(connection, transactionId) }

    override fun close() {
        runtime.close()
    }

    private fun insertOrVerifyBlueprint(connection: Connection, blueprint: BuilderBookBlueprint) {
        connection.prepareStatement(INSERT_BLUEPRINT).use { statement ->
            var index = 1
            statement.setString(index++, blueprint.blueprintId.toString())
            statement.setString(index++, blueprint.creatorId.toString())
            statement.setString(index++, blueprint.creatorName)
            statement.setString(index++, blueprint.title)
            statement.setString(index++, blueprint.buildingId)
            statement.setString(index++, blueprint.contentSha256)
            statement.setString(index++, blueprint.schematicSha256)
            statement.setInt(index++, blueprint.blockCount)
            statement.setInt(index++, blueprint.materialTypes)
            statement.setInt(index++, blueprint.materialItems)
            statement.setLong(index++, blueprint.materialCostMinor)
            statement.setLong(index++, blueprint.constructionFeeMinor)
            statement.setLong(index++, blueprint.issuePriceMinor)
            statement.setLong(index, blueprint.createdAtMillis)
            try {
                statement.executeUpdate()
            } catch (failure: SQLException) {
                if (!failure.isDuplicateKey()) throw failure
                require(loadBlueprint(connection, blueprint.blueprintId, true) == blueprint) {
                    "Builder-book blueprint identity collision"
                }
            }
        }
    }

    private fun insertOrVerifyInstance(connection: Connection, instance: BuilderBookInstance, transactionId: UUID) {
        connection.prepareStatement(INSERT_INSTANCE).use { statement ->
            statement.setString(1, instance.instanceId.toString())
            statement.setString(2, instance.blueprintId.toString())
            require(instance.transactionId == transactionId) { "Builder-book instance transaction changed" }
            statement.setString(3, instance.transactionId.toString())
            statement.setString(4, instance.mintedBy.toString())
            statement.setString(5, instance.deliveryPlayerId.toString())
            statement.setString(6, instance.ownerId.toString())
            statement.setInt(7, instance.generation)
            statement.setString(8, instance.status.name)
            statement.setLong(9, instance.createdAtMillis)
            try {
                statement.executeUpdate()
            } catch (failure: SQLException) {
                if (!failure.isDuplicateKey()) throw failure
                require(loadInstance(connection, instance.instanceId, true) == instance) {
                    "Builder-book instance identity collision"
                }
            }
        }
    }

    private fun updateMint(connection: Connection, expected: BuilderBookMint, next: BuilderBookMint): Boolean {
        connection.prepareStatement(UPDATE_MINT).use { statement ->
            statement.setString(1, next.status.name)
            statement.setString(2, next.playerId.toString().takeUnless { next.status.terminal })
            statement.setLong(3, next.updatedAtMillis)
            statement.setNullableLong(4, next.balanceBeforeMinor)
            statement.setNullableLong(5, next.balanceAfterMinor)
            statement.setNullableLong(6, next.refundBalanceBeforeMinor)
            statement.setNullableLong(7, next.refundBalanceAfterMinor)
            statement.setString(8, next.providerTransactionId)
            statement.setString(9, next.evidence)
            statement.setString(10, expected.transactionId.toString())
            statement.setString(11, expected.status.name)
            statement.setLong(12, expected.updatedAtMillis)
            return statement.executeUpdate() == 1
        }
    }

    private fun loadBlueprint(connection: Connection, blueprintId: UUID, forUpdate: Boolean): BuilderBookBlueprint? =
        connection.prepareStatement(
            "SELECT * FROM arc_builder_book_blueprints WHERE blueprint_uuid = ? AND revoked_at_ms IS NULL" +
                if (forUpdate) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setString(1, blueprintId.toString())
            statement.executeQuery().use { result -> if (result.next()) result.readBlueprint() else null }
        }

    private fun loadInstance(connection: Connection, instanceId: UUID, forUpdate: Boolean): BuilderBookInstance? =
        connection.prepareStatement(
            "SELECT * FROM arc_builder_book_instances WHERE instance_uuid = ?" + if (forUpdate) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setString(1, instanceId.toString())
            statement.executeQuery().use { result -> if (result.next()) result.readInstance() else null }
        }

    private fun loadMint(connection: Connection, transactionId: UUID, forUpdate: Boolean = false): BuilderBookMint? =
        connection.prepareStatement(
            "SELECT * FROM arc_builder_book_mints WHERE transaction_uuid = ?" + if (forUpdate) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setString(1, transactionId.toString())
            statement.executeQuery().use { result -> if (result.next()) result.readMint() else null }
        }

    private fun ResultSet.readBlueprint(prefix: String = ""): BuilderBookBlueprint = BuilderBookBlueprint(
        blueprintId = UUID.fromString(getString("${prefix}blueprint_uuid")),
        creatorId = UUID.fromString(getString("${prefix}creator_uuid")),
        creatorName = getString("${prefix}creator_name"),
        title = getString("${prefix}title"),
        buildingId = getString("${prefix}building_id"),
        contentSha256 = getString("${prefix}content_sha256"),
        schematicSha256 = getString("${prefix}schematic_sha256"),
        blockCount = getInt("${prefix}block_count"),
        materialTypes = getInt("${prefix}material_types"),
        materialItems = getInt("${prefix}material_items"),
        materialCostMinor = getLong("${prefix}material_cost_minor"),
        constructionFeeMinor = getLong("${prefix}construction_fee_minor"),
        issuePriceMinor = getLong("${prefix}issue_price_minor"),
        createdAtMillis = getLong("${prefix}created_at_ms"),
    ).validated()

    private fun ResultSet.readInstance(prefix: String = ""): BuilderBookInstance = BuilderBookInstance(
        instanceId = UUID.fromString(getString("${prefix}instance_uuid")),
        blueprintId = UUID.fromString(getString("${prefix}blueprint_uuid")),
        transactionId = UUID.fromString(getString("${prefix}transaction_uuid")),
        mintedBy = UUID.fromString(getString("${prefix}minted_by_uuid")),
        deliveryPlayerId = UUID.fromString(getString("${prefix}delivery_player_uuid")),
        ownerId = UUID.fromString(getString("${prefix}owner_uuid")),
        generation = getInt("${prefix}generation"),
        status = BuilderBookInstanceStatus.valueOf(getString("${prefix}status")),
        createdAtMillis = getLong("${prefix}created_at_ms"),
        reservationOperationId = getString("${prefix}reservation_operation_uuid")?.let(UUID::fromString),
        reservationPlayerId = getString("${prefix}reservation_player_uuid")?.let(UUID::fromString),
        reservationServer = getString("${prefix}reservation_server"),
        reservedAtMillis = getNullableLong("${prefix}reserved_at_ms"),
        consumedOperationId = getString("${prefix}consumed_operation_uuid")?.let(UUID::fromString),
        consumedAtMillis = getNullableLong("${prefix}consumed_at_ms"),
        lastAuctionLeaseId = getString("${prefix}last_auction_lease_uuid")?.let(UUID::fromString),
    ).validated()

    private fun ResultSet.readMint(): BuilderBookMint = BuilderBookMint(
        transactionId = UUID.fromString(getString("transaction_uuid")),
        kind = BuilderBookMintKind.valueOf(getString("kind")),
        playerId = UUID.fromString(getString("player_uuid")),
        blueprint = BuilderBookBlueprint(
            blueprintId = UUID.fromString(getString("blueprint_uuid")),
            creatorId = UUID.fromString(getString("creator_uuid")),
            creatorName = getString("creator_name"),
            title = getString("title"),
            buildingId = getString("building_id"),
            contentSha256 = getString("content_sha256"),
            schematicSha256 = getString("schematic_sha256"),
            blockCount = getInt("block_count"),
            materialTypes = getInt("material_types"),
            materialItems = getInt("material_items"),
            materialCostMinor = getLong("material_cost_minor"),
            constructionFeeMinor = getLong("construction_fee_minor"),
            issuePriceMinor = getLong("issue_price_minor"),
            createdAtMillis = getLong("blueprint_created_at_ms"),
        ).validated(),
        instanceId = UUID.fromString(getString("instance_uuid")),
        sourceInstanceId = getString("source_instance_uuid")?.let(UUID::fromString),
        placement = readPlacement(),
        status = BuilderBookMintStatus.valueOf(getString("status")),
        createdAtMillis = getLong("created_at_ms"),
        updatedAtMillis = getLong("updated_at_ms"),
        balanceBeforeMinor = getNullableLong("balance_before_minor"),
        balanceAfterMinor = getNullableLong("balance_after_minor"),
        refundBalanceBeforeMinor = getNullableLong("refund_balance_before_minor"),
        refundBalanceAfterMinor = getNullableLong("refund_balance_after_minor"),
        providerTransactionId = getString("provider_transaction_id"),
        evidence = getString("evidence"),
    ).validated()

    private fun ResultSet.readPlacement(): BuilderBookPlacement = BuilderBookPlacement(
        rotation = getInt("delivery_rotation"),
        offsetX = getInt("delivery_offset_x"),
        offsetY = getInt("delivery_offset_y"),
        offsetZ = getInt("delivery_offset_z"),
    ).validated()

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)
    }

    private fun ResultSet.getNullableLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

    private fun SQLException.isDuplicateKey(): Boolean = sqlState == "23000" && errorCode == 1062

    companion object {
        const val MIGRATION_NAMESPACE = "arc_builder_books"
        const val CURRENT_SCHEMA_VERSION = 2

        val MIGRATIONS = listOf(
            SqlMigration(
                version = 1,
                description = "Create authoritative builder book blueprint, instance and mint ledgers",
                statements = listOf(
                    """
                    CREATE TABLE IF NOT EXISTS arc_builder_book_blueprints (
                        blueprint_uuid CHAR(36) NOT NULL,
                        creator_uuid CHAR(36) NOT NULL,
                        creator_name VARCHAR(16) NOT NULL,
                        title VARCHAR(48) NOT NULL,
                        building_id VARCHAR(160) NOT NULL,
                        content_sha256 CHAR(64) NOT NULL,
                        schematic_sha256 CHAR(64) NOT NULL,
                        block_count INT UNSIGNED NOT NULL,
                        material_types SMALLINT UNSIGNED NOT NULL,
                        material_items INT UNSIGNED NOT NULL,
                        material_cost_minor BIGINT UNSIGNED NOT NULL,
                        construction_fee_minor BIGINT UNSIGNED NOT NULL,
                        issue_price_minor BIGINT UNSIGNED NOT NULL,
                        created_at_ms BIGINT UNSIGNED NOT NULL,
                        revoked_at_ms BIGINT UNSIGNED NULL,
                        PRIMARY KEY (blueprint_uuid),
                        KEY idx_arc_builder_blueprint_creator (creator_uuid, created_at_ms),
                        KEY idx_arc_builder_blueprint_building (building_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS arc_builder_book_mints (
                        transaction_uuid CHAR(36) NOT NULL,
                        kind VARCHAR(16) NOT NULL,
                        player_uuid CHAR(36) NOT NULL,
                        blueprint_uuid CHAR(36) NOT NULL,
                        instance_uuid CHAR(36) NOT NULL,
                        source_instance_uuid CHAR(36) NULL,
                        delivery_rotation SMALLINT NOT NULL,
                        delivery_offset_x SMALLINT NOT NULL,
                        delivery_offset_y SMALLINT NOT NULL,
                        delivery_offset_z SMALLINT NOT NULL,
                        creator_uuid CHAR(36) NOT NULL,
                        creator_name VARCHAR(16) NOT NULL,
                        title VARCHAR(48) NOT NULL,
                        building_id VARCHAR(160) NOT NULL,
                        content_sha256 CHAR(64) NOT NULL,
                        schematic_sha256 CHAR(64) NOT NULL,
                        block_count INT UNSIGNED NOT NULL,
                        material_types SMALLINT UNSIGNED NOT NULL,
                        material_items INT UNSIGNED NOT NULL,
                        material_cost_minor BIGINT UNSIGNED NOT NULL,
                        construction_fee_minor BIGINT UNSIGNED NOT NULL,
                        issue_price_minor BIGINT UNSIGNED NOT NULL,
                        blueprint_created_at_ms BIGINT UNSIGNED NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        open_player_uuid CHAR(36) NULL,
                        balance_before_minor BIGINT NULL,
                        balance_after_minor BIGINT NULL,
                        refund_balance_before_minor BIGINT NULL,
                        refund_balance_after_minor BIGINT NULL,
                        provider_transaction_id VARCHAR(160) NULL,
                        evidence VARCHAR(160) NULL,
                        created_at_ms BIGINT UNSIGNED NOT NULL,
                        updated_at_ms BIGINT UNSIGNED NOT NULL,
                        PRIMARY KEY (transaction_uuid),
                        UNIQUE KEY uq_arc_builder_mint_instance (instance_uuid),
                        UNIQUE KEY uq_arc_builder_mint_open_player (open_player_uuid),
                        KEY idx_arc_builder_mint_player_status (player_uuid, status),
                        KEY idx_arc_builder_mint_status_time (status, updated_at_ms)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS arc_builder_book_instances (
                        instance_uuid CHAR(36) NOT NULL,
                        blueprint_uuid CHAR(36) NOT NULL,
                        transaction_uuid CHAR(36) NOT NULL,
                        minted_by_uuid CHAR(36) NOT NULL,
                        delivery_player_uuid CHAR(36) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        reservation_operation_uuid CHAR(36) NULL,
                        reservation_player_uuid CHAR(36) NULL,
                        reservation_server VARCHAR(64) NULL,
                        reserved_at_ms BIGINT UNSIGNED NULL,
                        consumed_operation_uuid CHAR(36) NULL,
                        consumed_at_ms BIGINT UNSIGNED NULL,
                        created_at_ms BIGINT UNSIGNED NOT NULL,
                        PRIMARY KEY (instance_uuid),
                        UNIQUE KEY uq_arc_builder_instance_transaction (transaction_uuid),
                        UNIQUE KEY uq_arc_builder_instance_reservation (reservation_operation_uuid),
                        KEY idx_arc_builder_instance_delivery (delivery_player_uuid, status),
                        KEY idx_arc_builder_instance_reservation_server (reservation_server, status),
                        CONSTRAINT fk_arc_builder_instance_blueprint FOREIGN KEY (blueprint_uuid)
                            REFERENCES arc_builder_book_blueprints (blueprint_uuid) ON DELETE RESTRICT,
                        CONSTRAINT fk_arc_builder_instance_mint FOREIGN KEY (transaction_uuid)
                            REFERENCES arc_builder_book_mints (transaction_uuid) ON DELETE RESTRICT
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.trimIndent(),
                ),
            ),
            SqlMigration(
                version = CURRENT_SCHEMA_VERSION,
                description = "Bind builder books to one owner and rotate their generation after auction transfer",
                statements = buildList {
                    addAll(
                        addColumnIfMissing(
                            column = "owner_uuid",
                            definition = "owner_uuid CHAR(36) NULL AFTER delivery_player_uuid",
                        ),
                    )
                    addAll(
                        addColumnIfMissing(
                            column = "generation",
                            definition = "generation INT UNSIGNED NOT NULL DEFAULT 1 AFTER owner_uuid",
                        ),
                    )
                    addAll(
                        addColumnIfMissing(
                            column = "last_auction_lease_uuid",
                            definition = "last_auction_lease_uuid CHAR(36) NULL AFTER reserved_at_ms",
                        ),
                    )
                    add("UPDATE arc_builder_book_instances SET owner_uuid = delivery_player_uuid WHERE owner_uuid IS NULL")
                    add("ALTER TABLE arc_builder_book_instances MODIFY owner_uuid CHAR(36) NOT NULL")
                },
            ),
        )

        private fun addColumnIfMissing(column: String, definition: String): List<String> {
            require(column.matches(Regex("[a-z_]{1,64}"))) { "Unsafe builder-book migration column" }
            require(definition.matches(Regex("[A-Za-z0-9_() ]{1,256}"))) { "Unsafe builder-book migration definition" }
            val variable = "@arc_builder_${column}_ddl"
            val statement = "arc_builder_${column}_stmt"
            val alter = "ALTER TABLE arc_builder_book_instances ADD COLUMN $definition"
            return listOf(
                "SET $variable = (SELECT IF(COUNT(*) = 0, '$alter', 'SELECT 1') " +
                    "FROM information_schema.columns WHERE table_schema = DATABASE() " +
                    "AND table_name = 'arc_builder_book_instances' AND column_name = '$column')",
                "PREPARE $statement FROM $variable",
                "EXECUTE $statement",
                "DEALLOCATE PREPARE $statement",
            )
        }

        private const val INSERT_MINT =
            "INSERT INTO arc_builder_book_mints (transaction_uuid, kind, player_uuid, blueprint_uuid, instance_uuid, " +
                "source_instance_uuid, " +
                "delivery_rotation, delivery_offset_x, delivery_offset_y, delivery_offset_z, " +
                "creator_uuid, creator_name, title, building_id, content_sha256, schematic_sha256, block_count, " +
                "material_types, material_items, material_cost_minor, construction_fee_minor, issue_price_minor, " +
                "blueprint_created_at_ms, status, open_player_uuid, created_at_ms, updated_at_ms) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        private const val UPDATE_MINT =
            "UPDATE arc_builder_book_mints SET status = ?, open_player_uuid = ?, updated_at_ms = ?, balance_before_minor = ?, " +
                "balance_after_minor = ?, refund_balance_before_minor = ?, refund_balance_after_minor = ?, " +
                "provider_transaction_id = ?, evidence = ? WHERE transaction_uuid = ? AND status = ? AND updated_at_ms = ?"

        private const val INSERT_BLUEPRINT =
            "INSERT INTO arc_builder_book_blueprints (blueprint_uuid, creator_uuid, creator_name, title, building_id, " +
                "content_sha256, schematic_sha256, block_count, material_types, material_items, material_cost_minor, " +
                "construction_fee_minor, issue_price_minor, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        private const val INSERT_INSTANCE =
            "INSERT INTO arc_builder_book_instances (instance_uuid, blueprint_uuid, transaction_uuid, minted_by_uuid, " +
                "delivery_player_uuid, owner_uuid, generation, status, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
