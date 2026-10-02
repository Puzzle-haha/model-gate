package com.modelgate.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本计算测试。
 *
 * 为什么值得单测：**金额算错是静默的。** 不会抛异常、不会打日志，
 * 只会让报表上的数字悄悄偏掉。没有断言，你可能几个月都发现不了，
 * 而一旦被面试官或财务问起来，就是信任问题。
 *
 * 核心要验证的换算：
 *   单价口径是「元 / 每百万 token」，目标是「微元」（1 元 = 10^6 微元）
 *   成本(元)   = tokens / 10^6 × 单价
 *   成本(微元) = tokens / 10^6 × 单价 × 10^6 = tokens × 单价
 *   两个 10^6 正好抵消 —— 所以直接相乘，不做除法（除法是精度损失的主要来源）
 */
class CostCalculatorTest {

    private CostCalculator calculator(BigDecimal input, BigDecimal output) {
        PricingProperties props = new PricingProperties();
        PricingProperties.Price price = new PricingProperties.Price();
        price.setInputPerMillion(input);
        price.setOutputPerMillion(output);
        props.setDefaultPrice(price);
        return new CostCalculator(props);
    }

    @Test
    @DisplayName("换算公式：微元 = prompt × 输入单价 + completion × 输出单价")
    void formulaIsDirectMultiplication() {
        CostCalculator calc = calculator(new BigDecimal("2.0"), new BigDecimal("8.0"));

        // 1000 个输入 token、500 个输出 token
        // 1000 × 2.0 + 500 × 8.0 = 2000 + 4000 = 6000 微元 = 0.006 元
        long micros = calc.costMicros("m", 1000, 500);

        assertThat(micros).isEqualTo(6000L);
        assertThat(CostCalculator.formatMicros(micros)).isEqualTo("0.006000");
    }

    @Test
    @DisplayName("单次调用的真实量级：11 输入 + 7 输出 = 78 微元")
    void smallCallIsAccurate() {
        CostCalculator calc = calculator(new BigDecimal("2.0"), new BigDecimal("8.0"));

        // 这正是压测里假上游返回的用量，用来对齐 M4 的实测数据
        assertThat(calc.costMicros("m", 11, 7)).isEqualTo(78L);
    }

    @Test
    @DisplayName("用量为 null 时按 0 计，不抛异常")
    void nullTokensTreatedAsZero() {
        CostCalculator calc = calculator(new BigDecimal("2.0"), new BigDecimal("8.0"));

        // 降级/失败时拿不到用量，这是正常路径，不能抛异常
        assertThat(calc.costMicros("m", null, null)).isZero();
        assertThat(calc.costMicros("m", null, 10)).isEqualTo(80L);
        assertThat(calc.costMicros("m", 10, null)).isEqualTo(20L);
    }

    @Test
    @DisplayName("没配单价的模型成本为 0，并可通过 hasPricing 识别")
    void unpricedModelCostsZero() {
        CostCalculator calc = calculator(BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(calc.costMicros("unknown", 1000, 1000)).isZero();
        assertThat(calc.hasPricing("unknown")).isFalse();
    }

    @Test
    @DisplayName("小数单价不引入精度损失")
    void fractionalPriceIsExact() {
        CostCalculator calc = calculator(new BigDecimal("0.5"), new BigDecimal("2.0"));

        // 1000 × 0.5 + 1000 × 2.0 = 500 + 2000 = 2500 微元
        assertThat(calc.costMicros("m", 1000, 1000)).isEqualTo(2500L);
    }

    @Test
    @DisplayName("大量调用累加不产生误差（整数运算的意义）")
    void accumulationHasNoDrift() {
        CostCalculator calc = calculator(new BigDecimal("0.15"), new BigDecimal("0.6"));

        long single = calc.costMicros("m", 11, 7);
        long total = 0;
        for (int i = 0; i < 100_000; i++) {
            total += calc.costMicros("m", 11, 7);
        }

        // 整数运算下，累加 10 万次必须精确等于单次 × 10 万
        assertThat(total).isEqualTo(single * 100_000);
    }

    @Test
    @DisplayName("每个模型可以有不同的单价")
    void perModelPricing() {
        PricingProperties props = new PricingProperties();

        PricingProperties.Price cheap = new PricingProperties.Price();
        cheap.setInputPerMillion(new BigDecimal("0.5"));
        cheap.setOutputPerMillion(new BigDecimal("2.0"));

        PricingProperties.Price expensive = new PricingProperties.Price();
        expensive.setInputPerMillion(new BigDecimal("8.0"));
        expensive.setOutputPerMillion(new BigDecimal("32.0"));

        Map<String, PricingProperties.Price> models = new LinkedHashMap<>();
        models.put("cheap", cheap);
        models.put("expensive", expensive);
        props.setModels(models);

        CostCalculator calc = new CostCalculator(props);

        long cheapCost = calc.costMicros("cheap", 1000, 1000);
        long expensiveCost = calc.costMicros("expensive", 1000, 1000);

        assertThat(cheapCost).isEqualTo(2500L);
        assertThat(expensiveCost).isEqualTo(40_000L);
        // 贵 16 倍 —— 这正是评测报告里"每个正确答案成本"要揭示的差异
        assertThat(expensiveCost / cheapCost).isEqualTo(16);
    }
}
