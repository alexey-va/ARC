package arc.arc.invest;

import arc.arc.invest.upgrades.Upgrade;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

public class BusinessCore {

    List<Upgrade> upgrades;
    long productionCycleMs;
    double failureChance;
    double speedup;
    long lastCycleTimestamp;
    Production production;

}
