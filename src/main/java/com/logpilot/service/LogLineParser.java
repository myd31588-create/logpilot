package com.logpilot.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志行解析：
 *   2026-10-24 20:00:01 ERROR [order-service] Connection to 10.0.0.11:8080 failed after 3000ms
 *   -> time / level / service / message
 *
 * 解析不出来时降级为：当前时间 + INFO + unknown + 整行作为 message。
 * 生产环境应支持多套格式（JSON、logback pattern 可配置），v0.2 再做。
 */
public final class LogLineParser {

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?)\\s+" +
            "([A-Za-z]+)\\s+" +
            "\\[([^\\]]+)]\\s+" +
            "(.*)$");

    private static final DateTimeFormatter[] FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    };

    private LogLineParser() {
    }

    public static ParsedLog parse(String line) {
        if (line == null || line.isBlank()) {
            return new ParsedLog(LocalDateTime.now(), "INFO", "unknown", "");
        }
        Matcher m = PATTERN.matcher(line.trim());
        if (m.matches()) {
            LocalDateTime time = parseTime(m.group(1));
            String level = m.group(2).toUpperCase();
            String service = m.group(3).trim();
            String message = m.group(4);
            return new ParsedLog(time, level, service, message);
        }
        return new ParsedLog(LocalDateTime.now(), guessLevel(line), "unknown", line.trim());
    }

    private static LocalDateTime parseTime(String raw) {
        String s = raw.replace('T', ' ');
        for (DateTimeFormatter f : FORMATS) {
            try {
                return LocalDateTime.parse(raw, f);
            } catch (Exception ignored) {
                // try next
            }
        }
        return LocalDateTime.now();
    }

    private static String guessLevel(String line) {
        String upper = line.toUpperCase();
        if (upper.contains("ERROR") || upper.contains("FATAL")) {
            return "ERROR";
        }
        if (upper.contains("WARN")) {
            return "WARN";
        }
        if (upper.contains("DEBUG")) {
            return "DEBUG";
        }
        return "INFO";
    }

    /** 解析结果。record 是 Java 16+ 特性，正好用上。 */
    public record ParsedLog(LocalDateTime time, String level, String service, String message) {
    }
}
