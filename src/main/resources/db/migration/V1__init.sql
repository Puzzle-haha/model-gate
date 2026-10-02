-- ============================================================================
-- V1 · 调用日志表
-- ============================================================================
-- ModelGate 的数据地基。后面几乎每个里程碑都要读它：
--   M4 成本核算 → 按 token / 模型聚合
--   M5 可观测性 → P95 延迟、错误率、降级率
--   M6 评测回归 → 按评测批次对比模型质量
--
-- ⚠️ 这份 DDL 是【照着 Hibernate 生成的建表语句写的】，不是手拍脑袋定的。
--    做法：临时用
--      --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create
--    让 Hibernate 把它期望的 DDL 输出成文件，再据此写迁移脚本。
--
--    为什么这么做：`ddl-auto: validate` 会逐列比对实体和表结构，
--    类型/长度对不上会直接启动失败。手写很容易在 varchar 长度、
--    datetime 精度这些细节上翻车，而报错信息又不够直白。
--
--    先用工具生成、再人工整理，是这个场景下最省时间的路径。
--
-- 迁移文件的命名规则：V<版本号>__<描述>.sql（两个下划线）
-- 一旦提交并被应用过，就【绝对不要改】—— 校验和对不上会导致启动失败。
-- 需要变更就新增 V2、V3，永远向前走。
-- ============================================================================

CREATE TABLE call_log
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,

    -- 网关生成的请求 id，用于把一次调用的所有日志串起来
    request_id        VARCHAR(64)  NOT NULL,

    -- 调用方标识。M3 接入密钥池后由 API key 映射而来
    tenant            VARCHAR(64)  NULL,

    -- 客户端请求的模型名，可能是 "auto"
    requested_model   VARCHAR(64)  NULL,

    provider          VARCHAR(32)  NOT NULL,
    model             VARCHAR(128) NOT NULL,

    -- SUCCESS / DEGRADED
    status            VARCHAR(16)  NOT NULL,

    -- 错误分类：timeout / upstream_5xx / circuit_open / bulkhead_rejected ...
    error_type        VARCHAR(48)  NULL,
    error_message     VARCHAR(512) NULL,

    -- 实际尝试次数（含首次）
    attempts          INT          NOT NULL,

    -- 刻意可空：不是所有上游都返回用量。
    -- 用 NULL 表示"上游没给"，而不是伪造 0 ——
    -- 成本核算必须能区分"用量为 0"和"用量未知"。
    prompt_tokens     INT          NULL,
    completion_tokens INT          NULL,
    total_tokens      INT          NULL,

    -- 容错层耗时（含重试与退避）
    elapsed_ms        BIGINT       NOT NULL,

    -- 调用结束时该供应商的熔断器状态
    breaker_state     VARCHAR(16)  NULL,

    -- 精度到微秒。用 datetime 而不是 timestamp：
    -- timestamp 在 MySQL 上有 2038 年上限，且受时区转换影响。
    created_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- 索引。三个都是照着"实际会怎么查"设计的，不是为了好看：
-- ----------------------------------------------------------------------------

-- 1. 按时间倒序翻最近的调用（运维排查、Dashboard 首页）
CREATE INDEX idx_call_log_created_at
    ON call_log (created_at);

-- 2. 按供应商 + 时间聚合（"DeepSeek 过去一小时的成功率是多少"）
--    复合索引的列顺序有讲究：等值条件在前，范围条件在后。
--    反过来写成 (created_at, provider) 就用不上 provider 的等值过滤了。
CREATE INDEX idx_call_log_provider_created
    ON call_log (provider, created_at);

-- 3. 按模型 + 时间聚合（成本报表、模型对比）
CREATE INDEX idx_call_log_model_created
    ON call_log (model, created_at);
