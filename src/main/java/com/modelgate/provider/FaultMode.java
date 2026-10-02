package com.modelgate.provider;

/**
 * 故障模式 —— 只有 MockProvider 关心它。
 *
 * 为什么要把故障一个个列出来：真实上游的失败是【随机发生】的，
 * 你今天遇到超时、明天遇到乱码，永远凑不齐一套完整样本，
 * 也就没法系统性验证容错代码。把故障变成可枚举、可复现，是工程化的第一步 ——
 * 这叫「故障注入」（fault injection）。
 *
 * MockProvider 把每个模式映射成一个模型名（如 {@code mock-slow}），
 * 于是"制造故障"和"选择模型"变成了同一件事，压测和演示都不需要额外开关。
 */
public enum FaultMode {

    OK("正常返回", true),
    SLOW("慢响应（超过超时阈值）", true),
    HANG("永久挂起（最危险）", true),
    ERROR("上游 5xx", true),
    AUTH("鉴权失败 401", false),
    GARBAGE("返回一段无关文本", true),
    EMPTY("返回空内容", true),
    TRUNCATED("返回被截断的 JSON", true),
    RANDOM("随机挑一个（模拟真实世界）", true);

    private final String description;
    private final boolean retryable;

    FaultMode(String description, boolean retryable) {
        this.description = description;
        this.retryable = retryable;
    }

    public String description() {
        return description;
    }

    public boolean retryable() {
        return retryable;
    }
}
