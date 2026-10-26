package com.logpilot.engine;

import java.util.regex.Pattern;

/**
 * 日志变量脱敏 / 归一化。
 *
 * 目的：把"会变的部分"（数字、ID、IP、时间戳、UUID、引号内容）统一替换成占位符，
 * 让形状相同的日志在进入聚类之前就尽可能一致，降低模板数量。
 *
 * 注意：脱敏和归一化是两件事，这里合在一起做，但在生产里应该分开
 * （脱敏结果不可逆，归一化只是为了聚类）。
 */
public final class LogMasker {

    /** 数字、小数（不加词边界，让 3000ms / user123 也能归一化成 &lt;*&gt;） */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** IPv4（含端口可选） */
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    /** UUID */
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /** 十六进制串（长于 6 位，避免误伤普通单词） */
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{8,}\\b");
    /** 时间戳：2026-10-24 20:36:25 / 2026-10-24T20:36:25.123 / 20:36:25 */
    private static final Pattern DATETIME = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?)?\\b|\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?\\b");
    /** 手机号 */
    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    /** 身份证 */
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[\\dXx]\\b");

    public static final String PLACEHOLDER = "<*>";

    private LogMasker() {
    }

    /**
     * 归一化：把变量替换成占位符，用于聚类。
     * 顺序很重要：先长后短，否则 IP 会被 NUMBER 先拆掉。
     */
    public static String normalize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String s = message;
        s = DATETIME.matcher(s).replaceAll(PLACEHOLDER);
        s = UUID.matcher(s).replaceAll(PLACEHOLDER);
        s = ID_CARD.matcher(s).replaceAll(PLACEHOLDER);
        s = PHONE.matcher(s).replaceAll(PLACEHOLDER);
        s = IPV4.matcher(s).replaceAll(PLACEHOLDER);
        s = HEX.matcher(s).replaceAll(PLACEHOLDER);
        s = NUMBER.matcher(s).replaceAll(PLACEHOLDER);
        return s.trim();
    }

    /**
     * 脱敏：只处理敏感信息，保留其他变量（用于入库后的原始日志展示）。
     */
    public static String desensitize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String s = message;
        s = ID_CARD.matcher(s).replaceAll("<id>");
        s = PHONE.matcher(s).replaceAll("<phone>");
        s = IPV4.matcher(s).replaceAll("<ip>");
        return s;
    }

    /**
     * 按空白切 token。日志里连续空格很常见，先归一化空白。
     */
    public static String[] tokenize(String message) {
        String s = normalize(message).replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return new String[0];
        }
        return s.split(" ");
    }
}

