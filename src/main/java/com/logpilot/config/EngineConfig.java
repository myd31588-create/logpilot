package com.logpilot.config;

import com.logpilot.engine.DrainTemplateExtractor;
import com.logpilot.engine.RuleEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把纯 Java 的引擎类注册成 Spring Bean。
 *
 * 注意：DrainTemplateExtractor 内部有可变状态（模板桶），是单例。
 * 单机多实例部署时会导致各实例模板不一致，v0.2 要把模板状态外置到 Redis。
 */
@Configuration
public class EngineConfig {

    @Bean
    public DrainTemplateExtractor drainTemplateExtractor() {
        // 相似度阈值 0.6；每个长度桶最多 500 个模板，防止模板爆炸
        return new DrainTemplateExtractor(0.6d, 500);
    }

    @Bean
    public RuleEngine ruleEngine() {
        return new RuleEngine();
    }
}
