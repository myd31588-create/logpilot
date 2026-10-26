package com.logpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 归因配置。
 *
 * 设计原则：默认"可选"。没有配置 api-key 时整个 LLM 链路自动跳过，
 * 报告退回规则结论（degraded=true），绝不因为外部服务不可用而让主流程失败。
 *
 * 兼容任何 OpenAI Chat Completions 协议的服务：DeepSeek、通义、Moonshot、本地 Ollama 等。
 */
@Component
@ConfigurationProperties(prefix = "logpilot.llm")
public class LlmProperties {

    /** 总开关，关掉就只用规则 */
    private boolean enabled = true;

    /** API Key，建议用环境变量 LLM_API_KEY 注入，不要写进仓库 */
    private String apiKey = "";

    /** OpenAI 兼容的 base url，不要带 /chat/completions */
    private String baseUrl = "https://api.deepseek.com/v1";

    /** 模型名 */
    private String model = "deepseek-chat";

    /** 读超时（秒），超时即降级 */
    private int timeoutSeconds = 8;

    /** 最多喂多少个模板给模型，控制 token 成本 */
    private int maxTemplates = 12;

    /** 采样温度，归因要稳定，所以默认很低 */
    private double temperature = 0.2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxTemplates() {
        return maxTemplates;
    }

    public void setMaxTemplates(int maxTemplates) {
        this.maxTemplates = maxTemplates;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
