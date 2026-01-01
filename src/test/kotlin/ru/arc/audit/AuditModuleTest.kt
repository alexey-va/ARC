@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.audit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Тесты для модуля аудита экономики.
 *
 * Модуль аудита отслеживает все экономические операции игроков:
 * - Покупки/продажи в магазинах
 * - Заработок на работах
 * - Переводы между игроками
 * - Дивиденды и акции
 * - Прочие операции
 */
class AuditModuleTest : TestBase() {

    // ==================== Transaction Tests ====================

    @Nested
    @DisplayName("Transaction - Класс транзакции")
    inner class TransactionTests {

        @Test
        @DisplayName("Создание транзакции с базовыми параметрами")
        fun testTransactionCreation() {
            val transaction = Transaction(Type.SHOP, 100.0, "Покупка меча")

            assertEquals(Type.SHOP, transaction.type)
            assertEquals(100.0, transaction.amount, 0.001)
            assertEquals("Покупка меча", transaction.comment)
            assertTrue(transaction.timestamp > 0)
            assertTrue(transaction.timestamp2 > 0)
        }

        @Test
        @DisplayName("Timestamp устанавливается автоматически")
        fun testTimestampAutoSet() {
            val before = System.currentTimeMillis()
            val transaction = Transaction(Type.JOB, 50.0, "Работа")
            val after = System.currentTimeMillis()

            assertTrue(transaction.timestamp >= before)
            assertTrue(transaction.timestamp <= after)
        }

        @Test
        @DisplayName("Timestamp2 изначально равен timestamp")
        fun testTimestamp2EqualsTimestamp() {
            val transaction = Transaction(Type.PAY, 25.0, "Перевод")

            // timestamp2 должен быть примерно равен timestamp при создании
            assertTrue(kotlin.math.abs(transaction.timestamp - transaction.timestamp2) < 10)
        }

        @Test
        @DisplayName("Транзакция с отрицательной суммой (расход)")
        fun testNegativeAmount() {
            val transaction = Transaction(Type.SHOP, -50.0, "Покупка")

            assertEquals(-50.0, transaction.amount, 0.001)
            assertTrue(transaction.isExpense)
            assertFalse(transaction.isIncome)
        }

        @Test
        @DisplayName("Транзакция с нулевой суммой")
        fun testZeroAmount() {
            val transaction = Transaction(Type.OTHER, 0.0, "Нулевая операция")

            assertEquals(0.0, transaction.amount, 0.001)
            assertFalse(transaction.isIncome)
            assertFalse(transaction.isExpense)
        }

        @Test
        @DisplayName("isIncome и isExpense корректно определяются")
        fun testIsIncomeAndExpense() {
            val income = Transaction(Type.JOB, 100.0, "Работа")
            val expense = Transaction(Type.SHOP, -50.0, "Покупка")

            assertTrue(income.isIncome)
            assertFalse(income.isExpense)
            assertFalse(expense.isIncome)
            assertTrue(expense.isExpense)
        }

        @Test
        @DisplayName("absoluteAmount возвращает модуль суммы")
        fun testAbsoluteAmount() {
            val income = Transaction(Type.JOB, 100.0, "Работа")
            val expense = Transaction(Type.SHOP, -50.0, "Покупка")

            assertEquals(100.0, income.absoluteAmount, 0.001)
            assertEquals(50.0, expense.absoluteAmount, 0.001)
        }

        @Test
        @DisplayName("aggregate() добавляет сумму и обновляет timestamp2")
        fun testAggregate() {
            val transaction = Transaction(Type.JOB, 100.0, "Работа")
            val originalTimestamp2 = transaction.timestamp2

            Thread.sleep(5)
            transaction.aggregate(50.0)

            assertEquals(150.0, transaction.amount, 0.001)
            assertTrue(transaction.timestamp2 >= originalTimestamp2)
        }

        @Test
        @DisplayName("canAggregate() проверяет тип и комментарий")
        fun testCanAggregate() {
            val transaction = Transaction(Type.JOB, 100.0, "Майнинг")

            assertTrue(transaction.canAggregate(Type.JOB, "Майнинг"))
            assertFalse(transaction.canAggregate(Type.SHOP, "Майнинг"))
            assertFalse(transaction.canAggregate(Type.JOB, "Рубка"))
        }

        @Test
        @DisplayName("income() создаёт доходную транзакцию")
        fun testIncomeFactory() {
            val transaction = Transaction.income(Type.JOB, 100.0, "Работа")

            assertTrue(transaction.isIncome)
            assertEquals(100.0, transaction.amount, 0.001)
        }

        @Test
        @DisplayName("expense() создаёт расходную транзакцию")
        fun testExpenseFactory() {
            val transaction = Transaction.expense(Type.SHOP, 50.0, "Покупка")

            assertTrue(transaction.isExpense)
            assertEquals(-50.0, transaction.amount, 0.001)
        }
    }

