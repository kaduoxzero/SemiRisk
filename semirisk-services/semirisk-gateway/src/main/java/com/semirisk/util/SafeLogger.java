package com.semirisk.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured logging helper that eliminates silent exception swallowing.
 *
 * <p>Every catch block should use one of these methods instead of
 * {@code catch (Exception ignored) {}} or {@code catch (Exception ex) {}}.
 * This ensures failures are observable and debuggable.</p>
 */
public final class SafeLogger {

    private SafeLogger() {}

    /** Log a warning-level message with context and the exception details. */
    public static void warn(Logger log, String context, Throwable ex) {
        log.warn("{}: {}", context, ex.getMessage(), ex);
    }

    /** Log a warning-level message with context and the exception details. */
    public static void warn(String context, Throwable ex) {
        LoggerFactory.getLogger(SafeLogger.class).warn("{}: {}", context, ex.getMessage(), ex);
    }

    /** Log an error-level message with context and the exception details. */
    public static void error(Logger log, String context, Throwable ex) {
        log.error("{}: {}", context, ex.getMessage(), ex);
    }

    /** Log a debug-level message when a failure is expected and non-critical. */
    public static void debug(Logger log, String context, Throwable ex) {
        log.debug("{}: {}", context, ex.getMessage());
    }

    /** Log a warning when a non-critical operation fails (e.g. JSON parse). */
    public static void warnQuiet(Logger log, String context) {
        log.warn("{}: operation skipped due to non-critical failure", context);
    }
}
