package ru.arc.invest;

import ru.arc.invest.upgrades.Upgrade;

import java.util.List;

public class BusinessCore {

    List<Upgrade> upgrades;
    long productionCycleMs;
    double failureChance;
    double speedup;
    long lastCycleTimestamp;
    Production production;

}
