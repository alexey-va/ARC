package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountPurchaseCoordinatorTest : StringSpec({
    "successful level purchase charges once and completes the durable journal" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.wallet.balanceMinor shouldBe 5_000_000L
        fixture.ownership.level shouldBe 1
        fixture.wallet.withdrawals shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
    }

    "failed ownership persistence refunds the exact purchase once" {
        val fixture = PurchaseFixture().also { it.ownership.failWrites = true }
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.PersistenceFailedRefunded
        fixture.wallet.balanceMinor shouldBe 10_000_000L
        fixture.wallet.withdrawals shouldBe 1
        fixture.wallet.deposits shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.REFUNDED
    }

    "non-sequential upgrade is rejected without charging or journaling" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 2) { result = it }

        result shouldBe MountPurchaseResult.InvalidLevel
        fixture.wallet.balanceMinor shouldBe 10_000_000L
        fixture.wallet.withdrawals shouldBe 0
        fixture.journal.records() shouldBe emptyList()
    }

    "glow purchase requires an unlocked mount" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseGlow(fixture.subject(), fixture.mount) { result = it }

        result shouldBe MountPurchaseResult.NotUnlocked
        fixture.wallet.withdrawals shouldBe 0
    }

    "ability purchase charges once and persists its exact permission" {
        val fixture = PurchaseFixture().also { it.ownership.level = 1 }
        val ability = checkNotNull(fixture.mount.ability("night-vision"))
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseAbility(fixture.subject(), fixture.mount, ability) { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.ownership.abilityPermissions shouldBe setOf(fixture.mount.abilityPermission(ability.id))
        fixture.wallet.withdrawals shouldBe 1
        fixture.journal.records().single().kind shouldBe MountPurchaseKind.ABILITY
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
    }

    "unavailable balance cancels the prepared record without blocking future purchases" {
        val fixture = PurchaseFixture().also { it.wallet.balanceAvailable = false }
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.EconomyUnavailable
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.CANCELLED
        fixture.journal.hasOpenPurchase(fixture.playerId) shouldBe false
    }

    "ambiguous withdrawal is quarantined and cannot be purchased twice" {
        val fixture = PurchaseFixture().also { it.wallet.ambiguousWithdrawal = true }
        var first: MountPurchaseResult? = null
        var second: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { first = it }
        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { second = it }

        first shouldBe MountPurchaseResult.ManualReview
        second shouldBe MountPurchaseResult.ManualReview
        fixture.wallet.withdrawals shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.MANUAL_REVIEW
    }

    "startup recovery reapplies permission after a proven withdrawal" {
        val fixture = PurchaseFixture()
        val record = fixture.preparedRecord()
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000_000L,
                balanceAfterMinor = 5_000_000L,
                evidence = "exact_balance_delta",
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.ownership.level shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        manual shouldBe emptyList()
        fixture.wallet.withdrawals shouldBe 0
    }

    "startup recovery uses exact provider history after an interrupted withdrawal call" {
        val fixture = PurchaseFixture().also {
            it.wallet.historyTransactionId = "91234"
            it.wallet.historyTransactionAtMillis = 2L
            it.wallet.historyAmountMinor = -5_000_000L
        }
        val record = fixture.preparedRecord()
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.wallet.historyReasons shouldBe listOf("arc-mount:${record.transactionId}")
        fixture.wallet.historyNotBeforeMillis shouldBe listOf(record.createdAt)
        fixture.ownership.level shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        manual shouldBe emptyList()
    }

    "manual review history search includes the provider call that preceded quarantine" {
        val fixture = PurchaseFixture().also {
            it.wallet.historyTransactionId = "91234"
            it.wallet.historyTransactionAtMillis = 2L
            it.wallet.historyAmountMinor = -5_000_000L
        }
        val record = fixture.preparedRecord()
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.MANUAL_REVIEW,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000_000L,
                evidence = "provider_threw",
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.wallet.historyNotBeforeMillis shouldBe listOf(record.createdAt)
        fixture.ownership.level shouldBe 1
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        manual shouldBe emptyList()
    }

    "manual review refund search includes the provider call that preceded quarantine" {
        val fixture = PurchaseFixture().also {
            it.wallet.historyTransactionId = "92345"
            it.wallet.historyTransactionAtMillis = 5L
            it.wallet.historyAmountMinor = 5_000_000L
        }
        val record = fixture.preparedRecord()
        val withdrawn =
            record.copy(
                status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000_000L,
                balanceAfterMinor = 5_000_000L,
                evidence = "exact_balance_delta",
            )
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(withdrawn) shouldBe true
        fixture.journal.persist(
            withdrawn.copy(
                status = MountPurchaseJournalStatus.OWNERSHIP_STARTED,
                updatedAt = 4L,
                evidence = "permission_write_started",
            ),
        ) shouldBe true
        fixture.journal.persist(
            withdrawn.copy(
                status = MountPurchaseJournalStatus.REFUND_STARTED,
                updatedAt = 5L,
                refundBalanceBeforeMinor = 5_000_000L,
                evidence = "permission_not_applied",
            ),
        ) shouldBe true
        fixture.journal.persist(
            withdrawn.copy(
                status = MountPurchaseJournalStatus.MANUAL_REVIEW,
                updatedAt = 6L,
                refundBalanceBeforeMinor = 5_000_000L,
                evidence = "provider_threw",
            ),
        ) shouldBe true
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.wallet.historyReasons shouldBe listOf("arc-mount-refund:${record.transactionId}")
        fixture.wallet.historyNotBeforeMillis shouldBe listOf(record.createdAt)
        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.REFUNDED
        manual shouldBe emptyList()
    }

    "manual review self-heals when the exact permission is later present" {
        val fixture = PurchaseFixture()
        val record = fixture.preparedRecord()
        fixture.journal.persist(record) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = 2L,
                balanceBeforeMinor = 10_000_000L,
            ),
        ) shouldBe true
        fixture.journal.persist(
            record.copy(
                status = MountPurchaseJournalStatus.MANUAL_REVIEW,
                updatedAt = 3L,
                balanceBeforeMinor = 10_000_000L,
                balanceAfterMinor = 5_000_000L,
                evidence = "permission_verification_failed",
            ),
        ) shouldBe true
        fixture.ownership.addDirect(record.permission)
        val manual = mutableListOf<MountPurchaseJournalRecord>()

        fixture.coordinator.recover(MountCatalog(listOf(fixture.mount)), manual::add)

        fixture.journal.records().single().status shouldBe MountPurchaseJournalStatus.COMPLETED
        fixture.wallet.withdrawals shouldBe 0
        manual shouldBe emptyList()
    }
})

