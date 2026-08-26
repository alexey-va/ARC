package ru.arc.contracts

import java.math.BigDecimal

/**
 * Formats exact minor currency units without hiding meaningful cents or
 * retaining redundant trailing zeroes on player-facing contract surfaces.
 */
internal fun formatContractMoney(minor: Long): String =
    BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()
