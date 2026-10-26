package com.logpilot.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 日志指纹：同一条日志（同一服务、同一级别、同样内容）算同一个指纹。
 *
 * 用途：日志风暴时（比如一次故障刷出 10 万条同样的错误），
 * 同一个指纹在窗口期内只入库一次，避免把数据库打爆。
 *
 * 用 SHA-256 而不是 String.hashCode()：后者 32 位、冲突率高，
 * 冲突意味着两条不同的日志被当成重复而丢掉。
 */
public final class Fingerprint {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Fingerprint() {
    }

    public static String of(String service, String level, String content) {
        String raw = (service == null ? "" : service)
                + '|' + (level == null ? "" : level)
                + '|' + (content == null ? "" : content);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[32];
            for (int i = 0; i < 16; i++) {
                out[i * 2] = HEX[(digest[i] >> 4) & 0x0f];
                out[i * 2 + 1] = HEX[digest[i] & 0x0f];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