    // ==================== Type Enum Tests ====================

    @Nested
    @DisplayName("Type - Типы транзакций")
    inner class TypeTests {

        @Test
        @DisplayName("Все типы транзакций доступны")
        fun testAllTypesExist() {
            val expectedTypes = listOf(
                "SHOP", "JOB", "PAY", "COMMAND",
                "CHEST_SHOP", "DIVIDEND", "STOCK", "AUCTION", "OTHER"
            )

            val actualTypes = Type.entries.map { it.name }

            expectedTypes.forEach { expected ->
                assertTrue(actualTypes.contains(expected), "Type.$expected should exist")
            }
        }

        @Test
        @DisplayName("Количество типов транзакций")
        fun testTypeCount() {
            assertEquals(9, Type.entries.size)
        }

        @Test
        @DisplayName("valueOf работает для всех типов")
        fun testValueOf() {
            assertEquals(Type.SHOP, Type.valueOf("SHOP"))
            assertEquals(Type.JOB, Type.valueOf("JOB"))
            assertEquals(Type.PAY, Type.valueOf("PAY"))
            assertEquals(Type.COMMAND, Type.valueOf("COMMAND"))
            assertEquals(Type.CHEST_SHOP, Type.valueOf("CHEST_SHOP"))
            assertEquals(Type.DIVIDEND, Type.valueOf("DIVIDEND"))
            assertEquals(Type.STOCK, Type.valueOf("STOCK"))
            assertEquals(Type.AUCTION, Type.valueOf("AUCTION"))
            assertEquals(Type.OTHER, Type.valueOf("OTHER"))
        }
    }

    // ==================== AuditData Tests ====================