private class PurchaseFixture {
    val playerId: UUID = UUID.randomUUID()
    val mount: MountDefinition = testMount()
    val ownership = MutableOwnership()
    val wallet = MutableWallet()
    val journal = FileMountPurchaseJournal(Files.createTempDirectory("arc-mount-purchase-").resolve("journal.json"))
    val coordinator = MountPurchaseCoordinator(ownership, wallet, journal, { true }, { it() }, clock = { 10L })

    fun subject() =
        MountPermissionSubject(playerId, "Rider") { permission ->
            permission == mount.levelPermission(ownership.level) && ownership.level > 0 ||
                permission == mount.glowPermission && ownership.glow ||
                permission == mount.glowDisabledPermission && ownership.glowDisabled ||
                permission in ownership.skinPermissions ||
                permission in ownership.activeSkinPermissions ||
                permission in ownership.abilityPermissions
        }

    fun preparedRecord() =
        MountPurchaseJournalRecord(
            transactionId = UUID.randomUUID().toString(),
            playerId = playerId.toString(),
            mountId = mount.id,
            kind = MountPurchaseKind.LEVEL,
            target = "1",
            permission = mount.levelPermission(1),
            priceMinor = 5_000_000L,
            createdAt = 1L,
            updatedAt = 1L,
        )
}

private class MutableOwnership : MountOwnership {
    var level = 0
    var glow = false
    var glowDisabled = false
    var failWrites = false
    val skinPermissions = hashSetOf<String>()
    val activeSkinPermissions = hashSetOf<String>()
    val abilityPermissions = hashSetOf<String>()
    private val directPermissions = hashSetOf<String>()

    fun addDirect(permission: String) {
        directPermissions += permission
    }

    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile =
        MountProfile(
            level,
            glow,
            glowDisabled,
            mount.skins.filter { mount.skinPermission(it.id) in skinPermissions }.mapTo(hashSetOf()) { it.id },
            mount.skins.firstOrNull { mount.activeSkinPermission(it.id) in activeSkinPermissions }?.id
                ?: MountDefinition.DEFAULT_SKIN_ID,
            mount.abilities.upgrades
                .filter { mount.abilityPermission(it.id) in abilityPermissions }
                .mapTo(hashSetOf(), MountAbilityUpgradeDefinition::id),
        )

