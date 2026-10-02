package com.modelgate.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "modelgate.gateway")
public class GatewayProperties {

    /**
     * 客户端请求 {@code model: "auto"} 时实际使用哪个模型。
     *
     * M1 阶段是个固定值；M2 会换成真正的路由策略
     * （按成本、健康度、任务类型选）。
     */
    private String defaultModel = "mock-ok";

    /**
     * 是否强制校验 API key。
     *
     * M1 阶段默认关闭（还没有密钥管理），M3 会接入密钥池并打开它。
     */
    private boolean requireApiKey = false;

    /**
     * 故障转移深度：一次请求最多尝试几个供应商。
     *
     * ⚠️ 这个值要和容错层的 maxAttempts 一起看，因为两者是【相乘】的：
     *
     *     最多上游调用次数 = failoverDepth × maxAttempts
     *
     * 默认 2 × 3 = 6 次。如果不注意这一点，把两个都调大，
     * 一次客户端请求可能变成几十次上游调用 —— 这就是"重试放大"，
     * 在故障期间足以把整个上游打垮。
     *
     * 所以两个参数都必须有明确上限，且启动时会打印这个乘积供你检查。
     */
    private int failoverDepth = 2;

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public boolean isRequireApiKey() { return requireApiKey; }
    public void setRequireApiKey(boolean requireApiKey) { this.requireApiKey = requireApiKey; }

    public int getFailoverDepth() { return failoverDepth; }
    public void setFailoverDepth(int failoverDepth) { this.failoverDepth = failoverDepth; }
}
