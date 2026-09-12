package ru.arc.itemcatalog

import com.google.gson.Gson
import org.bukkit.inventory.ItemStack
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.persistence.DurableRecord
import ru.arc.persistence.DurableRecordJournal
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Durable archive for provider-backed catalogue rewards.
 *
 * The archive stores an effect recipe and an inert preview under a content
 * address. It never stores a bearer UUID. A voucher made from an archived
 * recipe therefore remains transferable, while each materialization can mint a
 * new identity through [PhysicalRewardController].
 */
internal class FrozenPhysicalRewards(root: Path) {
    private val gson = Gson()
    private val journal = DurableRecordJournal(
        root = root,
        relativeDirectory = Path.of("data", "reward-physical-archive"),
        maxRecordBytes = MAX_RECORD_BYTES,
        encode = { record: FrozenPhysicalRewardRecord -> gson.toJson(record).toByteArray(Charsets.UTF_8) },
        decode = { bytes ->
            requireNotNull(gson.fromJson(bytes.toString(Charsets.UTF_8), FrozenPhysicalRewardRecord::class.java))
        },
        validate = FrozenPhysicalRewardRecord::validate,
    )
    private val records = linkedMapOf<String, FrozenPhysicalRewardRecord>()

    init {
        // A bad archive must not take ARC down. The affected frozen source is
        // unavailable until repaired; current catalogue sources remain usable.
        loadRecords().forEach { stored -> records[stored.recordId] = stored.value }
    }

