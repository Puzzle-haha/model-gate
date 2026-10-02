package com.modelgate.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断状态机测试。
 *
 * 为什么这个类值得单测：状态机的 bug 是**静默的**。
 * 写错了不会抛异常，只会在生产环境表现为"服务莫名其妙开始拒绝所有请求"
 * 或者"下游已经恢复了但熔断一直不放开"。手工测试基本发现不了。
 *
 * 三个关键行为：
 *   1. 连续失败到阈值才熔断（偶发失败不该熔断）
 *   2. HALF_OPEN 只放一个探针（放开所有请求就不是试探，是雪崩第二波）
 *   3. 成功后失败计数清零（否则偶发失败会累积成误熔断）
 */
class SimpleCircuitBreakerTest {

    private static final int THRESHOLD = 3;
    private static final long OPEN_MS = 80;

    private SimpleCircuitBreaker newBreaker() {
        return new SimpleCircuitBreaker(THRESHOLD, OPEN_MS);
    }

    @Test
    @DisplayName("初始状态为 CLOSED，且放行请求")
    void startsClosedAndAllows() {
        SimpleCircuitBreaker breaker = newBreaker();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("失败次数未达阈值时不熔断")
    void staysClosedBelowThreshold() {
        SimpleCircuitBreaker breaker = newBreaker();

        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("连续失败达到阈值后熔断，并拒绝请求")
    void opensAtThreshold() {
        SimpleCircuitBreaker breaker = newBreaker();

        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("成功会清零失败计数 —— 否则偶发失败会累积成误熔断")
    void successResetsFailureCount() {
        SimpleCircuitBreaker breaker = newBreaker();

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        assertThat(breaker.consecutiveFailures()).isZero();

        // 再来两次仍然不该熔断（如果计数没清零，这里就变成 4 次，超过阈值了）
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("冷却时间到后进入 HALF_OPEN，且只放行一个探针")
    void halfOpenAllowsExactlyOneProbe() throws InterruptedException {
        SimpleCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.allowRequest()).isFalse();

        Thread.sleep(OPEN_MS + 30);

        // 第一个请求拿到探针资格
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.HALF_OPEN);

        // 后续请求必须被拒 —— 如果这里放行，就不是"试探"而是让所有请求
        // 一起去打还没恢复的下游，等于雪崩第二波
        assertThat(breaker.allowRequest()).isFalse();
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("探针成功则恢复到 CLOSED")
    void probeSuccessClosesCircuit() throws InterruptedException {
        SimpleCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        Thread.sleep(OPEN_MS + 30);

        assertThat(breaker.allowRequest()).isTrue();   // 拿到探针
        breaker.recordSuccess();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("探针失败则立刻回到 OPEN，并重新开始冷却")
    void probeFailureReopensCircuit() throws InterruptedException {
        SimpleCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        Thread.sleep(OPEN_MS + 30);

        assertThat(breaker.allowRequest()).isTrue();   // 拿到探针
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
        // 冷却时间重新开始，所以此刻不放行
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("reset 后完全恢复初始状态")
    void resetRestoresInitialState() {
        SimpleCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }

        breaker.reset();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.consecutiveFailures()).isZero();
        assertThat(breaker.allowRequest()).isTrue();
    }

    // ================================================================
    //  HALF_OPEN 超时兜底 —— 守护"状态机永久卡死"这个静默故障
    // ================================================================

    @Test
    @DisplayName("★ 探针永不回报时，不能永久卡在 HALF_OPEN（否则该模型永久不可用）")
    void probeTimeoutPreventsPermanentLockout() throws InterruptedException {
        // 冷却 50ms、探针时限 120ms
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(THRESHOLD, 50, 120);
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);

        Thread.sleep(70);
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.HALF_OPEN);

        // 关键：探针【永远不回调 recordSuccess / recordFailure】
        Thread.sleep(150);

        // 修复前：永久返回 false，状态永远停在 HALF_OPEN
        // 修复后：退回 OPEN，重新开始冷却
        assertThat(breaker.allowRequest()).isFalse();
        assertThat(breaker.state())
                .as("探针超时后必须退回 OPEN，不能停在 HALF_OPEN")
                .isEqualTo(SimpleCircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("★ 探针超时退回 OPEN 后，再等一个冷却期仍能重新试探（说明没有死锁）")
    void recoversAfterProbeTimeout() throws InterruptedException {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(THRESHOLD, 50, 120);
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }

        Thread.sleep(70);
        assertThat(breaker.allowRequest()).isTrue();   // 探针 1
        Thread.sleep(150);                             // 探针 1 从未回报 → 超时
        assertThat(breaker.allowRequest()).isFalse();  // 退回 OPEN

        Thread.sleep(70);                              // 再等一个冷却期
        assertThat(breaker.allowRequest())
                .as("必须能重新放行探针，否则这个模型就永久不可用了")
                .isTrue();

        // 这次的探针正常回报成功 → 完全恢复
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("探针在时限内正常回报时，超时逻辑不干扰它")
    void probeReportingWithinTimeoutIsHonored() throws InterruptedException {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(THRESHOLD, 50, 5000);
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }

        Thread.sleep(70);
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.HALF_OPEN);

        breaker.recordSuccess();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("默认构造的探针时限不小于 30 秒（必须明显大于任何合理的调用超时）")
    void defaultProbeTimeoutIsGenerous() {
        // 这个约束的理由见类注释：探针时限若短于真实调用超时，
        // 就可能放开第二个探针，破坏"单探针"的严格性。
        // 用"短暂多一个探针"换"绝不永久卡死"是刻意的取舍，
        // 但前提是时限足够长，不至于在正常情况下误触发。
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(THRESHOLD, 1000);
        assertThat(breaker.probeTimeoutMs()).isGreaterThanOrEqualTo(30_000L);

        SimpleCircuitBreaker longCooldown = new SimpleCircuitBreaker(THRESHOLD, 60_000);
        assertThat(longCooldown.probeTimeoutMs())
                .as("冷却时长更长时，探针时限应不小于冷却时长")
                .isGreaterThanOrEqualTo(60_000L);
    }
}
