package com.logpilot.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 分析报告（页面和 API 共用）。
 *
 * 字段设计对应产品里真正要回答的四个问题：
 *   1. 根因是什么       -> rootCause
 *   2. 凭什么这么判断   -> evidence
 *   3. 有多确定         -> confidence + degraded
 *   4. 该怎么办         -> suggestions
 */
public class AnalysisReport {

    private String rootCause;
    /** 是否降级：true 表示结论只来自规则，LLM 没参与 */
    private boolean degraded;
    /** 降级原因，直接展示给用户，便于排查是"没配 key"还是"调不通" */
    private String degradedReason;
    /** 实际使用的模型名，降级时为 null */
    private String llmModel;
    private double confidence;
    /** LLM 认为证据不足、需要人工复核 */
    private boolean needHuman;
    private int templateCount;
    private long totalLogs;
    private List<String> evidence = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();
    private List<RuleHitView> hits = new ArrayList<>();

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public String getDegradedReason() {
        return degradedReason;
    }

    public void setDegradedReason(String degradedReason) {
        this.degradedReason = degradedReason;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public boolean isNeedHuman() {
        return needHuman;
    }

    public void setNeedHuman(boolean needHuman) {
        this.needHuman = needHuman;
    }

    public int getTemplateCount() {
        return templateCount;
    }

    public void setTemplateCount(int templateCount) {
        this.templateCount = templateCount;
    }

    public long getTotalLogs() {
        return totalLogs;
    }

    public void setTotalLogs(long totalLogs) {
        this.totalLogs = totalLogs;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public List<RuleHitView> getHits() {
        return hits;
    }

    public void setHits(List<RuleHitView> hits) {
        this.hits = hits;
    }

    /** 规则命中视图，给页面渲染用 */
    public static class RuleHitView {
        private final String code;
        private final String name;
        private final String severity;
        private final String suggestion;

        public RuleHitView(String code, String name, String severity, String suggestion) {
            this.code = code;
            this.name = name;
            this.severity = severity;
            this.suggestion = suggestion;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public String getSeverity() {
            return severity;
        }

        public String getSuggestion() {
            return suggestion;
        }
    }
}
