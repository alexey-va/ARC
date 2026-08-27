package ru.arc.investigation

import com.google.gson.Gson
import ru.arc.persistence.DurableRecordJournal
import ru.arc.util.Common
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

enum class InvestigationStatus {
    PREPARED,
    WITHDRAWAL_STARTED,
    ACTIVE,
    FAILED,
    REWARD_STARTED,
    COMPLETED,
    CANCELLED,
    MANUAL_REVIEW,
    ;

    val resolved: Boolean get() = this == FAILED || this == COMPLETED || this == CANCELLED
}

data class InvestigationJournalRecord(
    val transactionId: String,
    val playerId: String,
    val case: InvestigationCase,
    val feeMinor: Long,
    val rewardMinor: Long,
    val status: InvestigationStatus = InvestigationStatus.PREPARED,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val withdrawalStartedAt: Long? = null,
    val activeAt: Long? = null,
    val expiresAt: Long? = null,
    val cooldownUntil: Long? = null,
    val feeBalanceBeforeMinor: Long? = null,
    val feeBalanceAfterMinor: Long? = null,
    val rewardStartedAt: Long? = null,
    val rewardBalanceBeforeMinor: Long? = null,
    val rewardBalanceAfterMinor: Long? = null,
    val cluesMask: Int = 0,
    val submittedVerdict: InvestigationVerdict? = null,
    val evidence: String? = null,
) {
    fun validated(): InvestigationJournalRecord {
        require(runCatching { UUID.fromString(transactionId) }.getOrNull()?.toString() == transactionId) {
            "Invalid investigation transaction id"
        }
        require(runCatching { UUID.fromString(playerId) }.getOrNull()?.toString() == playerId) {
            "Invalid investigation player id"
        }
        case.validated()
        require(feeMinor > 0L && rewardMinor > feeMinor) { "Invalid investigation money policy" }
        require(createdAt > 0L && updatedAt >= createdAt) { "Invalid investigation timestamps" }
        require(cluesMask in 0..ALL_CLUES_MASK) { "Invalid investigation clue mask" }
        require(evidence == null || EVIDENCE_PATTERN.matches(evidence)) { "Invalid investigation evidence" }
        listOfNotNull(withdrawalStartedAt, activeAt, expiresAt, cooldownUntil, rewardStartedAt).forEach {
            require(it >= createdAt) { "Investigation phase predates creation" }
        }
        require(withdrawalStartedAt == null || withdrawalStartedAt <= updatedAt) { "Invalid withdrawal timestamp" }
        require(activeAt == null || activeAt <= updatedAt) { "Invalid active timestamp" }
        require(rewardStartedAt == null || rewardStartedAt <= updatedAt) { "Invalid reward timestamp" }

        if (status != InvestigationStatus.PREPARED && status != InvestigationStatus.CANCELLED) {
            require(withdrawalStartedAt != null && feeBalanceBeforeMinor != null) {
                "Paid investigation state lacks withdrawal intent"
            }
        }
        if (status == InvestigationStatus.ACTIVE || status == InvestigationStatus.FAILED || status == InvestigationStatus.REWARD_STARTED || status == InvestigationStatus.COMPLETED) {
            require(activeAt != null && expiresAt != null && cooldownUntil != null) { "Investigation state lacks active window" }
            require(expiresAt > activeAt && cooldownUntil > activeAt) { "Invalid investigation active window" }
            require(feeBalanceAfterMinor == feeBalanceBeforeMinor!! - feeMinor) { "Investigation fee lacks exact balance evidence" }
        }
        if (status == InvestigationStatus.REWARD_STARTED || status == InvestigationStatus.COMPLETED) {
            require(submittedVerdict == case.verdict) { "Reward can only follow a correct verdict" }
            require(rewardStartedAt != null && rewardBalanceBeforeMinor != null) { "Reward lacks durable intent" }
        }
        if (status == InvestigationStatus.COMPLETED) {
            require(rewardBalanceAfterMinor == rewardBalanceBeforeMinor!! + rewardMinor) {
                "Investigation reward lacks exact balance evidence"
            }
        }
        if (status == InvestigationStatus.FAILED && submittedVerdict != null) {
            require(submittedVerdict != case.verdict) { "A correct verdict cannot fail" }
        }
        return this
    }

    fun hasClue(witness: InvestigationWitness): Boolean = cluesMask and witness.bit != 0

    fun clueCount(): Int = Integer.bitCount(cluesMask)

    companion object {
        const val ALL_CLUES_MASK = 7
        private val EVIDENCE_PATTERN = Regex("[a-z0-9_:-]{1,180}")
    }
}

interface InvestigationJournal {
    fun records(): List<InvestigationJournalRecord>

    fun persist(record: InvestigationJournalRecord): Boolean

    fun latest(playerId: UUID): InvestigationJournalRecord? =
        records().filter { it.playerId == playerId.toString() }.maxByOrNull(InvestigationJournalRecord::createdAt)

