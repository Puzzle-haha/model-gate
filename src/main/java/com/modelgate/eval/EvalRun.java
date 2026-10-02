package com.modelgate.eval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 一次评测运行。
 *
 * 为什么要单独记一次"运行"，而不是只存明细：
 *   - 评测的价值在于**对比**（同一个评测集在不同时间的两次运行，
 *     或者同一时间不同模型的对比）。没有"运行"这个维度就没法对比。
 *   - 明细表会很大（任务数 × 模型数），运行表提供索引和汇总。
 *
 * 注意 models 存成逗号分隔的字符串而不是关联表：
 * 评测运行的模型列表是**一次性的快照**（模型配置会变），
 * 建关联表反而会在模型下线后产生悬空引用。这类"历史快照"数据
 * 适合宽表存，不适合规范化。
 */
@Entity
@Table(name = "eval_run", indexes = {
        @Index(name = "idx_eval_run_started", columnList = "started_at")
})
public class EvalRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "models", nullable = false, length = 512)
    private String models;

    @Column(name = "task_count", nullable = false)
    private int taskCount;

    @Column(name = "total_calls", nullable = false)
    private int totalCalls;

    @Column(name = "passed_count", nullable = false)
    private int passedCount;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected EvalRun() {
    }

    public EvalRun(String name, String models, int taskCount, OffsetDateTime startedAt) {
        this.name = name;
        this.models = models;
        this.taskCount = taskCount;
        this.startedAt = startedAt;
        this.status = "RUNNING";
    }

    public void finish(int totalCalls, int passedCount, long elapsedMs, long costMicros) {
        this.totalCalls = totalCalls;
        this.passedCount = passedCount;
        this.elapsedMs = elapsedMs;
        this.costMicros = costMicros;
        this.status = "COMPLETED";
        this.finishedAt = OffsetDateTime.now();
    }

    public void fail() {
        this.status = "FAILED";
        this.finishedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getModels() { return models; }
    public int getTaskCount() { return taskCount; }
    public int getTotalCalls() { return totalCalls; }
    public int getPassedCount() { return passedCount; }
    public long getElapsedMs() { return elapsedMs; }
    public long getCostMicros() { return costMicros; }
    public String getStatus() { return status; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
}
