-- ============================================================================
-- V3 · 评测集与模型质量回归
-- ============================================================================
-- 两张表，回答两个不同层次的问题：
--
--   eval_run    —— "跑过几次评测、用的哪批模型、总体成绩如何"
--   eval_result —— "具体哪道题、哪个模型、答成了什么"
--
-- 为什么要拆开（而不是只存明细）：
--   评测的价值在于**对比** —— 同一个评测集在不同时间的两次运行、
--   或者同一时间不同模型的横向对比。没有"运行"这个维度就没法对比，
--   只能对着一堆明细发呆。
--
-- 为什么 eval_run.models 存逗号分隔的字符串而不是关联表：
--   模型列表是**一次性的历史快照**。模型配置会变、模型会下线，
--   建关联表反而会在模型消失后产生悬空引用。
--   这类"历史快照"适合宽表存，不适合规范化。
--
-- ⚠️ 表结构照例是用 Hibernate 的 schema-generation 生成 DDL 后整理而来，
--    不是手写的 —— ddl-auto: validate 会逐列比对类型名，
--    passed 列必须是 BIT 而不是 TINYINT(1)，写错直接启动失败。
-- ============================================================================

CREATE TABLE eval_run
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(128) NOT NULL,
    -- 逗号分隔的模型列表（历史快照，见文件头说明）
    models       VARCHAR(512) NOT NULL,
    task_count   INT          NOT NULL,
    total_calls  INT          NOT NULL,
    passed_count INT          NOT NULL,
    elapsed_ms   BIGINT       NOT NULL,
    cost_micros  BIGINT       NOT NULL,
    -- RUNNING / COMPLETED / FAILED
    status       VARCHAR(16)  NOT NULL,
    started_at   DATETIME(6)  NOT NULL,
    finished_at  DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE eval_result
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    run_id          BIGINT       NOT NULL,
    task_id         VARCHAR(64)  NOT NULL,
    -- 算术 / 情感 …，用于按类型分组统计
    task_type       VARCHAR(32)  NOT NULL,
    model           VARCHAR(128) NOT NULL,
    provider        VARCHAR(32)  NULL,
    -- 期望答案与实际输出都存下来，便于复盘
    -- （只存"通过与否"的话，看到 60% 准确率你也不知道错在哪）
    expected_answer VARCHAR(256) NULL,
    actual_output   VARCHAR(512) NULL,
    passed          BIT          NOT NULL,
    elapsed_ms      BIGINT       NOT NULL,
    cost_micros     BIGINT       NOT NULL,
    total_tokens    INT          NULL,
    error_type      VARCHAR(48)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 历史运行列表（按时间倒序）
CREATE INDEX idx_eval_run_started
    ON eval_run (started_at);

-- 按 run 取明细
CREATE INDEX idx_eval_result_run
    ON eval_result (run_id);

-- 按 run + 模型聚合（报告的主查询）
CREATE INDEX idx_eval_result_model
    ON eval_result (run_id, model);
