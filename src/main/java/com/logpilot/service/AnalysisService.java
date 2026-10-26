package com.logpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logpilot.cache.Fingerprint;
import com.logpilot.cache.RedisGuard;
import com.logpilot.config.LlmProperties;
import com.logpilot.engine.DrainTemplateExtractor;
import com.logpilot.engine.RuleEngine;
import com.logpilot.llm.LlmAnalysis;
import com.logpilot.llm.LlmAnalyzer;
import com.logpilot.llm.LlmOutcome;
import com.logpilot.repo.LogEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 根因分析：规则优先，LLM 增强。
 *
 * 执行顺序是有意为之：
 *   1. 先跑规则，拿到一份"一定能交付"的结论
 *   2. 再调 LLM，成功则覆盖根因、补充证据与建议
 *   3. LLM 任何异常都只是让报告标记 degraded，不影响规则结论
 *
 * Redis 在这里承担两件事：
 *   - 分布式锁：同一个服务的分析串行化，避免并发请求同时打模型（token 是真金白银）
 *   - 结果缓存：模板没变时直接复用上一次的 LLM 结论
 * Redis 挂了锁退化为"总能拿到"，缓存退化为"总是未命中"，功能不受影响。
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private static final int MAX_SUGGESTIONS = 6;
    private static final int MAX_EVIDENCE = 5;
    private static final Duration LOCK_TTL = Duration.ofSeconds(15);

    private final LogEventRepository repository;
    private final RuleEngine ruleEngine;
    private final LlmAnalyzer llmAnalyzer;
    private final LlmProperties llmProperties;
    private final RedisGuard redis;
    private final ObjectMapper objectMapper;

    @Value("${logpilot.llm.cache-seconds:30}")
    private int cacheSeconds;

    public AnalysisService(LogEventRepository repository,
                           RuleEngine ruleEngine,
                           LlmAnalyzer llmAnalyzer,
                           LlmProperties llmProperties,
                           RedisGuard redis,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.ruleEngine = ruleEngine;
        this.llmAnalyzer = llmAnalyzer;
        this.llmProperties = llmProperties;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public AnalysisReport analyze(String service, int topN) {
        Pageable page = PageRequest.of(0, Math.max(1, topN));
        List<LogEventRepository.TemplateCount> counts = (service == null || service.isBlank())
                ? repository.findTemplateCounts(page)
                : repository.findTemplateCountsByService(service, page);

        AnalysisReport report = new AnalysisReport();
        report.setTemplateCount(counts.size());
        report.setDegraded(true);

        long total = 0;
        List<DrainTemplateExtractor.TemplateView> views = new ArrayList<>();
        for (LogEventRepository.TemplateCount c : counts) {
            total += c.getCnt();
            views.add(new DrainTemplateExtractor.TemplateView(0L, c.getTemplate(), c.getCnt(), 0L, 0L));
            report.getEvidence().add(c.getCnt() + " 次  " + c.getTemplate());
        }
        report.setTotalLogs(total);

        if (views.isEmpty()) {
            report.setRootCause("暂无日志，先灌一点数据再分析");
            report.setConfidence(0d);
            report.setDegradedReason("没有数据");
            return report;
        }

        // ---------- 第一步：规则 ----------
        List<RuleEngine.Hit> hits = ruleEngine.matchTemplates(views);
        for (RuleEngine.Hit h : hits) {
            report.getHits().add(new AnalysisReport.RuleHitView(h.code, h.name, h.severity, h.suggestion));
            report.getSuggestions().add(h.name + "：" + h.suggestion);
        }

        if (hits.isEmpty()) {
            report.setRootCause("未命中已知故障模式，需要人工判断");
            report.setConfidence(0.3d);
        } else {
            // 根因优先取 CRITICAL —— 按出现次数排序时，高频但不致命的规则会排在前面
            RuleEngine.Hit primary = null;
            for (RuleEngine.Hit h : hits) {
                if ("CRITICAL".equals(h.severity)) {
                    primary = h;
                    break;
                }
            }
            if (primary == null) {
                primary = hits.get(0);
            }
            report.setRootCause("疑似「" + primary.name + "」，优先排查该方向");
            report.setConfidence("CRITICAL".equals(primary.severity) ? 0.75d : 0.6d);
        }

        // ---------- 第二步：LLM 增强（带锁与缓存） ----------
        applyLlm(analyzeWithRedis(service, views, hits), report);

        // ---------- 收尾：截断 ----------
        if (report.getEvidence().size() > MAX_EVIDENCE) {
            report.setEvidence(new ArrayList<>(report.getEvidence().subList(0, MAX_EVIDENCE)));
        }
        if (report.getSuggestions().size() > MAX_SUGGESTIONS) {
            report.setSuggestions(new ArrayList<>(report.getSuggestions().subList(0, MAX_SUGGESTIONS)));
        }
        return report;
    }

    /**
     * 加锁 + 缓存地调用 LLM。
     * 三条分支：未配置 -> 直接返回 skipped；缓存命中 -> 复用；否则抢锁调用。
     */
    private LlmOutcome analyzeWithRedis(String service,
                                        List<DrainTemplateExtractor.TemplateView> views,
                                        List<RuleEngine.Hit> hits) {
        if (!llmAnalyzer.isConfigured()) {
            return llmAnalyzer.analyze(service, views, hits);
        }

        String cacheKey = cacheKey(service, views);
        Optional<String> cached = redis.get(cacheKey);
        if (cached.isPresent()) {
            try {
                return LlmOutcome.ok(objectMapper.readValue(cached.get(), LlmAnalysis.class));
            } catch (Exception e) {
                log.warn("LLM 缓存解析失败，忽略缓存: {}", e.toString());
            }
        }

        String lockKey = "logpilot:analysis:lock:" + (service == null || service.isBlank() ? "all" : service);
        if (!redis.tryLock(lockKey, LOCK_TTL)) {
            return LlmOutcome.skipped("另一个分析正在进行，本次跳过 LLM 调用");
        }
        try {
            LlmOutcome outcome = llmAnalyzer.analyze(service, views, hits);
            if (outcome.status() == LlmOutcome.Status.OK) {
                try {
                    redis.set(cacheKey, objectMapper.writeValueAsString(outcome.analysis()),
                            Duration.ofSeconds(Math.max(cacheSeconds, 1)));
                } catch (Exception e) {
                    log.warn("LLM 结果缓存写入失败: {}", e.toString());
                }
            }
            return outcome;
        } finally {
            redis.release(lockKey);
        }
    }

    /** 缓存 key 由"服务 + 模板快照"决定：模板一变就自然失效，不需要手动清理 */
    private String cacheKey(String service, List<DrainTemplateExtractor.TemplateView> views) {
        StringJoiner joiner = new StringJoiner("|");
        int limit = Math.min(views.size(), 8);
        for (int i = 0; i < limit; i++) {
            DrainTemplateExtractor.TemplateView v = views.get(i);
            joiner.add(v.count + ":" + v.template);
        }
        String sig = Fingerprint.of(service, String.valueOf(views.size()), joiner.toString());
        return "logpilot:llm:" + sig;
    }

    private void applyLlm(LlmOutcome outcome, AnalysisReport report) {
        if (outcome.status() != LlmOutcome.Status.OK) {
            report.setDegraded(true);
            report.setDegradedReason(outcome.reason());
            return;
        }

        LlmAnalysis llm = outcome.analysis();
        report.setDegraded(false);
        report.setDegradedReason(null);
        report.setLlmModel(llmProperties.getModel());
        report.setRootCause(llm.rootCause());
        report.setConfidence(llm.confidence());
        report.setNeedHuman(llm.needHuman());

        if (!llm.evidence().isEmpty()) {
            report.setEvidence(llm.evidence());
        }

        // LLM 建议放前面，规则建议作为兜底补充，去重后截断
        LinkedHashSet<String> merged = new LinkedHashSet<>(llm.suggestions());
        merged.addAll(report.getSuggestions());
        report.setSuggestions(new ArrayList<>(merged));
    }
}
