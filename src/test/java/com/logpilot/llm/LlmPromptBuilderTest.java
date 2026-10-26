package com.logpilot.llm;

import com.logpilot.engine.DrainTemplateExtractor;
import com.logpilot.engine.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPromptBuilderTest {

    @Test
    void Prompt应该包含模板计数与规则命中() {
        List<DrainTemplateExtractor.TemplateView> templates = List.of(
                new DrainTemplateExtractor.TemplateView(1L, "Connection to <*> failed after <*>ms", 12L, 0L, 0L),
                new DrainTemplateExtractor.TemplateView(2L, "java.lang.OutOfMemoryError: Java heap space", 3L, 0L, 0L)
        );
        List<RuleEngine.Hit> hits = new RuleEngine().match("java.lang.OutOfMemoryError: Java heap space");

        String prompt = LlmPromptBuilder.userPrompt("order-service", templates, hits, 12);

        assertTrue(prompt.contains("order-service"));
        assertTrue(prompt.contains("Connection to <*> failed after <*>ms"));
        assertTrue(prompt.contains("[12 次]"));
        assertTrue(prompt.contains("OOM"));
        assertTrue(prompt.contains("\"root_cause\""));
    }

    @Test
    void 模板数量超过上限时应该截断() {
        List<DrainTemplateExtractor.TemplateView> templates = List.of(
                new DrainTemplateExtractor.TemplateView(1L, "A", 3L, 0L, 0L),
                new DrainTemplateExtractor.TemplateView(2L, "B", 2L, 0L, 0L),
                new DrainTemplateExtractor.TemplateView(3L, "C", 1L, 0L, 0L)
        );

        String prompt = LlmPromptBuilder.userPrompt(null, templates, List.of(), 2);

        assertTrue(prompt.contains("A"));
        assertTrue(prompt.contains("B"));
        assertTrue(!prompt.contains("1. [1 次] C"), "第三个模板应被截断");
        assertTrue(prompt.contains("全部服务"));
    }
}
