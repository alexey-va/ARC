package ru.arc.invest

import ru.arc.invest.upgrades.Upgrade

class BusinessCore {
    var upgrades: List<Upgrade>? = null
    var productionCycleMs: Long = 0
    var failureChance: Double = 0.0
    var speedup: Double = 0.0
    var lastCycleTimestamp: Long = 0
    var production: Production? = null
}
