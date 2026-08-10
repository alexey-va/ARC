package ru.arc.audit

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * Запись о финансовой транзакции игрока.
 *
 * Транзакции могут агрегироваться: если несколько одинаковых операций
 * происходят подряд, они объединяются в одну с обновлённой суммой.
 *
 * @property type Тип операции
 * @property amount Сумма (положительная = доход, отрицательная = расход)
 * @property comment Описание операции
 * @property timestamp Время первой операции (мс)
 * @property timestamp2 Время последней агрегации (мс)
 */
data class Transaction(
    @SerializedName("t")
    val type: Type,

    @SerializedName("a")
    var amount: Double,

    @SerializedName("c")
    val comment: String,

    @SerializedName("ts")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("ts2")
    var timestamp2: Long = System.currentTimeMillis(),

    @SerializedName("s")
    val source: EconomySource? = null,

    @SerializedName("f")
    val flow: EconomyFlow? = null,

    @SerializedName("cu")
    val currency: String? = null,

    @SerializedName("sv")
    val server: String? = null,

    @SerializedName("o")
    val origin: String? = null,

    @SerializedName("n")
    var occurrences: Int = 1,

    /** Stable identity lets full-snapshot Redis merges retain concurrent append-only events. */
    @SerializedName("id")
    val eventId: String? = UUID.randomUUID().toString(),

    /** Optional v2 evidence; absent on original ledger records. */
    @SerializedName("x")
    val context: EconomyLedgerContext? = null,
) {
    /**
     * Является ли транзакция доходом.
     */
    val isIncome: Boolean get() = amount > 0

    /**
     * Является ли транзакция расходом.
     */
    val isExpense: Boolean get() = amount < 0

    /**
     * Абсолютная сумма транзакции.
     */
    val absoluteAmount: Double get() = kotlin.math.abs(amount)

    val occurrenceCount: Int get() = occurrences.coerceAtLeast(1)

    val normalizedSource: EconomySource get() = source ?: EconomySource.LEGACY

    val normalizedFlow: EconomyFlow get() = flow ?: EconomyFlow.UNKNOWN

    val normalizedCurrency: String get() = currency?.ifBlank { "vault" } ?: "vault"

    val normalizedServer: String get() = server?.ifBlank { "unknown" } ?: "unknown"

    val normalizedRecordKind: EconomyRecordKind get() = context?.normalizedRecordKind ?: EconomyRecordKind.TRANSACTION

    val normalizedStatus: EconomyEventStatus get() = context?.normalizedStatus ?: EconomyEventStatus.SUCCEEDED

    val mergeKey: String
        get() =
            eventId?.takeIf(String::isNotBlank)?.let { "event:$it" }
                ?: listOf(timestamp, type, comment, normalizedSource, normalizedFlow, normalizedCurrency, normalizedServer, origin.orEmpty())
                    .joinToString("|") { it.toString() }

    /**
     * Агрегировать с другой транзакцией того же типа.
     * Увеличивает сумму и обновляет timestamp2.
     */
    fun aggregate(additionalAmount: Double, at: Long = System.currentTimeMillis()) {
        amount += additionalAmount
        timestamp2 = at
        occurrences = occurrenceCount + 1
    }

    /**
     * Проверить, можно ли агрегировать с данными параметрами.
     */
    fun canAggregate(otherType: Type, otherComment: String): Boolean {
        return type == otherType && comment == otherComment
    }

    fun canAggregate(
        otherType: Type,
        otherAmount: Double,
        otherComment: String,
        metadata: AuditMetadata,
        at: Long,
        windowMillis: Long,
    ): Boolean =
        type == otherType &&
            comment == otherComment &&
            amount.compareTo(0.0) == otherAmount.compareTo(0.0) &&
            normalizedSource == metadata.source &&
            normalizedFlow == metadata.flow &&
            normalizedCurrency == metadata.currency &&
            normalizedServer == metadata.server &&
            origin.orEmpty() == metadata.origin &&
            context == null &&
            at - timestamp2 in 0..windowMillis

    companion object {
        /**
         * Создать транзакцию дохода.
         */
        fun income(type: Type, amount: Double, comment: String): Transaction {
            require(amount >= 0) { "Income amount must be non-negative" }
            return Transaction(type, amount, comment)
        }

        /**
         * Создать транзакцию расхода.
         */
        fun expense(type: Type, amount: Double, comment: String): Transaction {
            require(amount >= 0) { "Expense amount must be non-negative" }
            return Transaction(type, -amount, comment)
        }
    }
}
