package com.modelgate.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 容错参数。全部可在 application.yml 里改，也可被单次请求覆盖（方便做实验）。
 */
@Component
@ConfigurationProperties(prefix = "modelgate.resilience")
public class ResilienceProperties {

    /** 单次调用超时（毫秒）。这是最重要的一项 —— 没有它，一次上游卡死就能拖垮整个服务。 */
    private long timeoutMs = 1500;

    /** 最多尝试次数（含首次）。 */
    private int maxAttempts = 3;

    /** 退避基数（毫秒）。实际等待 = base × 2^(n-1) + 随机抖动。 */
    private long backoffBaseMs = 200;

    /**
     * 每个供应商的专用线程池大小。
     * 按供应商隔离（bulkhead），一家卡死不会饿死其他家。
     */
    private int poolSize = 4;

    /** 每个池的等待队列长度。队列满后直接拒绝 —— 这是保护机制，不是 bug。 */
    private int queueCapacity = 8;

    /**
     * 超时后是否中断工作线程。
     *
     * true  → 超时会中断线程，线程被释放，池子能恢复
     * false → 超时只是"不再等待"，线程还在跑。挂起几次之后池子就被永久占死，
     *         后续所有请求全部被拒绝 —— 服务相当于挂了（Phase 0 实验 6 已验证）
     *
     * 生产代码必须为 true。
     */
    private boolean cancelOnTimeout = true;

    /** 单个供应商连续失败多少次后熔断。 */
    private int breakerFailureThreshold = 5;

    /** 熔断后冷却多久再放探针。 */
    private long breakerOpenMs = 10000;

    /** MockProvider 的"慢响应"耗时（毫秒）。默认要明显超过超时阈值。 */
    private long mockSlowMs = 5000;

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public long getBackoffBaseMs() { return backoffBaseMs; }
    public void setBackoffBaseMs(long backoffBaseMs) { this.backoffBaseMs = backoffBaseMs; }

    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public boolean isCancelOnTimeout() { return cancelOnTimeout; }
    public void setCancelOnTimeout(boolean cancelOnTimeout) { this.cancelOnTimeout = cancelOnTimeout; }

    public int getBreakerFailureThreshold() { return breakerFailureThreshold; }
    public void setBreakerFailureThreshold(int breakerFailureThreshold) { this.breakerFailureThreshold = breakerFailureThreshold; }

    public long getBreakerOpenMs() { return breakerOpenMs; }
    public void setBreakerOpenMs(long breakerOpenMs) { this.breakerOpenMs = breakerOpenMs; }

    public long getMockSlowMs() { return mockSlowMs; }
    public void setMockSlowMs(long mockSlowMs) { this.mockSlowMs = mockSlowMs; }
}
