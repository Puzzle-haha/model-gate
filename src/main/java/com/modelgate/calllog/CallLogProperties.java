package com.modelgate.calllog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "modelgate.calllog")
public class CallLogProperties {

    private boolean enabled = true;

    /**
     * 待写入队列的容量。
     *
     * 必须有界：无界队列在数据库变慢时会吃光内存，
     * 把"日志写不动"升级成"整个进程 OOM"。
     */
    private int queueCapacity = 10000;

    /** 单批最多写多少条。批量插入比逐条快一个数量级。 */
    private int batchSize = 200;

    /** 队列空时的等待时间，避免空转烧 CPU。 */
    private long pollTimeoutMs = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public long getPollTimeoutMs() { return pollTimeoutMs; }
    public void setPollTimeoutMs(long pollTimeoutMs) { this.pollTimeoutMs = pollTimeoutMs; }
}
