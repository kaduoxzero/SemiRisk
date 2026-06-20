package com.semirisk.config;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 日志脱敏转换器。
 * 用法：%maskPwd 在 pattern 中。
 */
public class MaskPasswordConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        return msg == null ? null : maskPasswords(msg);
    }

    private String maskPasswords(String message) {
        return message.replaceAll("(?i)(password[=:]\\s*)[^\\s,;\"']+", "$1****");
    }
}