    /** A corrupt record must not hide unrelated valid historical records. */
    private fun loadRecords(): List<DurableRecord<FrozenPhysicalRewardRecord>> = runCatching {
        Files.list(journal.directory).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".json") }
                .sorted()
                .map { path: Path ->
                    val recordId = path.fileName.toString().removeSuffix(".json")
                    runCatching<DurableRecord<FrozenPhysicalRewardRecord>?> {
                        journal.loadOrNull(recordId)?.let { DurableRecord(recordId, it) }
                    }
                        .onFailure { failure ->
                            ru.arc.util.Logging.warn(
                                "Frozen physical reward record unavailable: id={} cause={}",
                                recordId,
                                failure.javaClass.simpleName,
                            )
                        }
                        .getOrNull()
                }
                .toList()
                .filterNotNull()
        }
    }.onFailure { failure ->
        ru.arc.util.Logging.warn("Frozen physical reward archive unavailable: {}", failure.javaClass.simpleName)
    }.getOrElse { emptyList() }

    /** Archives one immutable recipe, returning its content-addressed voucher key. */
    @Synchronized
    fun prepare(
        sourceKey: String,
        recipe: FrozenPhysicalRecipe,
        preview: ItemStack,
    ): PhysicalRewardMaterialization? = runCatching {
        require(PhysicalRewardVoucher.isValidKey(sourceKey)) { "Physical reward source key is invalid" }
        recipe.validate()
        val previewBytes = preview.serializeAsBytes()
        require(previewBytes.size <= MAX_STACK_BYTES) { "Physical reward preview is too large" }
        require(!preview.type.isAir) { "Physical reward preview must not be air" }
        val previewEncoded = Base64.getEncoder().encodeToString(previewBytes)
        val canonicalRecipe = gson.toJson(recipe)
        val fingerprint = OneTimeUseFingerprint.sha256(
            buildString {
                append("arc-frozen-physical-v1\n")
                append(sourceKey).append('\n')
                append(canonicalRecipe).append('\n')
                append(OneTimeUseFingerprint.sha256(previewBytes).sha256)
            }.toByteArray(Charsets.UTF_8),
        ).sha256
        val record = FrozenPhysicalRewardRecord(
            version = CURRENT_VERSION,
            key = "frozen:$fingerprint",
            sourceKey = sourceKey,
            fingerprint = fingerprint,
            preview = previewEncoded,
            recipe = recipe,
        ).also(FrozenPhysicalRewardRecord::validate)
        val existing = records[fingerprint]
        if (existing != null) {
            require(existing == record) { "Frozen physical reward content address collision" }
            return@runCatching PhysicalRewardMaterialization(record.key, record.fingerprint)
        }
        val stored = journal.commit(fingerprint, record)
        check(stored == record) { "Frozen physical reward archive readback mismatch" }
        records[fingerprint] = stored
        PhysicalRewardMaterialization(stored.key, stored.fingerprint)
    }.getOrNull()

    /** Returns an archived record only for a complete, content-addressed key. */
    @Synchronized
    fun find(key: String): FrozenPhysicalRewardRecord? {
        val fingerprint = key.removePrefix("frozen:").takeIf { key.startsWith("frozen:") } ?: return null
        if (!FINGERPRINT.matches(fingerprint)) return null
        return records[fingerprint]?.takeIf { it.key == key }
    }

    /** Stable, valid collection-seal marker for one archived record. */
    @Synchronized
    fun archivedCategoryId(key: String): String? {
        val fingerprint = key.removePrefix("frozen:").takeIf { key.startsWith("frozen:") } ?: return null
        if (!FINGERPRINT.matches(fingerprint)) return null
        return records[fingerprint]
            ?.takeIf { it.key == key && it.recipe.type == "seal" }
            ?.let { "set_frozen_$fingerprint" }
    }

    /** Resolves an archived seal into detached choices for the existing one-choice exchange. */
    @Synchronized
    fun archivedSeal(categoryId: String): ArchivedCollectionSeal? {
        if (!CollectionSealIdentity.isValidCategoryId(categoryId) || !categoryId.startsWith("set_frozen_")) return null
        val fingerprint = categoryId.removePrefix("set_frozen_").takeIf(FINGERPRINT::matches) ?: return null
        val record = records[fingerprint]?.takeIf { it.key == "frozen:$fingerprint" && it.recipe.type == "seal" } ?: return null
        val recipe = record.recipe
        val choices = runCatching {
            recipe.sealItems.orEmpty().map { encoded ->
                ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)).clone()
            }
        }.getOrNull() ?: return null
        if (choices.isEmpty()) return null
        val name = recipe.sealName ?: return null
        return ArchivedCollectionSeal(categoryId, name, recipe.sealDescription.orEmpty(), choices)
    }

    /** Returns the inert, configured preview for an archived seal marker. */
    @Synchronized
    fun archivedSealPreview(categoryId: String): ItemStack? {
        val fingerprint = categoryId.removePrefix("set_frozen_").takeIf {
            CollectionSealIdentity.isValidCategoryId(categoryId) && categoryId.startsWith("set_frozen_") && FINGERPRINT.matches(it)
        } ?: return null
        val record = records[fingerprint]?.takeIf { it.key == "frozen:$fingerprint" && it.recipe.type == "seal" } ?: return null
        return preview(record)
    }

    @Synchronized
    fun preview(record: FrozenPhysicalRewardRecord): ItemStack? = runCatching {
        ItemStack.deserializeBytes(Base64.getDecoder().decode(record.preview)).takeIf { !it.type.isAir }
    }.getOrNull()

    private companion object {
        const val CURRENT_VERSION = 1
        const val MAX_RECORD_BYTES = 16L * 1024L * 1024L
        const val MAX_STACK_BYTES = 512 * 1024
        val FINGERPRINT = Regex("[a-f0-9]{64}")
    }
}

/** Immutable menu snapshot consumed by [CollectionSealController]. */
data class ArchivedCollectionSeal(
    val categoryId: String,
    val name: String,
    val description: List<String>,
    val choices: List<ItemStack>,
)

