package ru.arc.itemcatalog

import org.bukkit.inventory.ItemStack
import ru.arc.onetime.OneTimeUseFingerprint
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Current immutable source used by one voucher attempt.
 *
 * [preview] is cloned by the controller before it is exposed to a player or
 * marked as a voucher. It is only presentation data; the native grant is
 * always supplied by the callback on the redemption path.
 */
data class PhysicalRewardSpec(
    val key: String,
    val fingerprint: OneTimeUseFingerprint,
    val preview: ItemStack,
    /** Full immutable choice pool; null for ordinary rewards. */
    val choiceOptions: List<PhysicalRewardChoiceOption>? = null,
    /** Server-side choice index confirmed in the native dialog, never persisted on the voucher. */
    val selectedChoiceIndex: Int? = null,
) {
    init {
        require(PhysicalRewardVoucher.isValidKey(key)) { "Physical reward source key is invalid" }
        require(!preview.type.isAir) { "Physical reward preview must not be air" }
        require(choiceOptions == null || choiceOptions.size in 3..32) { "Physical reward choice pool must contain 3..32 options" }
        require(choiceOptions == null || choiceOptions.map { it.id }.distinct().size == choiceOptions.size) {
            "Physical reward choice ids must be unique"
        }
        require(selectedChoiceIndex == null || choiceOptions?.indices?.contains(selectedChoiceIndex) == true) {
            "Selected physical reward choice is outside its pool"
        }
    }
}

/** Immutable dialog-facing label and frozen child address. */
data class PhysicalRewardChoiceOption(
    val id: String,
    val name: String,
    val description: List<String>,
    val childKey: String,
    val childFingerprint: String,
)

/** Native adapter outcome. Rejected guarantees that no value mutation happened. */
sealed interface PhysicalRewardOutcome {
    data object Applied : PhysicalRewardOutcome

    data class Rejected(val message: String) : PhysicalRewardOutcome {
        init {
            require(message.isNotBlank()) { "Physical reward rejection message must not be blank" }
        }
    }

    data class Uncertain(val message: String) : PhysicalRewardOutcome {
        init {
            require(message.isNotBlank()) { "Physical reward uncertainty message must not be blank" }
        }
    }
}

/** Bounded local state retained until the durable claim is settled. */
internal class PhysicalRewardRedemption(
    val playerId: UUID,
    val voucher: PhysicalRewardVoucherIdentity,
    val claimId: UUID,
    val claimScope: String,
    val choiceSelection: ChoiceSelection?,
) {
    @Volatile
    var claim: ru.arc.onetime.OneTimeUseClaim? = null

    @Volatile
    var phase: Phase = Phase.CLAIMING

    @Volatile
    var cancelled: Boolean = false

    /** Completion is recorded before a lifecycle callback is scheduled. */
    @Volatile
    var nativeOutcome: PhysicalRewardOutcome? = null

    @Volatile
    var nativeFailure: Throwable? = null

    val settlementStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Completes after the claim's release, commit, or abandon callback settles. */
    val settled = CompletableFuture<Void>()

    enum class Phase {
        CLAIMING,
        CLAIMED,
        MUTATING,
        COMMITTING,
        TERMINAL,
    }
}

/** Immutable confirmation context captured by one native-dialog button. */
internal data class ChoiceSelection(
    val definitionFingerprint: String,
    val offeredIndices: List<Int>,
    val selectedIndex: Int,
)
