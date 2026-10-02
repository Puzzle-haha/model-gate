package com.modelgate.calllog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 关闭时的排空行为测试。
 *
 * ============================================================================
 * 这个测试守护的是一个真实修过的 bug：**每次重启都静默丢日志**
 * ============================================================================
 * 原实现在 shutdown() 里第一件事就是 `worker.interrupt()`，
 * 而 drainLoop 的循环条件 `while (running || !queue.isEmpty())`
 * 本身就是优雅排空的路径 —— interrupt 会让它卡在 queue.poll() 上
 * 直接抛 InterruptedException 并被 catch 住 break，队列里剩下的一律丢失。
 *
 * 更隐蔽的是：紧接着那个 `while (!queue.isEmpty())` 等待循环，
 * 因为没人再排空了，只是白等满几秒，然后打一条"已停止"的日志。
 * **看起来一切正常，数据已经没了。**
 *
 * ---------------------------------------------------------------------------
 * 实测对照（本测试的断言依据）
 * ---------------------------------------------------------------------------
 * 修复后：shutdown() 之后 worker 继续排空，直到队列为空
 *     [mock] +182ms 第 3 次写库
 *     [test] +198ms 调用 shutdown()
 *     [mock] +214ms 第 4 次 ... 一直到第 10 次
 *     [test] +1016ms shutdown() 返回，written=10、queueSize=0
 *
 * 修复前：中断立刻生效，worker 在随后的 poll() 上抛异常退出
 *     saved=1（10 条里只落了 1 条），且 writeFailures=0 —— 完全无声
 */
class CallLogWriterShutdownTest {

    /** 造一条最小可用的日志记录。 */
    private CallLog sample(int i) {
        return CallLog.record(
                "req-" + i, "tenant", "fake-fast", "localtest", "fake-fast",
                true, null, null, 1, 10, 5, 15, 12L, "CLOSED", false, 100L);
    }

    private CallLogProperties props() {
        CallLogProperties props = new CallLogProperties();
        props.setBatchSize(1);              // 一条一批，让排空过程足够长
        props.setQueueCapacity(100);
        props.setPollTimeoutMs(300);        // 队列空后多久退出（决定测试尾部耗时）
        props.setShutdownTimeoutMs(3000);
        return props;
    }

    @Test
    @DisplayName("★ 关闭时必须把队列排空，不能一上来就 interrupt 工作线程")
    void shutdownDrainsQueueInsteadOfInterrupting() throws Exception {
        AtomicInteger saved = new AtomicInteger();

        CallLogRepository repository = mock(CallLogRepository.class);
        when(repository.saveAll(any())).thenAnswer(inv -> {
            List<CallLog> arg = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) {
                arg.add((CallLog) o);
            }
            saved.addAndGet(arg.size());
            // 模拟写库耗时：让"排空中途"这个状态持续一段可预期的时间，
            // 这样 shutdown() 稳定地落在排空中途，而不是靠 latch 卡时序
            Thread.sleep(30);
            return arg;
        });

        CallLogWriter writer = new CallLogWriter(repository, props());
        final int total = 10;
        for (int i = 0; i < total; i++) {
            writer.submit(sample(i));
        }

        // 让 worker 先处理掉几条，确保 shutdown 发生在排空中途
        Thread.sleep(80);

        writer.shutdown();

        assertThat(saved.get())
                .as("关闭时必须把已入队的 %d 条全部落盘。"
                        + "若 shutdown() 提前 interrupt 了工作线程，"
                        + "它会在随后的 poll() 上抛异常退出，只写掉前面几条 —— "
                        + "而且 writeFailures 保持 0，完全无声。writer 状态=%s",
                        total, writer.stats())
                .isEqualTo(total);
        assertThat(writer.stats().get("queueSize")).isEqualTo(0);
    }

    @Test
    @DisplayName("队列满了丢弃但不阻塞、不抛异常")
    void submitNeverBlocksNorThrows() throws Exception {
        CallLogProperties props = new CallLogProperties();
        props.setBatchSize(1);
        props.setQueueCapacity(1);
        props.setPollTimeoutMs(300);
        props.setShutdownTimeoutMs(300);

        CallLogRepository repository = mock(CallLogRepository.class);
        when(repository.saveAll(any())).thenAnswer(inv -> {
            Thread.sleep(5_000);            // 模拟数据库卡死
            return List.of();
        });

        CallLogWriter writer = new CallLogWriter(repository, props);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 50; i++) {
            writer.submit(sample(i));       // 必须全部立刻返回
        }
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(elapsed)
                .as("业务线程绝不能在日志入队上等待 —— 队列满就直接丢")
                .isLessThan(1000);

        assertThat(writer.stats().get("submitted")).isEqualTo(50L);
        assertThat((Long) writer.stats().get("dropped"))
                .as("队列容量只有 1 且写库卡死，绝大部分应该被丢弃并计数（而不是阻塞）")
                .isGreaterThan(0);

        // 清理：shutdownTimeoutMs=300，不会久等
        writer.shutdown();
    }
}
