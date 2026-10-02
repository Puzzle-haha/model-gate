-- ============================================================================
-- V2 · 响应缓存命中标记 + 成本核算
-- ============================================================================
-- 新增两列：
--   cache_hit   本次是否命中缓存（命中则没有上游调用，成本为 0）
--   cost_micros 本次调用的成本，单位【微元】（1 元 = 1,000,000 微元）
--
-- 为什么成本用整数存而不是 DOUBLE：
--   浮点数无法精确表示 0.1 这类十进制小数。单价 × token 数会产生大量
--   无限小数，累加几百万次之后的误差足以让报表和实际账单对不上。
--   金额要么用整数最小单位，要么用 DECIMAL。这里选 BIGINT，
--   因为数据库的 SUM() 对整数聚合又快又精确。
--
-- 类型说明：cache_hit 用 BIT 而不是 TINYINT(1)。
--   Hibernate 6 的 MySQL 方言把 boolean 映射成 BIT，
--   而 ddl-auto: validate 会逐列比对类型名 —— 写成 TINYINT 会直接启动失败。
--   这个类型同样是用 schema-generation 生成 DDL 之后照着写的，不是猜的。
--
-- 索引：新增 (tenant, created_at)，用于"按调用方查成本报表"。
--   已有的 (model, created_at) 覆盖"按模型统计"，(provider, created_at) 覆盖"按供应商"。
-- ============================================================================

ALTER TABLE call_log
    ADD COLUMN cache_hit   BIT    NOT NULL DEFAULT 0,
    ADD COLUMN cost_micros BIGINT NOT NULL DEFAULT 0;

-- 按调用方 + 时间聚合（成本报表、配额对账）
CREATE INDEX idx_call_log_tenant_created
    ON call_log (tenant, created_at);