    override fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> =
        write { this.level = level; directPermissions += mount.levelPermission(level) }

    override fun revokeLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> =
        write {
            directPermissions -= mount.levelPermission(level)
            if (this.level == level) this.level = (level - 1).coerceAtLeast(0)
        }

    override fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        write { glow = true; glowDisabled = false; directPermissions += mount.glowPermission }

    override fun revokeGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        write { glow = false; glowDisabled = false; directPermissions -= mount.glowPermission }

    override fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void> =
        write { glowDisabled = !enabled }

    override fun grantSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition): CompletableFuture<Void> =
        write { skinPermissions += mount.skinPermission(skin.id); directPermissions += mount.skinPermission(skin.id) }

    override fun revokeSkin(playerId: UUID, mount: MountDefinition, skin: MountSkinDefinition): CompletableFuture<Void> =
        write {
            skinPermissions -= mount.skinPermission(skin.id)
            directPermissions -= mount.skinPermission(skin.id)
            activeSkinPermissions -= mount.activeSkinPermission(skin.id)
        }

    override fun setActiveSkin(playerId: UUID, mount: MountDefinition, skinId: String): CompletableFuture<Void> =
        write {
            activeSkinPermissions.removeIf { it.startsWith("arc.mounts.${mount.id}.skin.active.") }
            if (skinId != MountDefinition.DEFAULT_SKIN_ID) activeSkinPermissions += mount.activeSkinPermission(skinId)
        }

    override fun grantAbility(
        playerId: UUID,
        mount: MountDefinition,
        ability: MountAbilityUpgradeDefinition,
    ): CompletableFuture<Void> =
        write {
            abilityPermissions += mount.abilityPermission(ability.id)
            directPermissions += mount.abilityPermission(ability.id)
        }

    override fun revokeAbility(
        playerId: UUID,
        mount: MountDefinition,
        ability: MountAbilityUpgradeDefinition,
    ): CompletableFuture<Void> =
        write {
            abilityPermissions -= mount.abilityPermission(ability.id)
            directPermissions -= mount.abilityPermission(ability.id)
        }

    override fun hasDirectPermission(playerId: UUID, permission: String): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(permission in directPermissions)

    override fun resolveUniqueId(playerName: String): CompletableFuture<UUID?> = CompletableFuture.completedFuture(null)

    private fun write(change: () -> Unit): CompletableFuture<Void> {
        if (failWrites) return CompletableFuture.failedFuture(IllegalStateException("write failed"))
        change()
        return CompletableFuture.completedFuture(null)
    }
}

private class MutableWallet : MountWallet {
    var balanceMinor = 10_000_000L
    var balanceAvailable = true
    var ambiguousWithdrawal = false
    var withdrawals = 0
    var deposits = 0
    var historyTransactionId: String? = null
    var historyTransactionAtMillis: Long? = null
    var historyAmountMinor: Long? = null
    val historyReasons = mutableListOf<String>()
    val historyNotBeforeMillis = mutableListOf<Long>()
    override val available = true

    override fun balanceMinor(playerId: UUID): Long? = balanceMinor.takeIf { balanceAvailable }

    override fun withdraw(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): MountMoneyEvidence {
        withdrawals++
        if (ambiguousWithdrawal) return MountMoneyEvidence(null, true, null, "provider_threw")
        if (balanceMinor != expectedBalanceBeforeMinor || balanceMinor < amountMinor) {
            return MountMoneyEvidence(false, false, balanceMinor, "provider_rejected")
        }
        balanceMinor -= amountMinor
        return MountMoneyEvidence(true, true, balanceMinor)
    }

    override fun deposit(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): MountMoneyEvidence {
        deposits++
        if (balanceMinor != expectedBalanceBeforeMinor) {
            return MountMoneyEvidence(false, false, balanceMinor, "provider_rejected")
        }
        balanceMinor += amountMinor
        return MountMoneyEvidence(true, true, balanceMinor)
    }

    override fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<MountProviderTransactionEvidence> {
        historyReasons += reason
        historyNotBeforeMillis += notBeforeMillis
        val visibleTransaction =
            historyTransactionId.takeIf {
                amountMinor == historyAmountMinor &&
                    historyTransactionAtMillis?.let { transactionAt -> transactionAt >= notBeforeMillis } == true
            }
        return CompletableFuture.completedFuture(MountProviderTransactionEvidence(visibleTransaction, true))
    }
}
