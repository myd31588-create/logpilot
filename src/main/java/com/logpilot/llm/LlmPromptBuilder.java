package com.logpilot.llm;

import com.logpilot.engine.DrainTemplateExtractor;
import com.logpilot.engine.RuleEngine;

import java.util.List;

/**
 * 构造给 LLM 的 Prompt。
 *
 * 三条原则（面试可讲）：
 *  1. 只喂"模板 + 计数 + 规则命中"，不喂原始日志 —— 原始日志噪声大、token 贵、还会诱导模型编造细节。
 *  2. 强制 JSON 输出 —— 自然语言无法结构化成页面字段。
 *  3. 明确禁止编造 —— 日志里没有的东西不许猜，宁可回答"证据不足"。
 */
public final class LlmPromptBuilder {

    private LlmPromptBuilder() {
    }

    public static String systemPrompt() {
        return """
                你是一名资深 SRE，擅长根据日志模板的统计特征定位线上故障根因。
                你只输出 JSON，不输出任何解释性文字，不使用 Markdown 代码块。
                你绝对不能编造输入里没有出现的服务名、错误信息或时间。
                """;
    }

    public static String userPrompt(String service,
                                    List<DrainTemplateExtractor.TemplateView> templates,
                                    List<RuleEngine.Hit> hits,
                                    int maxTemplates) {
        StringBuilder sb = new StringBuilder();
        sb.append("服务：").append(service == null || service.isBlank() ? "全部服务" : service).append('\n');

        sb.append("\n日志模板（按出现次数倒序）：\n");
        int limit = Math.min(Math.max(maxTemplates, 1), templates.size());
        if (limit == 0) {
            sb.append("（无）\n");
        }
        for (int i = 0; i < limit; i++) {
            DrainTemplateExtractor.TemplateView t = templates.get(i);
            sb.append(i + 1).append(". [").append(t.count).append(" 次] ").append(t.template).append('\n');
        }

        sb.append("\n规则引擎命中：");
        if (hits == null || hits.isEmpty()) {
            sb.append("无\n");
        } else {
            sb.append('\n');
            for (RuleEngine.Hit h : hits) {
                sb.append("- ").append(h.code).append("（").append(h.name).append("，").append(h.severity).append("）\n");
            }
        }

        sb.append("""

                请基于以上信息输出一个 JSON 对象，字段如下：
                {
                  "root_cause": "一句话说清最可能的根因",
                  "evidence": ["2-4 条证据，必须引用上面的模板或次数"],
                  "suggestions": ["2-3 条可执行的处理动作"],
                  "confidence": 0.0,
                  "need_human": false
                }

                要求：
                1. confidence 取 0 到 1 之间的小数；证据不足时给低分，并把 need_human 设为 true。
                2. evidence 里禁止出现输入中不存在的服务名或错误。
                3. 只输出 JSON，不要代码块标记，不要额外说明。
                """);
        return sb.toString();
    }
}
