package ru.arc.util

import java.util.concurrent.ThreadLocalRandom

object RandomUtils {

    @JvmStatic
    fun <T> random(array: Array<T>): T {
        if (array.size == 0) {
            throw IllegalArgumentException("Array must not be empty")
        }
        return array[ThreadLocalRandom.current().nextInt(array.size)]
    }

    @JvmStatic
    fun <T> random(array: Array<T>, amount: Int): Array<T> {
        if (amount <= 0) {
            return array.clone().copyOfRange(0, 0)
        }
        if (amount >= array.size) return array.clone()

        val copyArray = array.clone()

        // Shuffle the copied array using Fisher-Yates
        for (i in copyArray.size - 1 downTo 1) {
            val index = ThreadLocalRandom.current().nextInt(i + 1)
            val temp = copyArray[index]
            copyArray[index] = copyArray[i]
            copyArray[i] = temp
        }

        return copyArray.copyOfRange(0, amount)
    }

    @JvmStatic
    fun <T> random(collection: Collection<T>?): T {
        if (collection == null || collection.isEmpty()) {
            throw IllegalArgumentException("Collection must not be null or empty")
        }

        return if (collection is List<T>) {
            val randomIndex = ThreadLocalRandom.current().nextInt(collection.size)
            collection[randomIndex]
        } else {
            val list = collection.toList()
            val randomIndex = ThreadLocalRandom.current().nextInt(list.size)
            list[randomIndex]
        }
    }

    @JvmStatic
    fun <K, V> random(map: Map<K, V>?): Map.Entry<K, V> {
        if (map == null || map.isEmpty()) {
            throw IllegalArgumentException("Map must not be null or empty")
        }
        val rng = ThreadLocalRandom.current().nextInt(map.size)
        return map.entries.stream().skip(rng.toLong()).findFirst()
            .orElseThrow { IllegalStateException("Failed to get random map entry") }
    }
}

