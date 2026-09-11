package ru.arc.itemcatalog

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.onetime.OneTimeUseFingerprint
import java.util.UUID

/**
 * Bearer identity for a catalogue reward voucher.
 *
 * The UUID is deliberately unique for every created stack.  The key and
 * fingerprint are copied into the item so a later reload can reject a stale
 * or tampered definition without guessing what the holder intended to claim.
 */
data class PhysicalRewardVoucherIdentity(
    val id: UUID,
    val key: String,
    val fingerprint: OneTimeUseFingerprint,
)

object PhysicalRewardVoucher {
    const val VERSION = "1"

    private val KEY_PATTERN = Regex("[A-Za-z0-9_.:/-]{1,256}")

    val idKey = NamespacedKey("arc", "physical_reward_voucher")
    val versionKey = NamespacedKey("arc", "physical_reward_voucher_version")
    val sourceKey = NamespacedKey("arc", "physical_reward_voucher_source")
    val fingerprintKey = NamespacedKey("arc", "physical_reward_voucher_fingerprint")

    /** Reads only a complete, bounded marker. Partial or malformed markers are inert. */
    fun identity(stack: ItemStack?): PhysicalRewardVoucherIdentity? {
        if (stack == null || stack.type.isAir) return null
        val data = stack.itemMeta?.persistentDataContainer ?: return null
        if (data.get(versionKey, PersistentDataType.STRING) != VERSION) return null
        val id = data.get(idKey, PersistentDataType.STRING)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val key = data.get(sourceKey, PersistentDataType.STRING)?.takeIf(::isValidKey) ?: return null
        val fingerprint = data.get(fingerprintKey, PersistentDataType.STRING)?.let {
            runCatching { OneTimeUseFingerprint.parse(it) }.getOrNull()
        } ?: return null
        return PhysicalRewardVoucherIdentity(id, key, fingerprint)
    }

    fun mark(
        stack: ItemStack,
        identity: PhysicalRewardVoucherIdentity,
    ): ItemStack {
        require(isValidKey(identity.key)) { "Physical reward source key is invalid" }
        stack.editMeta { meta ->
            val data = meta.persistentDataContainer
            data.set(versionKey, PersistentDataType.STRING, VERSION)
            data.set(idKey, PersistentDataType.STRING, identity.id.toString())
            data.set(sourceKey, PersistentDataType.STRING, identity.key)
            data.set(fingerprintKey, PersistentDataType.STRING, identity.fingerprint.sha256)
        }
        return stack
    }

    fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)
}