/** Durable record format. Nullable fields are selected by [type] and validated strictly. */
internal data class FrozenPhysicalRewardRecord(
    val version: Int,
    val key: String,
    val sourceKey: String,
    val fingerprint: String,
    val preview: String,
    val recipe: FrozenPhysicalRecipe,
) {
    fun validate() {
        require(version == 1) { "Unsupported frozen physical reward version" }
        require(PhysicalRewardVoucher.isValidKey(key)) { "Frozen physical reward key is invalid" }
        require(key == "frozen:$fingerprint") { "Frozen physical reward key does not match fingerprint" }
        require(FINGERPRINT.matches(fingerprint)) { "Frozen physical reward fingerprint is invalid" }
        require(PhysicalRewardVoucher.isValidKey(sourceKey)) { "Archived source key is invalid" }
        require(preview.isNotBlank() && preview.length <= 1_000_000) { "Archived preview is invalid" }
        val previewBytes = Base64.getDecoder().decode(preview)
        require(previewBytes.size <= 512 * 1024) { "Archived preview is too large" }
        val previewStack = ItemStack.deserializeBytes(previewBytes)
        require(!previewStack.type.isAir) { "Archived preview is air" }
        require(PhysicalRewardVoucher.identity(previewStack) == null) {
            "Archived preview carries a physical voucher identity"
        }
        require(CollectionSealIdentity.categoryId(previewStack) == null) {
            "Archived preview carries a collection seal identity"
        }
        recipe.validate()
        val expectedFingerprint = OneTimeUseFingerprint.sha256(
            buildString {
                append("arc-frozen-physical-v1\n")
                append(sourceKey).append('\n')
                append(Gson().toJson(recipe)).append('\n')
                append(OneTimeUseFingerprint.sha256(previewBytes).sha256)
            }.toByteArray(Charsets.UTF_8),
        ).sha256
        require(expectedFingerprint == fingerprint) { "Frozen physical reward fingerprint mismatch" }
    }

    private companion object {
        val FINGERPRINT = Regex("[a-f0-9]{64}")
    }
}

