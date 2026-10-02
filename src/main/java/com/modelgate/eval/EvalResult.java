package com.modelgate.eval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 一条评测明细：某个模型在某条任务上的表现。
 *
 * 刻意把 output 和 expected 都存下来：
 *   - 只存"通过与否"的话，看到 60% 准确率你也不知道错在哪
 *   - 存了原文才能复盘："哦，它把 145+267 算成了 402" 或者
 *     "它答对了但多写了一句解释，被精确匹配判错"
 *
 * 后一种情况尤其重要 —— 它说明**问题出在提示词而不是模型能力**。
 * 没有原文，你永远发现不了这一点。
 */
@Entity
@Table(name = "eval_result", indexes = {
        @Index(name = "idx_eval_result_run", columnList = "run_id"),
        @Index(name = "idx_eval_result_model", columnList = "run_id, model")
})
public class EvalResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "task_type", nullable = false, length = 32)
    private String taskType;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "expected_answer", length = 256)
    private String expectedAnswer;

    @Column(name = "actual_output", length = 512)
    private String actualOutput;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "error_type", length = 48)
    private String errorType;

    protected EvalResult() {
    }

    public EvalResult(Long runId, String taskId, String taskType, String model, String provider,
                      String expectedAnswer, String actualOutput, boolean passed,
                      long elapsedMs, long costMicros, Integer totalTokens, String errorType) {
        this.runId = runId;
        this.taskId = taskId;
        this.taskType = taskType;
        this.model = model;
        this.provider = provider;
        this.expectedAnswer = truncate(expectedAnswer, 256);
        this.actualOutput = truncate(actualOutput, 512);
        this.passed = passed;
        this.elapsedMs = elapsedMs;
        this.costMicros = costMicros;
        this.totalTokens = totalTokens;
        this.errorType = truncate(errorType, 48);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public String getTaskId() { return taskId; }
    public String getTaskType() { return taskType; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public String getExpectedAnswer() { return expectedAnswer; }
    public String getActualOutput() { return actualOutput; }
    public boolean isPassed() { return passed; }
    public long getElapsedMs() { return elapsedMs; }
    public long getCostMicros() { return costMicros; }
    public Integer getTotalTokens() { return totalTokens; }
    public String getErrorType() { return errorType; }
}
