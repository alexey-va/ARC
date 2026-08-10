package ru.arc.audit

import com.google.gson.annotations.SerializedName
import ru.arc.audit.AuditData.Companion.AGGREGATION_LOOKUP_LIMIT
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Данные аудита для одного игрока.
 *
 * Хранит историю всех финансовых операций игрока.
 * Поддерживает агрегацию одинаковых операций для экономии памяти.
 *
 * @property transactions Очередь транзакций (новые в конце)
 * @property name Имя игрока
 * @property created Время создания записи
 */
class AuditData(
    @SerializedName("t")
    val transactions: ConcurrentLinkedDeque<Transaction> = ConcurrentLinkedDeque(),

    @SerializedName("n")
    var name: String = "",

    @SerializedName("c")
    var created: Long = System.currentTimeMillis(),

    /** Server-qualified repository key. Null keeps legacy player-only records readable. */
    @SerializedName("i")
    val storageId: String? = null,
) : Entity,
    Mergeable<AuditData> {
    companion object {
        /** Максимальное количество последних транзакций для поиска агрегации */
        private const val AGGREGATION_LOOKUP_LIMIT = 10

        /** Время жизни пустых данных (30 дней) */
        private const val EMPTY_DATA_LIFETIME_MS = 1000L * 60 * 60 * 24 * 30

        /**
         * Создать новые данные аудита для игрока.
         */
        fun create(playerName: String, storageId: String? = null): AuditData {
            return AuditData(
                transactions = ConcurrentLinkedDeque(),
                name = playerName,
                created = System.currentTimeMillis(),
                storageId = storageId,
            )
        }
    }

    /**
     * Записать операцию.
     *
     * Если в последних [AGGREGATION_LOOKUP_LIMIT] транзакциях есть подходящая
     * для агрегации (тот же тип и комментарий), сумма добавляется к ней.
     * Иначе создаётся новая транзакция.
     *
     * @param amount Сумма (положительная = доход, отрицательная = расход)
     * @param type Тип операции
     * @param comment Описание
     */
    fun operation(
        amount: Double,
        type: Type,
        comment: String,
        metadata: AuditMetadata = AuditMetadata.legacy(),
        at: Long = System.currentTimeMillis(),
        aggregationWindowMillis: Long = 10_000L,
    ) {
        // Ищем транзакцию для агрегации среди последних N
        val matchingTransaction = findTransactionForAggregation(type, amount, comment, metadata, at, aggregationWindowMillis)

        if (matchingTransaction != null) {
            matchingTransaction.aggregate(amount, at)
        } else {
            transactions.add(
                Transaction(
                    type = type,
                    amount = amount,
                    comment = comment,
                    timestamp = at,
                    timestamp2 = at,
                    source = metadata.source,
                    flow = metadata.flow,
                    currency = metadata.currency,
                    server = metadata.server,
                    origin = metadata.origin,
                ),
            )
        }

    }

    /**
     * Найти транзакцию для агрегации среди последних.
     */
    private fun findTransactionForAggregation(
        type: Type,
        amount: Double,
        comment: String,
        metadata: AuditMetadata,
        at: Long,
        aggregationWindowMillis: Long,
    ): Transaction? {
        var count = 0
        for (transaction in transactions.reversed()) {
            if (transaction.canAggregate(type, amount, comment, metadata, at, aggregationWindowMillis)) {
                return transaction
            }
            if (++count >= AGGREGATION_LOOKUP_LIMIT) {
                break
            }
        }
        return null
    }

    /**
     * Удалить старые транзакции.
     *
     * @param maxAge Максимальный возраст в миллисекундах (null = использовать конфиг)
     * @param maxTransactions Максимальное количество транзакций
     * @return Количество удалённых транзакций
     */
    fun trim(
        maxAge: Long,
        maxTransactions: Int = 50000,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val cutoffTime = now - maxAge
        var removed = 0

        // Async repository callbacks are not guaranteed to append in timestamp order.
        // Keep an aggregate while its most recent occurrence is still inside retention.
        removed += transactions.count { it.timestamp2 < cutoffTime }
        transactions.removeIf { it.timestamp2 < cutoffTime }

        while (transactions.size > maxTransactions.coerceAtLeast(0)) {
            transactions.poll()
            removed++
        }

        return removed
    }

    /**
     * Получить транзакции с фильтрацией.
     */
    fun getFiltered(filter: AuditFilter): List<Transaction> {
        return when (filter) {
            AuditFilter.ALL -> transactions.toList()
            AuditFilter.INCOME -> transactions.filter { it.isIncome }
            AuditFilter.EXPENSE -> transactions.filter { it.isExpense }
            AuditFilter.SHOP -> transactions.filter { it.type == Type.SHOP }
            AuditFilter.JOB -> transactions.filter { it.type == Type.JOB }
            AuditFilter.PAY -> transactions.filter { it.type == Type.PAY }
        }
    }

    /**
     * Подсчитать общий баланс изменений.
     */
    fun totalBalance(): Double = transactions.sumOf { it.amount }

    /**
     * Подсчитать общий доход.
     */
    fun totalIncome(): Double = transactions.filter { it.isIncome }.sumOf { it.amount }

    /**
     * Подсчитать общий расход.
     */
    fun totalExpense(): Double = transactions.filter { it.isExpense }.sumOf { it.amount }

    /**
     * Очистить все транзакции.
     */
    fun clear() {
        transactions.clear()
    }

    /**
     * Check if this entry should be removed (expired and empty).
     */
    fun shouldRemove(): Boolean {
        val isOld = created < System.currentTimeMillis() - EMPTY_DATA_LIFETIME_MS
        return isOld && transactions.isEmpty()
    }

    // ==================== Entity Implementation ====================

    override fun id(): String = storageId?.takeIf(String::isNotBlank) ?: name.lowercase()

    // ==================== Mergeable Implementation ====================

    override fun merge(other: AuditData) {
        val merged = linkedMapOf<String, Transaction>()
        (transactions.toList() + other.transactions.toList()).forEach { candidate ->
            val current = merged[candidate.mergeKey]
            if (
                current == null ||
                candidate.occurrenceCount > current.occurrenceCount ||
                (candidate.occurrenceCount == current.occurrenceCount && candidate.timestamp2 > current.timestamp2)
            ) {
                merged[candidate.mergeKey] = candidate.copy()
            }
        }
        transactions.clear()
        merged.values.sortedBy(Transaction::timestamp).forEach(transactions::add)
    }
}

/**
 * Фильтры для просмотра аудита.
 */
enum class AuditFilter {
    /** Все транзакции */
    ALL,

    /** Только доходы (положительные суммы) */
    INCOME,

    /** Только расходы (отрицательные суммы) */
    EXPENSE,

    /** Только операции в магазинах */
    SHOP,

    /** Только заработок от работ */
    JOB,

    /** Только переводы между игроками */
    PAY;

    companion object {
        /**
         * Получить фильтр по имени (case-insensitive).
         */
        fun fromString(value: String): AuditFilter {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: ALL
        }
    }
}
