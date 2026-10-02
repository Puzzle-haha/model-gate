package com.modelgate.calllog;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调用日志的异步写入器。
 *
 * 为什么不直接同步 insert：
 *   网关的核心指标是延迟。每次调用插一条数据库记录，会额外增加 1–5ms，
 *   而这个开销没有任何业务价值 —— 用户不关心你的日志什么时候落盘。
 *   更要命的是：数据库抖动或写入变慢时，同步写会把上游的延迟直接放大到
 *   客户端身上，日志这种"附属品"反而成了故障源。
 *
 * 设计要点：
 *   1. 有界队列。无界队列在数据库变慢时会吃光内存，
 *      把"日志写不动"升级成"整个进程 OOM"，故障等级反而升高。
 *   2. 非阻塞入队（offer）。队列满就丢弃并计数，**绝不阻塞业务线程**。
 *      丢日志是可以接受的，拖慢或拖挂请求是不可接受的。
 *   3. 批量落库。攒一批再写，比逐条插入快一个数量级。
 *   4. 关闭时尽力刷新，但不无限等待。
 *
 * 这是一个典型的取舍：**在"数据完整性"和"服务可用性"之间，日志选了后者。**
 * 面试被问"日志丢失怎么办"，答案不是"保证不丢"，而是"先保证不拖垮主链路，
 * 再用别的通道补偿"（比如同时打一份到 stdout，交给采集器）。
 */
@Service
public class CallLogWriter {

    private static final Logger log = LoggerFactory.getLogger(CallLogWriter.class);

    private final CallLogRepository repository;
    private final CallLogProperties props;

    private final BlockingQueue<CallLog> queue;
    private final Thread worker;
    private volatile boolean running = true;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();

    public CallLogWriter(CallLogRepository repository, CallLogProperties props) {
        this.repository = repository;
        this.props = props;
        this.queue = new ArrayBlockingQueue<>(props.getQueueCapacity());
        this.worker = new Thread(this::drainLoop, "call-log-writer");
        this.worker.setDaemon(true);
        this.worker.start();
        log.info("调用日志异步写入器已启动: 队列容量={} 批量={}", props.getQueueCapacity(), props.getBatchSize());
    }

    /**
     * 提交一条日志。**永不阻塞、永不抛异常。**
     *
     * 这个方法会被放在请求主链路上，所以它的失败必须被完全吞掉 ——
     * 记录一次调用失败的原因，不应该让这次调用本身失败。
     */
    public void submit(CallLog callLog) {
        if (!props.isEnabled() || callLog == null) {
            return;
        }
        submitted.incrementAndGet();
        // offer 是非阻塞的：队列满立刻返回 false，业务线程不会在这里等待
        if (!queue.offer(callLog)) {
            long total = dropped.incrementAndGet();
            // 每丢 1000 条提醒一次，避免刷屏
            if (total % 1000 == 1) {
                log.warn("调用日志队列已满，累计丢弃 {} 条（数据库可能变慢或写入阻塞）", total);
            }
        }
    }

    private void drainLoop() {
        List<CallLog> batch = new ArrayList<>(props.getBatchSize());
        while (running || !queue.isEmpty()) {
            try {
                CallLog first = queue.poll(props.getPollTimeoutMs(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                // 把队列里已有的尽量一起取走，形成一批
                queue.drainTo(batch, props.getBatchSize() - 1);

                repository.saveAll(batch);
                written.addAndGet(batch.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 写库失败不能杀死这个线程，否则之后所有日志都丢了
                writeFailures.incrementAndGet();
                log.warn("批量写入调用日志失败（{} 条）: {}", batch.size(), e.toString());
                sleepQuietly(1000);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 写入器自身的状态，用于观测"日志通道是否健康"。 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.isEnabled());
        m.put("queueSize", queue.size());
        m.put("queueCapacity", props.getQueueCapacity());
        m.put("submitted", submitted.get());
        m.put("written", written.get());
        m.put("dropped", dropped.get());
        m.put("writeFailures", writeFailures.get());
        return m;
    }

    /**
     * 关闭时尽力把队列写完，但不超过 shutdownTimeoutMs。
     *
     * ============================================================================
     * ⚠️ 这里【绝不能一上来就 interrupt 工作线程】—— 这是一个真实修过的 bug
     * ============================================================================
     * 最初的实现是这样的：
     *
     *     running = false;
     *     worker.interrupt();                    // ← 问题在这
     *     while (!queue.isEmpty() && ...) sleep(100);
     *
     * 看起来是在"催促"它，实际效果完全相反：
     *
     *   drainLoop 的循环条件 `while (running || !queue.isEmpty())`
     *   **本身就是优雅排空的路径** —— running=false 之后，它会继续把
     *   队列里剩下的写完再退出。
     *
     *   但 interrupt() 会让它卡在 `queue.poll(timeout)` 上立刻抛
     *   InterruptedException，被 catch 住后直接 break。
     *   **于是队列里剩下的日志全部丢失。**
     *
     * 更糟的是后面那个等待循环：工作线程已经死了，没人再排空队列，
     * 那个 while 只是白等满 5 秒，然后打一条"已停止"的日志，
     * 看起来一切正常。
     *
     * 所以正确顺序是：
     *   1. 只置 running=false，让它自然排空（可能是毫秒级）
     *   2. join 等待，给一个上限
     *   3. **只有真的超时了**才 interrupt 作为最后手段 ——
     *      那时才说明下游（数据库）确实卡死了，丢数据已成定局
     *
     * 教训：**当一个结构已经有优雅退出路径时，"催促"它往往是破坏它。**
     * 中断是"立刻停下"的语义，不是"快点做完"的语义。
     */
    @PreDestroy
    public void shutdown() {
        running = false;

        try {
            // 等待工作线程自然排空。它会在队列为空后自行退出。
            worker.join(props.getShutdownTimeoutMs());

            if (worker.isAlive()) {
                // 到这一步说明排空确实卡住了（比如数据库不可用）。
                // 这时才强制中断 —— 丢数据已成定局，但当务之急是别让进程关不掉。
                worker.interrupt();
                log.warn("调用日志写入器未能在 {}ms 内排空，已强制中断；"
                                + "队列中剩余 {} 条可能丢失",
                        props.getShutdownTimeoutMs(), queue.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.interrupt();
        }

        long lost = queue.size();
        log.info("调用日志写入器已停止: 已提交={} 已写入={} 已丢弃={} 写失败={}{}",
                submitted.get(), written.get(), dropped.get(), writeFailures.get(),
                lost > 0 ? " 关闭时未落盘=" + lost : "");
    }
}
