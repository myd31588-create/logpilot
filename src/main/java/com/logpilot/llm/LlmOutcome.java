package com.logpilot.llm;

/**
 * LLM 调用的三种结局。
 *
 * 显式建模"跳过"和"失败"，而不是返回 null —— 因为报告里要告诉用户
 * 到底是"没配 key"还是"调不通"，这两种情况的排查动作完全不同。
 */
public record LlmOutcome(Status status, LlmAnalysis analysis, String reason) {

    public enum Status {
        /** 成功拿到结构化结果 */
        OK,
        /** 没启用或没配 key，根本没发请求 */
        SKIPPED,
        /** 发了请求但失败：超时、限流、返回不合法 */
        FAILED
    }

    public static LlmOutcome ok(LlmAnalysis analysis) {
        return new LlmOutcome(Status.OK, analysis, null);
    }

    public static LlmOutcome skipped(String reason) {
        return new LlmOutcome(Status.SKIPPED, null, reason);
    }

    public static LlmOutcome failed(String reason) {
        return new LlmOutcome(Status.FAILED, null, reason);
    }
}