    fun open(playerId: UUID): InvestigationJournalRecord? =
        records().filter { it.playerId == playerId.toString() && !it.status.resolved }.maxByOrNull(InvestigationJournalRecord::createdAt)
}

class FileInvestigationJournal(
    root: Path,
    relativeDirectory: Path = Path.of("data", "investigations"),
    private val gson: Gson = Common.prettyGson,
) : InvestigationJournal {
    private val recordsById = linkedMapOf<String, InvestigationJournalRecord>()
    private val durable =
        DurableRecordJournal(
            root = root,
            relativeDirectory = relativeDirectory,
            maxRecordBytes = MAX_RECORD_BYTES,
            encode = { record: InvestigationJournalRecord -> gson.toJson(record).toByteArray(StandardCharsets.UTF_8) },
            decode = { bytes -> gson.fromJson(bytes.toString(StandardCharsets.UTF_8), InvestigationJournalRecord::class.java) },
            validate = InvestigationJournalRecord::validated,
        )

    init {
        load()
    }

    @Synchronized
    override fun records(): List<InvestigationJournalRecord> = recordsById.values.sortedBy(InvestigationJournalRecord::createdAt)

    @Synchronized
    override fun persist(record: InvestigationJournalRecord): Boolean {
        val valid = record.validated()
        val current = recordsById[valid.transactionId]
        if (current == null) {
            require(valid.status == InvestigationStatus.PREPARED) { "New investigation must start PREPARED" }
        } else {
            require(
                current.playerId == valid.playerId &&
                    current.case == valid.case &&
                    current.feeMinor == valid.feeMinor &&
                    current.rewardMinor == valid.rewardMinor &&
                    current.createdAt == valid.createdAt,
            ) { "Investigation journal identity changed" }
            require(valid.updatedAt >= current.updatedAt) { "Investigation journal moved backwards" }
            require(valid.status == current.status || valid.status in allowedTransitions(current.status)) {
                "Illegal investigation transition ${current.status} -> ${valid.status}"
            }
            require(valid.cluesMask or current.cluesMask == valid.cluesMask) { "Investigation clues cannot be forgotten" }
        }

        return runCatching {
            if (current == null) compactBeforeInsert()
            val committed = durable.commit(valid.transactionId, valid)
            recordsById[committed.transactionId] = committed
        }.isSuccess
    }

    private fun load() {
        val loaded = durable.loadAll()
        require(loaded.size <= MAX_RECORDS) { "Investigation journal exceeds its hard limit" }
        loaded.map { it.value.validated() }.forEach { record ->
            require(recordsById.put(record.transactionId, record) == null) { "Duplicate investigation transaction id" }
        }
        val unresolved = recordsById.values.filterNot { it.status.resolved }
        require(unresolved.size <= MAX_UNRESOLVED) { "Too many unresolved investigations" }
        require(unresolved.groupingBy(InvestigationJournalRecord::playerId).eachCount().values.none { it > 1 }) {
            "A player has more than one unresolved investigation"
        }
    }

    private fun compactBeforeInsert() {
        val unresolved = recordsById.values.count { !it.status.resolved }
        require(unresolved < MAX_UNRESOLVED) { "Too many unresolved investigations" }
        if (recordsById.size < MAX_RECORDS) return
        val oldestResolved = recordsById.values.filter { it.status.resolved }.minByOrNull(InvestigationJournalRecord::updatedAt)
            ?: error("Investigation journal is full of unresolved records")
        check(durable.acknowledge(oldestResolved.transactionId)) { "Unable to compact investigation journal" }
        recordsById.remove(oldestResolved.transactionId)
    }

    private fun allowedTransitions(status: InvestigationStatus): Set<InvestigationStatus> =
        when (status) {
            InvestigationStatus.PREPARED -> setOf(InvestigationStatus.WITHDRAWAL_STARTED, InvestigationStatus.CANCELLED)
            InvestigationStatus.WITHDRAWAL_STARTED -> setOf(InvestigationStatus.ACTIVE, InvestigationStatus.FAILED, InvestigationStatus.MANUAL_REVIEW)
            InvestigationStatus.ACTIVE -> setOf(InvestigationStatus.FAILED, InvestigationStatus.REWARD_STARTED, InvestigationStatus.MANUAL_REVIEW)
            InvestigationStatus.REWARD_STARTED -> setOf(InvestigationStatus.COMPLETED, InvestigationStatus.MANUAL_REVIEW)
            InvestigationStatus.MANUAL_REVIEW -> setOf(InvestigationStatus.ACTIVE, InvestigationStatus.FAILED, InvestigationStatus.COMPLETED)
            InvestigationStatus.FAILED,
            InvestigationStatus.COMPLETED,
            InvestigationStatus.CANCELLED,
            -> emptySet()
        }

    companion object {
        private const val MAX_RECORDS = 8_192
        private const val MAX_UNRESOLVED = 512
        private const val MAX_RECORD_BYTES = 64L * 1024L
    }
}
