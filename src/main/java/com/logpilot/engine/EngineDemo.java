package com.logpilot.engine;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 自测入口：验证 Drain 聚类与规则引擎的逻辑。
 *
 * 运行：
 *   javac -encoding UTF-8 -d out src/main/java/com/logpilot/engine/*.java
 *   java -cp out com.logpilot.engine.EngineDemo
 *
 * 也可以传文件：java -cp out com.logpilot.engine.EngineDemo sample.log
 */
public final class EngineDemo {

    private static final String[] SAMPLES = {
        "2026-10-24 20:00:01 ERROR [order-service] Connection to 10.0.0.11:8080 failed after 3000ms",
        "2026-10-24 20:00:02 ERROR [order-service] Connection to 10.0.0.12:8080 failed after 5120ms",
        "2026-10-24 20:00:03 ERROR [order-service] Connection to 10.0.0.13:8080 failed after 2980ms",
        "2026-10-24 20:00:04 ERROR [order-service] java.lang.OutOfMemoryError: Java heap space",
        "2026-10-24 20:00:05 WARN  [order-service] Slow query detected: select * from t_order where id = 12345 took 2100ms",
        "2026-10-24 20:00:06 WARN  [order-service] Slow query detected: select * from t_order where id = 99999 took 3300ms",
        "2026-10-24 20:00:07 INFO  [order-service] order 10086 created, userId=2001, amount=99.00",
        "2026-10-24 20:00:08 INFO  [order-service] order 10087 created, userId=2002, amount=129.50",
        "2026-10-24 20:00:09 INFO  [order-service] order 10088 created, userId=2003, amount=58.00",
        "2026-10-24 20:00:10 ERROR [pay-service] Could not get JDBC Connection, HikariPool-1 - Connection is not available, request timed out after 30000ms",
        "2026-10-24 20:00:11 ERROR [pay-service] phone 13812345678 login failed, token expired",
        "2026-10-24 20:00:12 ERROR [pay-service] No space left on device writing /data/pay/2026-10-24.log",
    };

    public static void main(String[] args) throws Exception {
        DrainTemplateExtractor extractor = new DrainTemplateExtractor(0.6d, 500);
        RuleEngine rules = new RuleEngine();

        System.out.println("=== 输入日志 ===");
        if (args.length > 0) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(args[0]), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    feed(extractor, rules, line);
                }
            }
            reader.close();
        } else {
            for (String s : SAMPLES) {
                feed(extractor, rules, s);
            }
        }

        System.out.println();
        System.out.println("=== 聚类结果（" + extractor.clusterCount() + " 个模板）===");
        List<DrainTemplateExtractor.TemplateView> top = extractor.topTemplates(20);
        for (DrainTemplateExtractor.TemplateView v : top) {
            System.out.println(String.format("%4d x  %s", v.count, v.template));
        }

        System.out.println();
        System.out.println("=== 规则命中 ===");
        for (RuleEngine.Hit h : rules.matchTemplates(top)) {
            System.out.println(h);
        }
    }

    private static void feed(DrainTemplateExtractor extractor, RuleEngine rules, String line) {
        DrainTemplateExtractor.TemplateMatch m = extractor.match(line);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-8s -> %s", "[" + m.count + "]", m.template));
        List<RuleEngine.Hit> hits = rules.match(line);
        if (!hits.isEmpty()) {
            sb.append("   ");
            for (RuleEngine.Hit h : hits) {
                sb.append(h.code).append(' ');
            }
        }
        System.out.println(sb.toString());
    }
}
