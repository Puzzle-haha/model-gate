package com.modelgate.pricing;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成本计算。
 *
 * ============================================================================
 * 单位换算里有个很漂亮的化简，值得记住
 * ============================================================================
 * 单价口径是「元 / 每百万 token」，而我们要存的是「微元」（1 元 = 10^6 微元）。
 *
 *   成本(元)    = tokens / 10^6 × 单价
 *   成本(微元)  = tokens / 10^6 × 单价 × 10^6
 *               = tokens × 单价
 *
 * 也就是**两个 10^6 正好抵消**，直接相乘即可，不用做除法。
 * 这不只是省一次运算 —— 除法是浮点误差的主要来源，能不做就不做。
 *
 * ============================================================================
 */
@Service
public class CostCalculator {

    private final PricingProperties pricing;

    public CostCalculator(PricingProperties pricing) {
        this.pricing = pricing;
    }

    /**
     * 计算一次调用的成本（微元）。
     *
     * @return 成本，单位微元；用量未知时返回 0
     */
    public long costMicros(String model, Integer promptTokens, Integer completionTokens) {
        PricingProperties.Price price = pricing.priceFor(model);

        BigDecimal input = price.getInputPerMillion() == null
                ? BigDecimal.ZERO : price.getInputPerMillion();
        BigDecimal output = price.getOutputPerMillion() == null
                ? BigDecimal.ZERO : price.getOutputPerMillion();

        if (input.signum() == 0 && output.signum() == 0) {
            return 0L;
        }

        BigDecimal cost = input.multiply(BigDecimal.valueOf(nz(promptTokens)))
                .add(output.multiply(BigDecimal.valueOf(nz(completionTokens))));

        // 四舍五入到整数微元。单次误差 < 1 微元（= 百万分之一元），可忽略。
        return cost.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 这个模型是否配了单价。用于在日志/报表里提示"漏配了"。 */
    public boolean hasPricing(String model) {
        PricingProperties.Price p = pricing.priceFor(model);
        BigDecimal in = p.getInputPerMillion();
        BigDecimal out = p.getOutputPerMillion();
        return (in != null && in.signum() > 0) || (out != null && out.signum() > 0);
    }

    public String currency() {
        return pricing.getCurrency();
    }

    /** 微元转成人能读的元，保留 6 位小数。 */
    public static String formatMicros(long micros) {
        return BigDecimal.valueOf(micros, 6).toPlainString();
    }

    private long nz(Integer v) {
        return v == null ? 0L : v;
    }
}
