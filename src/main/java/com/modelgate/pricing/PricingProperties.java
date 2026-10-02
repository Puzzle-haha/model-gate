package com.modelgate.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型单价配置。
 *
 * 单位：币种单位 / 每百万 token。这是各家官方定价的通用口径，
 * 直接照抄官方页面即可，不需要自己换算。
 *
 * 为什么单价用 BigDecimal 而不是 double：
 *   单价是人为设定的十进制数（2.0、0.15、4.5），用 double 存会引入
 *   表示误差（比如 0.15 存成 0.1499999999999999944…）。
 *   虽然单次误差极小，但乘上百万级 token 再累加，就会在报表里显形。
 *   配置值用 BigDecimal，最终落库前一次性转成整数微元。
 */
@Component
@ConfigurationProperties(prefix = "modelgate.pricing")
public class PricingProperties {

    /** 币种标识，只用于展示。 */
    private String currency = "CNY";

    /**
     * 没有单独配置单价的模型走这一档。
     *
     * 默认 0 表示"不计费" —— 刻意不设一个拍脑袋的默认价，
     * 因为"算错的钱"比"没算的钱"更危险：前者会让报表看起来有数据，
     * 实际全是错的。宁可显示 0，让你发现漏配了。
     */
    private Price defaultPrice = new Price();

    /** 模型名 -> 单价。 */
    private Map<String, Price> models = new LinkedHashMap<>();

    public Price priceFor(String model) {
        Price p = models.get(model);
        return p != null ? p : defaultPrice;
    }

    public static class Price {
        /** 输入（prompt）token 单价，每百万 token。 */
        private BigDecimal inputPerMillion = BigDecimal.ZERO;

        /** 输出（completion）token 单价，每百万 token。 */
        private BigDecimal outputPerMillion = BigDecimal.ZERO;

        public BigDecimal getInputPerMillion() { return inputPerMillion; }
        public void setInputPerMillion(BigDecimal inputPerMillion) { this.inputPerMillion = inputPerMillion; }

        public BigDecimal getOutputPerMillion() { return outputPerMillion; }
        public void setOutputPerMillion(BigDecimal outputPerMillion) { this.outputPerMillion = outputPerMillion; }
    }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Price getDefaultPrice() { return defaultPrice; }
    public void setDefaultPrice(Price defaultPrice) { this.defaultPrice = defaultPrice; }

    public Map<String, Price> getModels() { return models; }
    public void setModels(Map<String, Price> models) { this.models = models; }
}
