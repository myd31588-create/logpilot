package com.logpilot.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drain 日志模板提取（简化实现）。
 *
 * 原始论文：Drain: An Online Log Parsing Approach with Fixed Depth Tree (ICWS 2017)
 *
 * 本实现的取舍：
 *  - 保留核心思想：按 token 数分组 + 逐 token 比较 + 相似合并 + 通配符替换
 *  - 省略固定深度树，改为同长度桶内线性扫描。模板数 < 1000 时性能足够，
 *    代码量从 ~500 行降到 ~100 行，更利于讲清楚。
 *  - 生产优化方向（面试可讲）：前缀树分桶、模板数上限淘汰、增量更新。
 *
 * 线程安全性：本类非线程安全，调用方需加锁（Spring 里用单例 + synchronized，
 * 或者按 service 分片加锁）。
 */
public class DrainTemplateExtractor {

    /** 相似度阈值：低于它认为是新模板 */
    private final double similarityThreshold;
    /** 每个长度桶最多保留多少模板，防止模板爆炸 */
    private final int maxClustersPerLength;

    /** key = token 数量，value = 该长度下的模板簇 */
    private final Map<Integer, List<Cluster>> buckets = new HashMap<Integer, List<Cluster>>();

    public DrainTemplateExtractor() {
        this(0.5d, 500);
    }

    public DrainTemplateExtractor(double similarityThreshold, int maxClustersPerLength) {
        this.similarityThreshold = similarityThreshold;
        this.maxClustersPerLength = maxClustersPerLength;
    }

    /**
     * 输入一条日志，返回它归属的模板（形如 "Connection to &lt;*&gt;:&lt;*&gt; failed after &lt;*&gt;ms"）。
     * 同一个模板被反复命中时，count 会累加。
     */
    public synchronized TemplateMatch match(String rawMessage) {
        String[] tokens = LogMasker.tokenize(rawMessage);
        if (tokens.length == 0) {
            return new TemplateMatch(0L, "", 0L);
        }

        List<Cluster> bucket = buckets.get(tokens.length);
        if (bucket == null) {
            bucket = new ArrayList<Cluster>();
            buckets.put(tokens.length, bucket);
        }

        Cluster best = null;
        double bestSim = -1d;
        for (Cluster c : bucket) {
            double sim = similarity(c.tokens, tokens);
            if (sim > bestSim) {
                bestSim = sim;
                best = c;
            }
        }

        if (best != null && bestSim >= similarityThreshold) {
            best.merge(tokens);
            return new TemplateMatch(best.id, best.render(), ++best.count);
        }

        // 新模板；桶满时淘汰最久未命中的（简单 LRU 近似）
        if (bucket.size() >= maxClustersPerLength) {
            bucket.remove(oldest(bucket));
        }
        Cluster created = new Cluster(tokens);
        bucket.add(created);
        return new TemplateMatch(created.id, created.render(), created.count);
    }

    /** 当前已提取的模板总数 */
    public synchronized int clusterCount() {
        int n = 0;
        for (List<Cluster> list : buckets.values()) {
            n += list.size();
        }
        return n;
    }

    /** 按命中次数倒序导出模板，用于页面展示 */
    public synchronized List<TemplateView> topTemplates(int limit) {
        List<Cluster> all = new ArrayList<Cluster>();
        for (List<Cluster> list : buckets.values()) {
            all.addAll(list);
        }
        java.util.Collections.sort(all, new java.util.Comparator<Cluster>() {
            public int compare(Cluster a, Cluster b) {
                return Long.compare(b.count, a.count);
            }
        });
        List<TemplateView> out = new ArrayList<TemplateView>();
        for (int i = 0; i < all.size() && i < limit; i++) {
            Cluster c = all.get(i);
            out.add(new TemplateView(c.id, c.render(), c.count, c.firstSeen, c.lastSeen));
        }
        return out;
    }

    // ---------- 相似度 ----------

    /**
     * token 级相似度：相同位置相同 token（或任一方是通配符）算命中。
     * 通配符参与匹配但不参与扣分，避免模板越合并越"松散"。
     */
    static double similarity(String[] a, String[] b) {
        if (a.length != b.length) {
            return 0d;
        }
        int same = 0;
        for (int i = 0; i < a.length; i++) {
            if (isWildcard(a[i]) || isWildcard(b[i]) || a[i].equals(b[i])) {
                same++;
            }
        }
        return (double) same / (double) a.length;
    }

    private static boolean isWildcard(String token) {
        return LogMasker.PLACEHOLDER.equals(token);
    }

    private static int oldest(List<Cluster> list) {
        int idx = 0;
        long min = Long.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).lastSeen < min) {
                min = list.get(i).lastSeen;
                idx = i;
            }
        }
        return idx;
    }

    // ---------- 内部结构 ----------

    private static final class Cluster {
        final long id;
        final String[] tokens;
        long count = 1;
        long firstSeen = System.currentTimeMillis();
        long lastSeen = firstSeen;

        Cluster(String[] tokens) {
            this.id = IDS.incrementAndGet();
            this.tokens = tokens.clone();
        }

        /** 合并：不一致的位置全部变成通配符 */
        void merge(String[] incoming) {
            for (int i = 0; i < tokens.length && i < incoming.length; i++) {
                if (!tokens[i].equals(incoming[i])) {
                    tokens[i] = LogMasker.PLACEHOLDER;
                }
            }
            count++;
            lastSeen = System.currentTimeMillis();
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(tokens[i]);
            }
            return sb.toString();
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(0);

    // ---------- 对外返回 ----------

    /** 单次匹配结果 */
    public static final class TemplateMatch {
        public final long templateId;
        public final String template;
        public final long count;

        public TemplateMatch(long templateId, String template, long count) {
            this.templateId = templateId;
            this.template = template;
            this.count = count;
        }

        @Override
        public String toString() {
            return "TemplateMatch{id=" + templateId + ", count=" + count + ", template='" + template + "'}";
        }
    }

    /** 模板列表视图 */
    public static final class TemplateView {
        public final long id;
        public final String template;
        public final long count;
        public final long firstSeen;
        public final long lastSeen;

        public TemplateView(long id, String template, long count, long firstSeen, long lastSeen) {
            this.id = id;
            this.template = template;
            this.count = count;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }
    }
}
