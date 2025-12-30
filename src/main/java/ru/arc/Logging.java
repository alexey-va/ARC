package ru.arc;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.BurstFilter;
import org.apache.logging.log4j.core.layout.JsonLayout;
import pl.tkowalcz.tjahzi.log4j2.Header;
import pl.tkowalcz.tjahzi.log4j2.LokiAppender;
import pl.tkowalcz.tjahzi.log4j2.labels.Label;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static ru.arc.util.Logging.warn;

@Slf4j
public class Logging {

    public static void addLokiAppender() {
        try {
            Config config = ConfigManager.of(ARC.plugin.getDataPath(), "logging.yml");
            if (!config.bool("enabled", true)) {
                return;
            }
            Label[] labels = config.map("labels", Map.of()).entrySet().stream()
                    .filter(l -> {
                        if (!Label.hasValidName(l.getKey())) {
                            warn("Invalid label: {}", l);
                            return false;
                        }
                        if (l.getValue() == null || !(l.getValue() instanceof String)) {
                            warn("Null value for label: {}", l);
                            return false;
                        }
                        return true;
                    }).map(l -> Label.createLabel(l.getKey(), (String) l.getValue(), null))
                    .toArray(Label[]::new);
            Header[] headers = new Header[labels.length];
            for (int i = 0; i < labels.length; i++) {
                Label label = labels[i];
                headers[i] = Header.createHeader(label.getName(), label.getValue());
            }

            var layout = JsonLayout.newBuilder()
                    .setCompact(true) // Minimize log size
                    .setEventEol(true) // Ensures each log event ends with a newline
                    .setCharset(StandardCharsets.UTF_8)
                    .build();

            var builder = LokiAppender.newBuilder();
            builder.setHost(config.string("host"));
            builder.setPort(config.integer("port", 3100));
            builder.setLabels(labels);
            builder.setHeaders(headers);
            builder.setName("lokiAppender");
            builder.setLayout(layout);
            builder.setFilter(getFilter());
            LokiAppender build = builder.build();
            build.start();


            Logger rootLogger = (Logger) LogManager.getRootLogger();
            Configuration configuration = rootLogger.getContext().getConfiguration();
            configuration.addAppender(build);
            LoggerConfig loggerConfig = configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
            loggerConfig.addAppender(build, Level.INFO, null);
            rootLogger.getContext().updateLoggers();
        } catch (Exception e) {
            warn("Failed to add lokiAppender", e);
        }
    }

    private static Filter getFilter() {
        Config config = ConfigManager.of(ARC.plugin.getDataPath(), "logging.yml");
        return BurstFilter.newBuilder()
                .setLevel(Level.INFO)
                .setRate(config.integer("rate", 20))
                .setMaxBurst(config.integer("maxBurst", 100))
                .build();
    }
}
