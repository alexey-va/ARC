package ru.arc.audit

import com.google.gson.annotations.SerializedName
import ru.arc.network.repos.RepoData
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
    var created: Long = System.currentTimeMillis()
) : RepoData<AuditData>() {

    companion object {
        /** Максимальное количество последних транзакций для поиска агрегации */
        private const val AGGREGATION_LOOKUP_LIMIT = 10

        /** Время жизни пустых данных (30 дней) */
        private const val EMPTY_DATA_LIFETIME_MS = 1000L * 60 * 60 * 24 * 30

        /**
         * Создать новые данные аудита для игрока.
         */
        fun create(playerName: String): AuditData {
            return AuditData(
                transactions = ConcurrentLinkedDeque(),
                name = playerName,
                created = System.currentTimeMillis()
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
    fun operation(amount: Double, type: Type, comment: String) {
        // Ищем транзакцию для агрегации среди последних N
        val matchingTransaction = findTransactionForAggregation(type, comment)

        if (matchingTransaction != null) {
            matchingTransaction.aggregate(amount)
        } else {
            transactions.add(Transaction(type, amount, comment))
        }

        setDirty(true)
    }

    /**
     * Найти транзакцию для агрегации среди последних.
     */
    private fun findTransactionForAggregation(type: Type, comment: String): Transaction? {
        var count = 0
        for (transaction in transactions.reversed()) {
            if (transaction.canAggregate(type, comment)) {
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
    fun trim(maxAge: Long, maxTransactions: Int = 50000): Int {
        val cutoffTime = System.currentTimeMillis() - maxAge
        var removed = 0

        while (transactions.isNotEmpty()) {
            val oldest = transactions.peek() ?: break

            val shouldRemove = transactions.size > maxTransactions || oldest.timestamp < cutoffTime
            if (!shouldRemove) break

            transactions.poll()
            removed++
        }

        if (removed > 0) {
            setDirty(true)
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
        setDirty(true)
    }

    // ==================== RepoData Implementation ====================

    override fun id(): String = name.lowercase()

    override fun isRemove(): Boolean {
        val isOld = created < System.currentTimeMillis() - EMPTY_DATA_LIFETIME_MS
        return isOld && transactions.isEmpty()
    }

    override fun merge(other: AuditData) {
        transactions.clear()
        transactions.addAll(other.transactions)
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

