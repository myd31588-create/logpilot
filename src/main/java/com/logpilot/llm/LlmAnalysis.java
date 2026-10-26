package com.logpilot.llm;

import java.util.List;

/**
 * LLM 返回的结构化归因结果。
 *
 * 为什么强制 JSON：自然语言没法直接展示成字段，也没法做置信度判断。
 * 解析失败就当没有结果，走规则兜底——这是"宁可降级也不要错"。
 */
public record LlmAnalysis(
        String rootCause,
        List<String> evidence,
        List<String> suggestions,
        double confidence,
        boolean needHuman
) {
}
