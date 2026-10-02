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
     * 关闭时尽力把队列写完，但不无限等待。
     *
     * 为什么设上限：如果数据库已经不可用，无限等待会让进程永远关不掉，
     * 只能被 kill -9 —— 那反而会丢掉更多数据。
     */
    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
        long deadline = System.currentTimeMillis() + 5000;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            sleepQuietly(100);
        }
        log.info("调用日志写入器已停止: 已提交={} 已写入={} 已丢弃={} 写失败={}",
                submitted.get(), written.get(), dropped.get(), writeFailures.get());
    }
}
