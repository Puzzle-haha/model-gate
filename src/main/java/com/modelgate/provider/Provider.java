package com.modelgate.provider;

import com.modelgate.resilience.LlmCallException;

import java.util.List;

/**
 * 上游模型供应商的适配契约。
 *
 * 设计要点：
 *  1. 只暴露"能力"（能提供哪些模型）和"动作"（发起一次对话），
 *     不暴露 HTTP 细节。调 HTTP 还是走 SDK，是实现的事。
 *  2. 抛受检的 LlmCallException，强制调用方处理失败 ——
 *     上游失败是常态，不是异常情况。
 *  3. 实现类必须是线程安全的：容错层会从多个线程并发调用它。
 */
public interface Provider {

    /** 供应商标识，用于路由和日志，如 "deepseek"、"mock"。 */
    String name();

    /** 该供应商能提供的模型名列表。路由器据此判断谁能接这个请求。 */
    List<String> models();

    /** 发起一次对话补全。 */
    ProviderResponse chat(ProviderRequest request) throws LlmCallException;

    /**
     * 该供应商期望的调用超时（毫秒）。返回 null 表示"用全局默认值"。
     *
     * 为什么超时要由供应商声明，而不是全局一刀切：
     *   - MockProvider 是本地 sleep，1500ms 足够暴露问题，也方便做实验
     *   - 真实 LLM 生成一段长文本可能要 30–60 秒，用 1500ms 会把
     *     【所有】正常调用都判成超时 —— 超时阈值设错比不设更糟，
     *     因为它会把正常流量全部转成重试，直接把上游打挂
     *
     * 容错层拿到这个值后决定怎么用（目前是"没有显式覆盖就用它"）。
     * 让供应商描述自己的延迟特征，让容错层决定策略 —— 职责是分开的。
     */
    default Long defaultTimeoutMs() {
        return null;
    }

    /**
     * 路由优先级。数字【越小越优先】。
     *
     * 当多个供应商都支持同一个模型名时，路由器按这个值排序，
     * 从优先级最高的开始尝试，失败了再切下一个。
     *
     * 默认 100。MockProvider 用 1000（永远排最后）——
     * 模拟供应商不应该抢占真实流量的位置。
     */
    default int priority() {
        return 100;
    }
}