/** Effect recipe for a provider-backed reward. It contains no player identity. */
internal data class FrozenPhysicalRecipe(
    val type: String,
    val currency: String? = null,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val tokenAmount: Long? = null,
    val commandKind: String? = null,
    val commandValue: String? = null,
    val mountId: String? = null,
    val furnitureBoxes: List<String>? = null,
    /** Detached member snapshot used by collection seals; redemption chooses one member. */
    val sealItems: List<String>? = null,
    val sealName: String? = null,
    val sealDescription: List<String>? = null,
    val treasure: FrozenTreasureNode? = null,
) {
    fun validate() {
        when (type) {
            "money" -> validateAmount(currency, minAmount, maxAmount, expectedCurrency = "vault")
            "tokens" -> {
                require(currency == "tokens") { "Frozen token currency is invalid" }
                require(tokenAmount in 1L..999_999L) { "Frozen token amount is invalid" }
                require(minAmount == null && maxAmount == null) { "Frozen token range is invalid" }
                require(commandKind == null && commandValue == null && mountId == null && furnitureBoxes == null && sealItems == null && sealName == null && sealDescription == null && treasure == null)
            }
            "command" -> {
                require(commandKind in ALLOWED_COMMAND_KINDS) { "Frozen command kind is invalid" }
                require(commandValue != null && commandValue.length in 1..256) { "Frozen command value is invalid" }
                require(ALLOWED_COMMANDS.getValue(commandKind!!).matches(commandValue)) {
                    "Frozen command value is not an allowed native command"
                }
                require(currency == null && minAmount == null && maxAmount == null && tokenAmount == null)
                require(mountId == null && furnitureBoxes == null && sealItems == null && sealName == null && sealDescription == null && treasure == null)
            }
            "ae" -> {
                require(commandKind == "item" || commandKind == "random_book") { "Frozen AE kind is invalid" }
                if (commandKind == "item") require(commandValue?.length in 1..128) { "Frozen AE item is invalid" }
                require(minAmount == null && maxAmount == null && tokenAmount == null)
                require(currency == null && mountId == null && furnitureBoxes == null && sealItems == null && sealName == null && sealDescription == null && treasure == null)
            }
            "mount" -> {
                require(mountId != null && ID_PATTERN.matches(mountId)) { "Frozen mount id is invalid" }
                require(currency == null && minAmount == null && maxAmount == null && tokenAmount == null)
                require(commandKind == null && commandValue == null && furnitureBoxes == null && sealItems == null && sealName == null && sealDescription == null && treasure == null)
            }
            "furniture" -> {
                require(furnitureBoxes != null && furnitureBoxes.size in 1..8) { "Frozen furniture boxes are invalid" }
                furnitureBoxes.forEach { encoded ->
                    require(encoded.length in 1..700_000) { "Frozen furniture box is too large" }
                    val bytes = Base64.getDecoder().decode(encoded)
                    require(bytes.size <= 512 * 1024) { "Frozen furniture box is too large" }
                    require(!ItemStack.deserializeBytes(bytes).type.isAir) { "Frozen furniture box is air" }
                }
                require(currency == null && minAmount == null && maxAmount == null && tokenAmount == null)
                require(commandKind == null && commandValue == null && mountId == null && sealItems == null && sealName == null && sealDescription == null && treasure == null)
            }
            "seal" -> {
                require(sealItems != null && sealItems.size in 1..216) { "Frozen seal snapshot is invalid" }
                sealItems.forEach { encoded ->
                    require(encoded.length in 1..700_000) { "Frozen seal snapshot entry is too large" }
                    val bytes = Base64.getDecoder().decode(encoded)
                    require(bytes.size <= 512 * 1024) { "Frozen seal snapshot entry is too large" }
                    val stack = ItemStack.deserializeBytes(bytes)
                    require(!stack.type.isAir) { "Frozen seal snapshot entry is air" }
                    require(PhysicalRewardVoucher.identity(stack) == null) {
                        "Frozen seal snapshot entry carries a physical voucher identity"
                    }
                    require(CollectionSealIdentity.categoryId(stack) == null) {
                        "Frozen seal snapshot entry carries a collection seal identity"
                    }
                }
                require(sealName != null && sealName.length in 1..256) { "Frozen seal name is invalid" }
                require(sealDescription != null && sealDescription.size <= 32 && sealDescription.all { it.length <= 512 }) {
                    "Frozen seal description is invalid"
                }
                require(currency == null && minAmount == null && maxAmount == null && tokenAmount == null)
                require(commandKind == null && commandValue == null && mountId == null && furnitureBoxes == null && treasure == null)
            }
            "treasure" -> {
                require(treasure != null) { "Frozen treasure recipe is missing" }
                treasure.validate(0, emptySet())
                require(currency == null && minAmount == null && maxAmount == null && tokenAmount == null)
                require(commandKind == null && commandValue == null && mountId == null && furnitureBoxes == null && sealItems == null && sealName == null && sealDescription == null)
            }
            else -> error("Unknown frozen physical recipe type: $type")
        }
    }

    private fun validateAmount(currency: String?, min: Double?, max: Double?, expectedCurrency: String) {
        require(currency == expectedCurrency) { "Frozen currency is invalid" }
        require(min != null && max != null && min.isFinite() && max.isFinite() && min > 0.0 && max >= min && max <= 1_000_000_000.0)
        require(tokenAmount == null && commandKind == null && commandValue == null)
        require(mountId == null && furnitureBoxes == null && sealItems == null && sealName == null && sealDescription == null && treasure == null)
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_.:/-]{1,256}")
        val ALLOWED_COMMAND_KINDS = setOf("arcbuilder", "arcecojobs", "elitemobs")
        val ALLOWED_COMMANDS = mapOf(
            "arcbuilder" to Regex("arcbuilder:builder systembook %player% [a-z0-9_-]+\\.schem"),
            "arcecojobs" to Regex("arcecojobs:arcjobs booster give %player% [a-z0-9_-]+ 1"),
            "elitemobs" to Regex("elitemobs:elitemobs loot give %player% [a-z0-9_-]+\\.yml"),
        )
    }
}