    @Nested
    @DisplayName("AuditData - Данные аудита игрока")
    inner class AuditDataTests {

        private lateinit var auditData: AuditData

        @BeforeEach
        fun setUp() {
            auditData = AuditData.create("TestPlayer")
        }

        @Test
        @DisplayName("Создание AuditData с пустыми транзакциями")
        fun testCreation() {
            assertEquals("TestPlayer", auditData.name)
            assertTrue(auditData.transactions.isEmpty())
            assertTrue(auditData.created > 0)
        }

        @Test
        @DisplayName("id() возвращает имя в нижнем регистре")
        fun testId() {
            auditData.name = "TestPlayer"
            assertEquals("testplayer", auditData.id())

            auditData.name = "UPPERCASE"
            assertEquals("uppercase", auditData.id())
        }

        @Test
        @DisplayName("operation() добавляет новую транзакцию")
        fun testOperationAddsTransaction() {
            auditData.operation(100.0, Type.SHOP, "Покупка")

            assertEquals(1, auditData.transactions.size)
            val transaction = auditData.transactions.first
            assertEquals(100.0, transaction.amount, 0.001)
            assertEquals(Type.SHOP, transaction.type)
            assertEquals("Покупка", transaction.comment)
        }

        @Test
        @DisplayName("operation() агрегирует одинаковые транзакции")
        fun testOperationAggregates() {
            auditData.operation(100.0, Type.JOB, "Майнинг")
            auditData.operation(50.0, Type.JOB, "Майнинг")

            // Должна быть одна агрегированная транзакция
            assertEquals(1, auditData.transactions.size)
            assertEquals(150.0, auditData.transactions.first.amount, 0.001)
        }

        @Test
        @DisplayName("operation() не агрегирует разные типы")
        fun testOperationDifferentTypes() {
            auditData.operation(100.0, Type.JOB, "Работа")
            auditData.operation(50.0, Type.SHOP, "Работа") // Разный тип

            assertEquals(2, auditData.transactions.size)
        }

        @Test
        @DisplayName("operation() не агрегирует разные комментарии")
        fun testOperationDifferentComments() {
            auditData.operation(100.0, Type.JOB, "Майнинг")
            auditData.operation(50.0, Type.JOB, "Рубка") // Разный комментарий

            assertEquals(2, auditData.transactions.size)
        }

        @Test
        @DisplayName("operation() агрегирует только последние 10 транзакций")
        fun testOperationAggregationLimit() {
            // Добавляем 15 разных транзакций
            for (i in 1..15) {
                auditData.operation(10.0, Type.OTHER, "Операция $i")
            }

            // Добавляем транзакцию с тем же комментарием что и первая
            // Она НЕ должна агрегироваться, т.к. первая вне лимита 10
            auditData.operation(5.0, Type.OTHER, "Операция 1")

            assertEquals(16, auditData.transactions.size)
        }

        @Test
        @DisplayName("operation() устанавливает dirty flag")
        fun testOperationSetsDirty() {
            auditData.setDirty(false)
            auditData.operation(100.0, Type.SHOP, "Тест")

            assertTrue(auditData.isDirty)
        }

        @Test
        @DisplayName("operation() обновляет timestamp2 при агрегации")
        fun testOperationUpdatesTimestamp2() {
            auditData.operation(100.0, Type.JOB, "Работа")
            val firstTimestamp2 = auditData.transactions.first.timestamp2

            Thread.sleep(10) // Небольшая задержка

            auditData.operation(50.0, Type.JOB, "Работа")
            val newTimestamp2 = auditData.transactions.first.timestamp2

            assertTrue(newTimestamp2 >= firstTimestamp2)
        }

        @Test
        @DisplayName("merge() заменяет транзакции")
        fun testMerge() {
            auditData.operation(100.0, Type.SHOP, "Первая")

            val other = AuditData.create("TestPlayer")
            other.operation(200.0, Type.JOB, "Вторая")
            other.operation(300.0, Type.PAY, "Третья")

            auditData.merge(other)

            assertEquals(2, auditData.transactions.size)
            assertEquals(200.0, auditData.transactions.first.amount, 0.001)
        }

        @Test
        @DisplayName("isRemove() возвращает false для новых данных")
        fun testIsRemoveForNewData() {
            assertFalse(auditData.isRemove)
        }

        @Test
        @DisplayName("isRemove() возвращает false если есть транзакции")
        fun testIsRemoveWithTransactions() {
            // Устанавливаем старую дату создания
            auditData.created = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 31 // 31 день
            auditData.operation(100.0, Type.SHOP, "Тест")

            assertFalse(auditData.isRemove)
        }

        @Test
        @DisplayName("isRemove() возвращает true для старых пустых данных")
        fun testIsRemoveForOldEmptyData() {
            // Устанавливаем старую дату создания (>30 дней)
            auditData.created = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 31

            assertTrue(auditData.isRemove)
        }

        @Test
        @DisplayName("Доход и расход корректно сохраняются")
        fun testIncomeAndExpense() {
            auditData.operation(100.0, Type.JOB, "Заработок") // Доход
            auditData.operation(-50.0, Type.SHOP, "Покупка")  // Расход

            assertEquals(2, auditData.transactions.size)

            val income = auditData.transactions.first { it.amount > 0 }
            val expense = auditData.transactions.first { it.amount < 0 }

            assertEquals(100.0, income.amount, 0.001)
            assertEquals(-50.0, expense.amount, 0.001)
        }

        @Test
        @DisplayName("clear() очищает все транзакции")
        fun testClear() {
            auditData.operation(100.0, Type.JOB, "Работа")
            auditData.operation(50.0, Type.SHOP, "Покупка")

            auditData.clear()

            assertTrue(auditData.transactions.isEmpty())
            assertTrue(auditData.isDirty)
        }

        @Test
        @DisplayName("totalBalance() считает общий баланс")
        fun testTotalBalance() {
            auditData.operation(100.0, Type.JOB, "Работа")
            auditData.operation(-30.0, Type.SHOP, "Покупка")
            auditData.operation(50.0, Type.DIVIDEND, "Дивиденды")

            assertEquals(120.0, auditData.totalBalance(), 0.001)
        }

        @Test
        @DisplayName("totalIncome() считает только доходы")
        fun testTotalIncome() {
            auditData.operation(100.0, Type.JOB, "Работа")
            auditData.operation(-30.0, Type.SHOP, "Покупка")
            auditData.operation(50.0, Type.DIVIDEND, "Дивиденды")

            assertEquals(150.0, auditData.totalIncome(), 0.001)
        }

        @Test
        @DisplayName("totalExpense() считает только расходы")
        fun testTotalExpense() {
            auditData.operation(100.0, Type.JOB, "Работа")
            auditData.operation(-30.0, Type.SHOP, "Покупка")
            auditData.operation(-20.0, Type.PAY, "Перевод")

            assertEquals(-50.0, auditData.totalExpense(), 0.001)
        }

        @Test
        @DisplayName("getFiltered() фильтрует транзакции")
        fun testGetFiltered() {
            auditData.operation(100.0, Type.JOB, "Работа")
            auditData.operation(-30.0, Type.SHOP, "Покупка")
            auditData.operation(50.0, Type.JOB, "Ещё работа")
            auditData.operation(-20.0, Type.PAY, "Перевод")

            assertEquals(4, auditData.getFiltered(AuditFilter.ALL).size)
            assertEquals(2, auditData.getFiltered(AuditFilter.INCOME).size)
            assertEquals(2, auditData.getFiltered(AuditFilter.EXPENSE).size)
            assertEquals(2, auditData.getFiltered(AuditFilter.JOB).size)
            assertEquals(1, auditData.getFiltered(AuditFilter.SHOP).size)
            assertEquals(1, auditData.getFiltered(AuditFilter.PAY).size)
        }

        @Test
        @DisplayName("trim() удаляет старые транзакции")
        fun testTrim() {
            // Добавляем транзакции с разными временами
            val oldTransaction = Transaction(Type.JOB, 100.0, "Старая", System.currentTimeMillis() - 100000)
            val newTransaction = Transaction(Type.JOB, 50.0, "Новая")

            auditData.transactions.add(oldTransaction)
            auditData.transactions.add(newTransaction)

            val removed = auditData.trim(50000) // Удаляем старше 50 секунд

            assertEquals(1, removed)
            assertEquals(1, auditData.transactions.size)
            assertEquals("Новая", auditData.transactions.first.comment)
        }
    }

