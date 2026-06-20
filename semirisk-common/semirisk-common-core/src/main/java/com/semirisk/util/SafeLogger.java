package com.semirisk.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.function.Supplier;

/**
 * 安全执行工具类，统一异常日志格式，避免静默吞掉异常。
 * 所有关键路径（数据持久化、AI 调用、爬虫同步）都应使用此类。
 */
public final class SafeLogger {

    private SafeLogger() {}

    /** 按级别执行带上下文的异常日志。 */
    public static void log(Level level, String context, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            LOG.atLevel(level).log("[{}] {} : {}", context, level.name().toLowerCase(), ex.getMessage());
        }
    }

    /** 按级别执行带返回值的操作。 */
    public static <T> T compute(Level level, String context, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (Exception ex) {
            LOG.atLevel(level).log("[{}] {} : {}", context, level.name().toLowerCase(), ex.getMessage());
            return fallback;
        }
    }

    /** 执行操作，失败时 WARN 级别记录。 */
    public static void warn(String context, Runnable action) {
        log(Level.WARN, context, action);
    }

    /** 执行操作，失败时 ERROR 级别记录。 */
    public static void error(String context, Runnable action) {
        log(Level.ERROR, context, action);
    }

    /** 执行操作，失败时 INFO 级别记录。 */
    public static void info(String context, Runnable action) {
        log(Level.INFO, context, action);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SafeLogger.class);
}
