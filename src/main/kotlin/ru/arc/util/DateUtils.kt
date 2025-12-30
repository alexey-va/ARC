package ru.arc.util

import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object DateUtils {

    private var config: Config? = null

    private fun getConfig(): Config? {
        if (config == null && ARC.plugin != null) {
            config = ConfigManager.of(ARC.plugin!!.dataPath, "misc.yml")
        }
        return config
    }

    @JvmStatic
    fun formatDate(timestamp: Long): String {
        val cfg = getConfig()
        val format = cfg?.string("date-format", "MM-dd HH:mm:ss") ?: "MM-dd HH:mm:ss"
        val timezoneStr = cfg?.string("timezone", "+3") ?: "+3"
        val zone = ZoneOffset.of(timezoneStr)
        val time = LocalDateTime.ofEpochSecond(timestamp / 1000, 0, zone)
        return time.format(DateTimeFormatter.ofPattern(format))
    }
}