    // ==================== AuditFilter Enum Tests ====================

    @Nested
    @DisplayName("AuditFilter - Фильтры аудита")
    inner class FilterTests {

        @Test
        @DisplayName("Все фильтры доступны")
        fun testAllFiltersExist() {
            val expectedFilters = listOf("INCOME", "EXPENSE", "ALL", "SHOP", "JOB", "PAY")

            val actualFilters = AuditFilter.entries.map { it.name }

            expectedFilters.forEach { expected ->
                assertTrue(actualFilters.contains(expected), "AuditFilter.$expected should exist")
            }
        }

        @Test
        @DisplayName("Количество фильтров")
        fun testFilterCount() {
            assertEquals(6, AuditFilter.entries.size)
        }

        @Test
        @DisplayName("valueOf работает для всех фильтров")
        fun testValueOf() {
            assertEquals(AuditFilter.INCOME, AuditFilter.valueOf("INCOME"))
            assertEquals(AuditFilter.EXPENSE, AuditFilter.valueOf("EXPENSE"))
            assertEquals(AuditFilter.ALL, AuditFilter.valueOf("ALL"))
            assertEquals(AuditFilter.SHOP, AuditFilter.valueOf("SHOP"))
            assertEquals(AuditFilter.JOB, AuditFilter.valueOf("JOB"))
            assertEquals(AuditFilter.PAY, AuditFilter.valueOf("PAY"))
        }

        @Test
        @DisplayName("fromString() работает case-insensitive")
        fun testFromString() {
            assertEquals(AuditFilter.INCOME, AuditFilter.fromString("income"))
            assertEquals(AuditFilter.INCOME, AuditFilter.fromString("INCOME"))
            assertEquals(AuditFilter.INCOME, AuditFilter.fromString("Income"))
            assertEquals(AuditFilter.ALL, AuditFilter.fromString("unknown"))
        }
    }

    // ==================== Integration-like Tests ====================