/** Recursive, self-contained weighted Treasure graph used by archived vouchers. */
internal data class FrozenTreasureNode(
    val id: String,
    val type: String,
    val weight: Int,
    val minInt: Int? = null,
    val maxInt: Int? = null,
    val minDouble: Double? = null,
    val maxDouble: Double? = null,
    val stack: String? = null,
    val requiresItemsAdder: Boolean = false,
    val itemId: String? = null,
    val exclude: List<String>? = null,
    val commands: List<String>? = null,
    val poolId: String? = null,
    val children: List<FrozenTreasureNode>? = null,
    val aeKind: String? = null,
    val itemName: String? = null,
    val amount: Int? = null,
    val aeArgs: List<FrozenAeArg>? = null,
) {
    fun validate(depth: Int, visitedPools: Set<String>) {
        require(depth <= 8) { "Frozen treasure graph is too deep" }
        require(id.isNotBlank() && id.length <= 256) { "Frozen treasure id is invalid" }
        require(weight in 0..1_000_000) { "Frozen treasure weight is invalid" }
        require(type == "item" || !requiresItemsAdder) { "ItemsAdder marker is invalid for frozen treasure type" }
        when (type) {
            "item" -> {
                validateIntRange()
                require(stack != null && stack.length in 1..700_000)
                require(!ItemStack.deserializeBytes(Base64.getDecoder().decode(stack)).type.isAir)
                require(itemId == null && exclude == null && commands == null && poolId == null && children == null)
                require(aeKind == null && itemName == null && amount == null && aeArgs == null)
            }
            "money" -> {
                validateDoubleRange()
                require(stack == null && itemId == null && exclude == null && commands == null && poolId == null && children == null)
                require(aeKind == null && itemName == null && amount == null && aeArgs == null)
            }
            "command" -> {
                require(commands?.size == 1)
                require(commands.single().length in 1..512 && allowedCommand(commands.single()))
                require(stack == null && itemId == null && exclude == null && poolId == null && children == null)
                require(aeKind == null && itemName == null && amount == null && aeArgs == null)
            }
            "sub-pool" -> {
                require(poolId != null && poolId.isNotBlank() && poolId.length <= 256)
                require(poolId !in visitedPools)
                require(children != null && children.size in 1..216)
                require(children.any { it.weight > 0 })
                children.forEach { it.validate(depth + 1, visitedPools + poolId) }
                require(stack == null && itemId == null && exclude == null && commands == null)
                require(aeKind == null && itemName == null && amount == null && aeArgs == null)
            }
            "enchant", "potion" -> {
                validateIntRange()
                if (type == "enchant") require((exclude ?: emptyList()).size <= 256 && (exclude ?: emptyList()).all { it.length <= 128 })
                else require(exclude == null || exclude.isEmpty())
                require(stack == null && itemId == null && commands == null && poolId == null && children == null)
                require(aeKind == null && itemName == null && amount == null && aeArgs == null)
            }
            "ae" -> {
                require(aeKind == "item" || aeKind == "random_book")
                if (aeKind == "item") require(itemName?.length in 1..128) else require(itemName == null)
                require(amount in 1..64)
                require((aeArgs ?: emptyList()).size <= 16)
                require((aeArgs ?: emptyList()).all(FrozenAeArg::isValid))
                require(stack == null && itemId == null && exclude == null && commands == null && poolId == null && children == null)
            }
            "slimefun" -> {
                validateIntRange()
                require(itemId?.length in 1..256)
                val bytes = Base64.getDecoder().decode(requireNotNull(stack))
                require(bytes.size <= 512 * 1024)
                require(!ItemStack.deserializeBytes(bytes).type.isAir)
                require(exclude == null && commands == null && poolId == null && children == null)
                require(aeKind == null && itemName == null && amount == null && aeArgs == null)
            }
            else -> error("Unknown frozen treasure type: $type")
        }
    }

    private fun validateIntRange() {
        require(minInt != null && maxInt != null && minInt in 1..1_000_000 && maxInt in minInt..1_000_000)
    }

    private fun validateDoubleRange() {
        require(minDouble != null && maxDouble != null && minDouble.isFinite() && maxDouble.isFinite())
        require(minDouble > 0.0 && maxDouble >= minDouble && maxDouble <= 1_000_000_000.0)
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_.:/-]{1,256}")
        val NATIVE_COMMANDS = listOf(
            Regex("rediseconomy:balance %player% tokens give [1-9][0-9]{0,5} arc-lootbox-catalog"),
            Regex("arcbuilder:builder systembook %player% [a-z0-9_-]+\\.schem"),
            Regex("arcecojobs:arcjobs booster give %player% [a-z0-9_-]+ 1"),
            Regex("elitemobs:elitemobs loot give %player% [a-z0-9_-]+\\.yml"),
        )

        fun allowedCommand(command: String): Boolean = NATIVE_COMMANDS.any { it.matches(command) }
    }
}

internal data class FrozenAeArg(
    val type: String,
    val min: Int? = null,
    val max: Int? = null,
) {
    fun isValid(): Boolean = when (type) {
        "random-tier", "random-slot" -> min == null && max == null
        "int" -> min != null && max != null && min in -1_000_000..1_000_000 && max in min..1_000_000
        else -> false
    }
}
