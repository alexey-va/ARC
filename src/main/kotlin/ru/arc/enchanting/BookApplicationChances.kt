package ru.arc.enchanting

/** Application risk supplied by the AdvancedEnchantments provider. */
internal data class BookApplicationChances(val success: Int, val destroyOnFailure: Int) {
    init { require(success in 0..100 && destroyOnFailure in 0..1) }
}
