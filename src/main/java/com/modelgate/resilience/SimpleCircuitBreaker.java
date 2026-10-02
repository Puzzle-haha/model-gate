package com.modelgate.resilience;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简熔断器 —— 三态状态机。
 *
 * <pre>
 *           失败达到阈值
 *   CLOSED ──────────────► OPEN
 *     ▲                     │  冷却时间到
 *     │  探针成功            ▼
 *     └──────────────── HALF_OPEN
 *          探针失败 ─────────┘（回到 OPEN）
 * </pre>
 *
 * 它解决什么问题：当下游已经挂了，继续把请求打过去只会让情况更糟 ——
 * 你自己的线程被占满、下游被压得更起不来。熔断的作用是【快速失败】，
 * 把资源省下来，也给下游喘息的机会。
 *
 * 面试常被追问的两点，这里都处理了：
 *   1. HALF_OPEN 只能放【一个】探针。如果放开所有请求，那不叫试探，叫雪崩第二波。
 *   2. 失败计数要在成功后清零，否则偶发失败会累积到阈值造成误熔断。
 *
 * ============================================================================
 * 为什么 HALF_OPEN 必须有超时（一个真实踩到的设计缺陷）
 * ============================================================================
 * 最初的实现是：进入 HALF_OPEN → 放行一个探针 → 之后一律拒绝，
 * **直到探针回调 recordSuccess / recordFailure**。
 *
 * 问题在于：**这个回调不保证会来。**
 *   - 调用链上抛出了未预期的未检查异常，跳过了回报
 *   - CancellationException 之类的边界情况
 *   - 报告线程本身出了意外
 *
 * 一旦回调没来，状态就永久停在 HALF_OPEN，`allowRequest()` 永远返回 false ——
 * **这个 (供应商, 模型) 就永久不可用了，而且没有任何告警**。
 *
 * 这是"静默死亡"的又一个变体：不是线程池被占死，而是状态机卡死。
 * 两者的共同教训是 ——
 * **状态机绝不能依赖一个"可能永远不来的回调"来推进。**
 * 凡是等待外部回报的状态，都必须有超时兜底。
 *
 * 所以这里给 HALF_OPEN 也加了时限：超过 probeTimeoutMs 还没等到回报，
 * 就当作探针失败，退回 OPEN 重新冷却。
 *
 * ⚠️ 一个必须说明的取舍：如果 probeTimeoutMs 比真实的调用超时还短，
 * 就可能在旧探针仍在飞的时候放开新探针 —— 短暂出现两个并发探针，
 * 破坏了"单探针"的严格性。
 *
 * 但这是**刻意选择**的：用"极端情况下短暂多一个探针"换"绝不永久卡死"。
 * 前者的代价是有界的（多打一次上游），后者是无限的（服务永久不可用）。
 * 所以 probeTimeoutMs 的默认值取得明显大于任何合理的调用超时。
 *
 * 注意：这个实例是【按 (供应商, 模型)】创建的（见 ProviderInvoker 的 breakerFor）。
 *
 * 熔断必须按依赖隔离，但粒度选择有讲究：
 *   - 早期版本按【供应商】熔断，实测发现一个问题 —— 某个模型失败会把该供应商下
 *     **所有模型**都熔断掉，一个模型被限流就导致全家的服务不可用。
 *   - 现在按 (供应商, 模型)，因为限流、模型下线这类故障往往是单个模型的。
 *
 * 对照：线程池的隔离粒度仍然是【供应商】—— 它关心的是"别把上游打死"，
 * 按模型建池会让 N 个模型 × 每池 4 线程的并发全打到同一家。
 */
public class SimpleCircuitBreaker {

    public enum State {
        /** 正常放行。 */
        CLOSED,
        /** 熔断中，一律快速失败。 */
        OPEN,
        /** 冷却结束，放一个探针试探。 */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long openDurationMs;
    private final long probeTimeoutMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    private volatile State state = State.CLOSED;
    private volatile long openedAt = 0L;
    /** 进入 HALF_OPEN 的时刻，用于判断探针是否已经超时。 */
    private volatile long halfOpenedAt = 0L;

    public SimpleCircuitBreaker(int failureThreshold, long openDurationMs) {
        // 探针时限默认取【冷却时长】和 30 秒中的较大者。
        // 冷却时长通常已经明显大于调用超时（本项目的 10s vs 1.5s），
        // 再加一个 30 秒的下限，确保不会比任何合理的调用超时还短 ——
        // 见类注释里对"短暂双探针"取舍的说明。
        this(failureThreshold, openDurationMs, Math.max(openDurationMs, 30_000L));
    }

    public SimpleCircuitBreaker(int failureThreshold, long openDurationMs, long probeTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
        this.probeTimeoutMs = probeTimeoutMs;
    }

    /** 是否放行本次请求。 */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= openDurationMs) {
                state = State.HALF_OPEN;
                halfOpenedAt = System.currentTimeMillis();
                probeInFlight.set(true);
                return true;   // 放行这一个探针
            }
            return false;
        }
        if (state == State.HALF_OPEN) {
            // 探针超时兜底：回调迟迟不来时不能让状态机永久卡死。
            // 退回 OPEN 重新冷却，而不是一直拒绝下去。
            if (System.currentTimeMillis() - halfOpenedAt >= probeTimeoutMs) {
                state = State.OPEN;
                openedAt = System.currentTimeMillis();
                probeInFlight.set(false);
                return false;
            }
            // 已经有一个探针在飞，其余请求快速失败 —— 否则就不是"试探"了
            return false;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        probeInFlight.set(false);
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        probeInFlight.set(false);
        if (state == State.HALF_OPEN) {
            // 探针失败：立刻回到 OPEN，重新开始冷却
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
        }
    }

    public synchronized void reset() {
        consecutiveFailures.set(0);
        probeInFlight.set(false);
        state = State.CLOSED;
        openedAt = 0L;
        halfOpenedAt = 0L;
    }

    public State state() {
        return state;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public long openDurationMs() {
        return openDurationMs;
    }

    public long probeTimeoutMs() {
        return probeTimeoutMs;
    }
}
