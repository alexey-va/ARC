package ru.arc.itemcatalog

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import java.nio.file.Path

/** Private connection settings for the network-wide bearer redemption journal. */
class PhysicalRewardStorageConfig(private val config: Config) {
    val enabled get() = config.bool("enabled", false)

    fun sql() = SqlConnectionConfig(
        host = config.string("mysql.host", "localhost"),
        port = config.integer("mysql.port", 3306),
        database = config.string("mysql.database", "arc"),
        username = config.string("mysql.username", "arc"),
        password = config.string("mysql.password", ""),
        sslMode = SqlSslMode.valueOf(config.string("mysql.ssl-mode", "VERIFY_IDENTITY")),
        maximumPoolSize = 2,
    )

    companion object {
        fun load(root: Path) = PhysicalRewardStorageConfig(ConfigManager.ofModule(root, "reward-redemption.yml"))
    }
}
