package com.coloryr.allmusic.client.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ClientLogLevels {
    private ClientLogLevels() {
    }

    static void apply(List<String> quiet) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (String name : quiet) {
            if (name != null && !name.trim().isEmpty()) {
                String prefix = name.trim();
                prefixes.add(prefix);
                // 构建包重定位了 HTTP 依赖，开发环境和发布包都需要匹配。
                if (prefix.startsWith("org.apache.hc.")) {
                    prefixes.add("com.coloryr.allmusic.libs." + prefix);
                }
            }
        }
        if (prefixes.isEmpty()) {
            return;
        }
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        Level rootLevel = context.getRootLogger().getLevel();
        Map<String, Level> levels = new HashMap<>();
        for (Logger logger : context.getLoggers()) {
            levels.put(logger.getName(), logger.getLevel());
        }
        for (String name : prefixes) {
            LoggerConfig logger = configuration.getLoggers().get(name);
            if (logger == null) {
                configuration.addLogger(name, new LoggerConfig(name, Level.INFO, true));
            } else {
                logger.setLevel(Level.INFO);
            }
        }
        context.updateLoggers(configuration);
        // 更新配置会重设其他模组在运行时指定的等级，恢复未选中的日志。
        context.getRootLogger().setLevel(rootLevel);
        for (Map.Entry<String, Level> entry : levels.entrySet()) {
            boolean selected = false;
            for (String prefix : prefixes) {
                if (entry.getKey().equals(prefix) || entry.getKey().startsWith(prefix + ".")) {
                    selected = true;
                    break;
                }
            }
            if (!selected) {
                context.getLogger(entry.getKey()).setLevel(entry.getValue());
            }
        }
    }
}
