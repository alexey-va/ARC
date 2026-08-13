package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MountPurchaseCoordinatorTest : StringSpec({
    "successful level purchase charges once and persists the next level" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.Success
        fixture.wallet.balance shouldBe 50_000.0
        fixture.ownership.level shouldBe 1
        fixture.wallet.withdrawals shouldBe 1
    }

    "failed ownership persistence refunds the full purchase" {
        val fixture = PurchaseFixture().also { it.ownership.failWrites = true }
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 1) { result = it }

        result shouldBe MountPurchaseResult.PersistenceFailedRefunded
        fixture.wallet.balance shouldBe 100_000.0
        fixture.wallet.deposits shouldBe 1
    }

    "non-sequential upgrade is rejected without charging" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseLevel(fixture.subject(), fixture.mount, 2) { result = it }

        result shouldBe MountPurchaseResult.InvalidLevel
        fixture.wallet.balance shouldBe 100_000.0
        fixture.wallet.withdrawals shouldBe 0
    }

    "glow purchase requires an unlocked mount" {
        val fixture = PurchaseFixture()
        var result: MountPurchaseResult? = null

        fixture.coordinator.purchaseGlow(fixture.subject(), fixture.mount) { result = it }

        result shouldBe MountPurchaseResult.NotUnlocked
        fixture.wallet.withdrawals shouldBe 0
    }
})

private class PurchaseFixture {
    val playerId: UUID = UUID.randomUUID()
    val mount: MountDefinition = purchaseTestMount()
    val ownership = MutableOwnership()
    val wallet = MutableWallet()
    val coordinator = MountPurchaseCoordinator(ownership, wallet) { it() }

    fun subject() =
        MountPermissionSubject(playerId, "Rider") { permission ->
            permission == mount.levelPermission(ownership.level) && ownership.level > 0 ||
                permission == mount.glowPermission && ownership.glow ||
                permission == mount.glowDisabledPermission && ownership.glowDisabled
        }
}

private fun purchaseTestMount() =
    MountDefinition(
        id = "bee",
        movement = MountMovement.FLYING,
        entityType = "BEE",
        iconMaterial = "BEE_SPAWN_EGG",
        displayName = "Пчела",
        speeds = listOf(0.4, 0.6, 0.9),
        prices = listOf(50_000.0, 100_000.0, 500_000.0),
        glowPrice = 10_000.0,
    )

private class MutableOwnership : MountOwnership {
    var level = 0
    var glow = false
    var glowDisabled = false
    var failWrites = false

    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile =
        MountProfile(level, glow, glowDisabled)

    override fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> =
        write { this.level = level }

    override fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        write { glow = true; glowDisabled = false }

    override fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void> =
        write { glowDisabled = !enabled }

    override fun resolveUniqueId(playerName: String): CompletableFuture<UUID?> = CompletableFuture.completedFuture(null)

    private fun write(change: () -> Unit): CompletableFuture<Void> {
        if (failWrites) return CompletableFuture.failedFuture(IllegalStateException("write failed"))
        change()
        return CompletableFuture.completedFuture(null)
    }
}

private class MutableWallet : MountWallet {
    var balance = 100_000.0
    var withdrawals = 0
    var deposits = 0
    override val available = true

    override fun balance(playerId: UUID): Double = balance

    override fun withdraw(playerId: UUID, amount: Double): Boolean {
        if (balance < amount) return false
        balance -= amount
        withdrawals++
        return true
    }

    override fun deposit(playerId: UUID, amount: Double): Boolean {
        balance += amount
        deposits++
        return true
    }
}
