package com.logpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logpilot.config.LlmProperties;
import com.logpilot.engine.DrainTemplateExtractor;
import com.logpilot.engine.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的 LLM 客户端。
 *
 * 关键设计：
 *  - 超时短（默认 8s）：分析是交互请求，不能把用户卡住
 *  - 任何异常都转成 LlmOutcome.failed，不往外抛
 *  - 返回内容做 JSON 容错解析（模型偶尔会包 ```json 代码块）
 */
@Component
public class LlmAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalyzer.class);

    private final LlmProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public LlmAnalyzer(LlmProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(Math.max(props.getTimeoutSeconds(), 1) * 1000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.getBaseUrl())
                .build();
        if (isConfigured()) {
            log.info("LLM 归因已启用: {} @ {}", props.getModel(), props.getBaseUrl());
        } else {
            log.info("LLM 未配置 api-key，将以规则模式运行（degraded=true）");
        }
    }

    public boolean isConfigured() {
        return props.isEnabled()
                && props.getApiKey() != null
                && !props.getApiKey().isBlank();
    }

    public LlmOutcome analyze(String service,
                              List<DrainTemplateExtractor.TemplateView> templates,
                              List<RuleEngine.Hit> hits) {
        if (!props.isEnabled()) {
            return LlmOutcome.skipped("LLM 已关闭（logpilot.llm.enabled=false）");
        }
        if (!isConfigured()) {
            return LlmOutcome.skipped("未配置 LLM api-key，当前为规则模式");
        }
        if (templates == null || templates.isEmpty()) {
            return LlmOutcome.skipped("没有模板可供分析");
        }

        long start = System.currentTimeMillis();
        try {
            String content = callChat(LlmPromptBuilder.systemPrompt(),
                    LlmPromptBuilder.userPrompt(service, templates, hits, props.getMaxTemplates()));
            LlmAnalysis analysis = parse(content);
            log.info("LLM 归因完成，耗时 {} ms，confidence={}", System.currentTimeMillis() - start, analysis.confidence());
            return LlmOutcome.ok(analysis);
        } catch (Exception e) {
            log.warn("LLM 归因失败，降级为规则模式: {}", e.toString());
            return LlmOutcome.failed("LLM 调用失败：" + brief(e));
        }
    }

    private String callChat(String systemPrompt, String userPrompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", 800);
        body.put("response_format", Map.of("type", "json_object"));

        String raw = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(raw);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("返回内容为空");
        }
        return content.asText();
    }

    /**
     * 容错解析：模型有时会包 ```json ... ```，有时前后带解释文字。
     * 这里截取第一个 { 到最后一个 }，再按 JSON 解析。
     */
    LlmAnalysis parse(String content) throws Exception {
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("返回内容不是 JSON");
        }
        JsonNode node = objectMapper.readTree(text.substring(start, end + 1));

        String rootCause = node.path("root_cause").asText("").trim();
        if (rootCause.isEmpty()) {
            throw new IllegalStateException("缺少 root_cause");
        }

        List<String> evidence = readStringList(node.path("evidence"));
        List<String> suggestions = readStringList(node.path("suggestions"));

        double confidence = node.path("confidence").asDouble(0.5d);
        confidence = Math.max(0d, Math.min(1d, confidence));

        boolean needHuman = node.path("need_human").asBoolean(false);

        return new LlmAnalysis(rootCause, evidence, suggestions, confidence, needHuman);
    }

    private static List<String> readStringList(JsonNode arrayNode) {
        List<String> out = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode n : arrayNode) {
                String s = n.asText("").trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String brief(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }
}
