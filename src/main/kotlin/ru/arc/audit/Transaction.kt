package ru.arc.audit

import com.google.gson.annotations.SerializedName

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
    var timestamp2: Long = System.currentTimeMillis()
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

    /**
     * Агрегировать с другой транзакцией того же типа.
     * Увеличивает сумму и обновляет timestamp2.
     */
    fun aggregate(additionalAmount: Double) {
        amount += additionalAmount
        timestamp2 = System.currentTimeMillis()
    }

    /**
     * Проверить, можно ли агрегировать с данными параметрами.
     */
    fun canAggregate(otherType: Type, otherComment: String): Boolean {
        return type == otherType && comment == otherComment
    }

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