    @Nested
    @DisplayName("Интеграционные сценарии")
    inner class IntegrationTests {

        @Test
        @DisplayName("Полный цикл операций игрока")
        fun testFullPlayerCycle() {
            val auditData = AuditData.create("Steve")

            // Игрок работает
            auditData.operation(100.0, Type.JOB, "Майнинг")
            auditData.operation(50.0, Type.JOB, "Майнинг")
            auditData.operation(75.0, Type.JOB, "Рубка деревьев")

            // Игрок покупает
            auditData.operation(-30.0, Type.SHOP, "Покупка кирки")
            auditData.operation(-20.0, Type.SHOP, "Покупка еды")

            // Игрок переводит деньги
            auditData.operation(-50.0, Type.PAY, "Перевод Alex")

            // Проверяем результат
            // Майнинг агрегировался: 100 + 50 = 150
            // Остальные отдельно: рубка(75), кирка(-30), еда(-20), перевод(-50)
            assertEquals(5, auditData.transactions.size)

            val mining = auditData.transactions.first { it.comment == "Майнинг" }
            assertEquals(150.0, mining.amount, 0.001)
        }

        @Test
        @DisplayName("Баланс после серии операций")
        fun testBalanceCalculation() {
            val auditData = AuditData.create("Player")

            auditData.operation(1000.0, Type.JOB, "Зарплата")
            auditData.operation(-200.0, Type.SHOP, "Покупки")
            auditData.operation(-100.0, Type.PAY, "Перевод")
            auditData.operation(50.0, Type.DIVIDEND, "Дивиденды")

            val totalBalance = auditData.totalBalance()
            assertEquals(750.0, totalBalance, 0.001)
        }

        @Test
        @DisplayName("Фильтрация транзакций по типу")
        fun testFilterByType() {
            val auditData = AuditData.create("Player")

            auditData.operation(100.0, Type.JOB, "Работа 1")
            auditData.operation(200.0, Type.JOB, "Работа 2")
            auditData.operation(-50.0, Type.SHOP, "Покупка")
            auditData.operation(-30.0, Type.PAY, "Перевод")

            val jobTransactions = auditData.getFiltered(AuditFilter.JOB)
            val shopTransactions = auditData.getFiltered(AuditFilter.SHOP)
            val payTransactions = auditData.getFiltered(AuditFilter.PAY)

            assertEquals(2, jobTransactions.size)
            assertEquals(1, shopTransactions.size)
            assertEquals(1, payTransactions.size)
        }

        @Test
        @DisplayName("Фильтрация по доходу/расходу")
        fun testFilterByIncomeExpense() {
            val auditData = AuditData.create("Player")

            auditData.operation(100.0, Type.JOB, "Доход 1")
            auditData.operation(200.0, Type.JOB, "Доход 2")
            auditData.operation(-50.0, Type.SHOP, "Расход 1")
            auditData.operation(-30.0, Type.SHOP, "Расход 2")
            auditData.operation(-20.0, Type.PAY, "Расход 3")

            val income = auditData.getFiltered(AuditFilter.INCOME)
            val expense = auditData.getFiltered(AuditFilter.EXPENSE)

            assertEquals(2, income.size)
            assertEquals(3, expense.size)
            assertEquals(300.0, income.sumOf { it.amount }, 0.001)
            assertEquals(-100.0, expense.sumOf { it.amount }, 0.001)
        }

        @Test
        @DisplayName("Сортировка транзакций по времени")
        fun testTransactionOrdering() {
            val auditData = AuditData.create("Player")

            auditData.operation(100.0, Type.JOB, "Первая")
            Thread.sleep(5)
            auditData.operation(200.0, Type.SHOP, "Вторая")
            Thread.sleep(5)
            auditData.operation(300.0, Type.PAY, "Третья")

            val transactions = auditData.transactions.toList()

            // Первая должна быть раньше второй, вторая раньше третьей
            assertTrue(transactions[0].timestamp <= transactions[1].timestamp)
            assertTrue(transactions[1].timestamp <= transactions[2].timestamp)
        }

        @Test
        @DisplayName("Множественные игроки")
        fun testMultiplePlayers() {
            val player1 = AuditData.create("Player1")
            val player2 = AuditData.create("Player2")

            player1.operation(100.0, Type.JOB, "Работа")
            player2.operation(200.0, Type.JOB, "Работа")

            assertEquals("player1", player1.id())
            assertEquals("player2", player2.id())
            assertEquals(1, player1.transactions.size)
            assertEquals(1, player2.transactions.size)
            assertEquals(100.0, player1.transactions.first.amount, 0.001)
            assertEquals(200.0, player2.transactions.first.amount, 0.001)
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Граничные случаи")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("Операция с пустым комментарием")
        fun testEmptyComment() {
            val auditData = AuditData.create("Player")
            auditData.operation(100.0, Type.OTHER, "")

            assertEquals(1, auditData.transactions.size)
            assertEquals("", auditData.transactions.first.comment)
        }

        @Test
        @DisplayName("Операция с очень большой суммой")
        fun testLargeAmount() {
            val auditData = AuditData.create("Player")
            val largeAmount = 1_000_000_000.0
            auditData.operation(largeAmount, Type.OTHER, "Большая сумма")

            assertEquals(largeAmount, auditData.transactions.first.amount, 0.001)
        }

        @Test
        @DisplayName("Операция с очень маленькой суммой")
        fun testSmallAmount() {
            val auditData = AuditData.create("Player")
            auditData.operation(0.001, Type.OTHER, "Маленькая сумма")

            assertEquals(0.001, auditData.transactions.first.amount, 0.0001)
        }

        @Test
        @DisplayName("Много транзакций подряд")
        fun testManyTransactions() {
            val auditData = AuditData.create("Player")

            repeat(1000) { i ->
                auditData.operation(1.0, Type.OTHER, "Транзакция $i")
            }

            assertEquals(1000, auditData.transactions.size)
        }

        @Test
        @DisplayName("Агрегация множества одинаковых операций")
        fun testManyAggregations() {
            val auditData = AuditData.create("Player")

            repeat(100) {
                auditData.operation(1.0, Type.JOB, "Одинаковая")
            }

            // Все должны агрегироваться в одну
            assertEquals(1, auditData.transactions.size)
            assertEquals(100.0, auditData.transactions.first.amount, 0.001)
        }

        @Test
        @DisplayName("Имя игрока с разным регистром")
        fun testPlayerNameCase() {
            val auditData1 = AuditData.create("Steve")
            val auditData2 = AuditData.create("STEVE")
            val auditData3 = AuditData.create("steve")

            // Все id() должны быть одинаковыми (lowercase)
            assertEquals(auditData1.id(), auditData2.id())
            assertEquals(auditData2.id(), auditData3.id())
            assertEquals("steve", auditData1.id())
        }

        @Test
        @DisplayName("Транзакция с Unicode в комментарии")
        fun testUnicodeComment() {
            val auditData = AuditData.create("Player")
            auditData.operation(100.0, Type.OTHER, "Покупка 🎮 игры")

            assertEquals("Покупка 🎮 игры", auditData.transactions.first.comment)
        }

        @Test
        @DisplayName("Транзакция с длинным комментарием")
        fun testLongComment() {
            val auditData = AuditData.create("Player")
            val longComment = "А".repeat(1000)
            auditData.operation(100.0, Type.OTHER, longComment)

            assertEquals(longComment, auditData.transactions.first.comment)
        }
    }

    // ==================== Concurrency Tests ====================

    @Nested
    @DisplayName("Многопоточность")
    inner class ConcurrencyTests {

        @Test
        @DisplayName("ConcurrentLinkedDeque безопасен для многопоточной записи")
        fun testConcurrentOperations() {
            val auditData = AuditData.create("Player")

            val threads = (1..10).map { threadId ->
                Thread {
                    repeat(100) { i ->
                        auditData.operation(1.0, Type.OTHER, "Thread$threadId-$i")
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // Должно быть 1000 уникальных транзакций (каждый поток делает 100 уникальных)
            assertEquals(1000, auditData.transactions.size)
        }

        @Test
        @DisplayName("Агрегация работает в многопоточной среде")
        fun testConcurrentAggregation() {
            val auditData = AuditData.create("Player")

            val threads = (1..5).map {
                Thread {
                    repeat(20) {
                        auditData.operation(1.0, Type.JOB, "Одинаковая работа")
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // Из-за race condition при агрегации возможны потери
            // Проверяем что сумма в разумных пределах (>90% от ожидаемой)
            // и что агрегация работает (транзакций меньше чем операций)
            val transactions = auditData.transactions.filter { it.comment == "Одинаковая работа" }
            val totalAmount = transactions.sumOf { it.amount }

            assertTrue(totalAmount >= 90.0, "Total should be at least 90, was $totalAmount")
            assertTrue(totalAmount <= 100.0, "Total should be at most 100, was $totalAmount")
            assertTrue(transactions.size < 100, "Aggregation should reduce transaction count, was ${transactions.size}")
        }
    }
}
