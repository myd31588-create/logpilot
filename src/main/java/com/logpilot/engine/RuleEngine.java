package com.logpilot.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 规则引擎：覆盖已知的、高频的故障模式。
 *
 * 设计意图（面试可讲）：
 *  1. 规则负责"快、准、零成本"，覆盖 80% 的已知故障；
 *  2. LLM 负责"规则覆盖不到的、需要跨日志推理的"剩余 20%；
 *  3. LLM 不可用时，规则结论仍然可交付 —— 这是可用性底线。
 */
public class RuleEngine {

    public static final class Rule {
        final String code;
        final String name;
        final Pattern pattern;
        final String severity;
        final String suggestion;

        Rule(String code, String name, String regex, String severity, String suggestion) {
            this.code = code;
            this.name = name;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.severity = severity;
            this.suggestion = suggestion;
        }
    }

    private static final List<Rule> RULES;

    static {
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(new Rule("OOM", "内存溢出",
                "OutOfMemoryError|GC overhead limit exceeded|Java heap space",
                "CRITICAL",
                "检查堆内存与泄漏点：先看 GC 日志和 heap dump，再评估扩容；临时可重启实例止血"));
        rules.add(new Rule("DOWNSTREAM", "下游服务不可用",
                "Connection refused|Connection reset|No route to host|Broken pipe",
                "CRITICAL",
                "确认目标服务是否存活、端口是否监听、网络策略/安全组是否放行"));
        rules.add(new Rule("TIMEOUT", "调用超时",
                "connect timed out|Read timed out|SocketTimeoutException|timeout",
                "MAJOR",
                "检查下游 RT 与线程池/连接池配置；确认是否因下游变慢导致级联超时"));
        rules.add(new Rule("SLOW_SQL", "慢查询",
                "slow query|took \\d+ ?ms|执行时间过长",
                "MAJOR",
                "抓取执行计划（EXPLAIN），检查索引命中、回表、锁等待与数据量"));
        rules.add(new Rule("DISK", "磁盘空间不足",
                "No space left on device|Disk quota exceeded",
                "CRITICAL",
                "清理日志与临时文件，检查是否有异常写入或日志未轮转"));
        rules.add(new Rule("POOL", "连接池耗尽",
                "Could not get JDBC Connection|HikariPool.*(?:exhausted|Timeout)|pool.*exhausted",
                "CRITICAL",
                "检查连接泄漏（未关闭的 Connection）、慢 SQL 占用、池大小配置"));
        rules.add(new Rule("AUTH", "认证/权限异常",
                "401 Unauthorized|403 Forbidden|token (?:expired|invalid)",
                "MINOR",
                "检查令牌有效期、签名密钥、权限配置；确认是否为批量失效"));
        RULES = Collections.unmodifiableList(rules);
    }

    public static final class Hit {
        public final String code;
        public final String name;
        public final String severity;
        public final String suggestion;

        Hit(Rule r) {
            this.code = r.code;
            this.name = r.name;
            this.severity = r.severity;
            this.suggestion = r.suggestion;
        }

        @Override
        public String toString() {
            return "[" + severity + "] " + name + "(" + code + ") → " + suggestion;
        }
    }

    /** 对单条日志做规则匹配，可能命中多条 */
    public List<Hit> match(String message) {
        List<Hit> hits = new ArrayList<Hit>();
        if (message == null || message.isEmpty()) {
            return hits;
        }
        for (Rule r : RULES) {
            if (r.pattern.matcher(message).find()) {
                hits.add(new Hit(r));
            }
        }
        return hits;
    }

    /**
     * 对一个时间窗内的模板集合做规则匹配，返回去重后的结论。
     * 同一条规则只保留一次，避免几十条日志刷出几十条同样的建议。
     */
    public List<Hit> matchTemplates(List<DrainTemplateExtractor.TemplateView> templates) {
        List<Hit> hits = new ArrayList<Hit>();
        List<String> seen = new ArrayList<String>();
        for (DrainTemplateExtractor.TemplateView t : templates) {
            for (Hit h : match(t.template)) {
                if (!seen.contains(h.code)) {
                    seen.add(h.code);
                    hits.add(h);
                }
            }
        }
        return hits;
    }
}
