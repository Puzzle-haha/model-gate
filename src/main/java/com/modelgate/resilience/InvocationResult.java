package com.modelgate.resilience;

import com.modelgate.provider.ProviderResponse;

import java.util.List;

/**
 * 一次调用的完整结果，包含过程轨迹。
 *
 * 为什么要返回 trace：容错代码最容易变成"黑盒"——出问题时你不知道
 * 到底重试了几次、每次错在哪、是不是走了降级。把过程暴露出来，
 * 调试、可观测性和面试讲解都靠它。
 */
public record InvocationResult(
        /** 是否拿到了真正的上游回复。 */
        boolean success,
        /** 是否走了降级（返回的是兜底内容，不是上游产出）。 */
        boolean degraded,
        /** 上游响应；降级时为 null。 */
        ProviderResponse response,
        /** 最终对外返回的内容。 */
        String content,
        /** 失败原因摘要；成功时为 null。 */
        String error,
        /** 错误分类，便于落库统计。 */
        String errorType,
        /** 实际尝试次数。 */
        int attempts,
        /** 总耗时（毫秒）。 */
        long elapsedMs,
        /** 结束时该供应商的熔断器状态。 */
        String breakerState,
        /** 过程轨迹，逐条可读。 */
        List<String> trace) {
}
