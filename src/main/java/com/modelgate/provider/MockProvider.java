package com.modelgate.provider;

import com.modelgate.resilience.LlmCallException;
import com.modelgate.resilience.ResilienceProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 可注入故障的模拟供应商。
 *
 * 它不联网、不花钱、不依赖 API key，而且【故障 100% 可复现】。
 * 用真实 API 是做不了这件事的：你没法命令它"这次给我超时"。
 *
 * 两个用途：
 *   1. 压测：真实 API 按量计费，压测会烧钱；Mock 可以无限打
 *   2. 容错验证：能精确制造超时/5xx/空响应，逐条验证容错逻辑
 *
 * 模型名约定：{@code mock-<模式小写>}，例如 mock-ok、mock-slow、mock-hang。
 * 这样"制造故障"和"选择模型"是同一件事，不需要额外的调试开关。
 */
@Component
public class MockProvider implements Provider {

    public static final String PREFIX = "mock-";

    /** HANG 模式睡多久。远大于任何超时阈值，效果等同于"永不返回"。 */
    private static final long HANG_MS = 60_000L;

    private final ResilienceProperties props;

    public MockProvider(ResilienceProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "mock";
    }

    /**
     * 优先级最低 —— 模拟供应商永远排在真实供应商后面，
     * 避免它抢占真实流量的位置。
     */
    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public List<String> models() {
        return Arrays.stream(FaultMode.values())
                .map(m -> PREFIX + m.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    @Override
    public ProviderResponse chat(ProviderRequest request) throws LlmCallException {
        FaultMode requested = parseMode(request.model());
        FaultMode mode = requested == FaultMode.RANDOM ? randomMode() : requested;

        String prompt = lastUserContent(request);

        String answer;
        switch (mode) {
            case SLOW:
                sleep(props.getMockSlowMs());
                answer = "这是一条慢响应，用于验证超时与重试。";
                break;

            case HANG:
                // 调用方有超时，所以实际会被放弃；
                // 但如果容错层忘记 cancel，这个线程会一直占着池子里的位置
                sleep(HANG_MS);
                answer = "永远到不了这里";
                break;

            case ERROR:
                sleep(50);
                throw new LlmCallException("上游返回 503 Service Unavailable", true, "upstream_5xx");

            case AUTH:
                sleep(50);
                // 标记为不可重试：key 错了重试一万次结果都一样
                throw new LlmCallException("上游返回 401 Unauthorized：API key 无效", false, "upstream_401");

            case GARBAGE:
                sleep(50);
                answer = "抱歉，我无法回答这个问题。今天天气不错。";
                break;

            case TRUNCATED:
                sleep(50);
                // 真实世界极常见：输出达到 max_tokens 上限，JSON 就断在半截
                answer = "{\"answer\":\"这是一段被截断的回复，注意它没有闭合";
                break;

            case EMPTY:
                sleep(50);
                // 空内容一律当失败处理并重试 —— 返回空字符串给用户没有任何意义
                throw new LlmCallException("上游返回了空内容", true, "empty_content");

            default:
                sleep(30);
                answer = "这是一条来自 MockProvider 的正常回复。你问的是：「" + prompt + "」";
                break;
        }

        int promptTokens = estimateTokens(prompt);
        int completionTokens = estimateTokens(answer);
        return new ProviderResponse(answer, request.model(), promptTokens, completionTokens, "stop");
    }

    /** 把 mock-slow 解析成 FaultMode.SLOW；认不出来就当作 OK。 */
    private FaultMode parseMode(String model) {
        if (model == null || !model.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return FaultMode.OK;
        }
        String suffix = model.substring(PREFIX.length()).toUpperCase(Locale.ROOT);
        for (FaultMode m : FaultMode.values()) {
            if (m.name().equals(suffix)) {
                return m;
            }
        }
        return FaultMode.OK;
    }

    /** RANDOM 模式：偏向正常，更接近真实世界（大部分调用是成功的）。 */
    private FaultMode randomMode() {
        FaultMode[] candidates = {
                FaultMode.OK, FaultMode.OK, FaultMode.OK,
                FaultMode.SLOW, FaultMode.ERROR, FaultMode.EMPTY,
        };
        return candidates[ThreadLocalRandom.current().nextInt(candidates.length)];
    }

    private String lastUserContent(ProviderRequest request) {
        return request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((first, second) -> second)
                .map(ChatMessage::content)
                .orElse("");
    }

    /**
     * 粗略估算 token 数。
     *
     * 真实实现会用 tiktoken 之类的分词器。这里用"字符数 / 4"这种粗糙近似，
     * 只为了在 Mock 场景下给出一个数量级正确的数字，
     * 让成本核算的代码路径能被验证。
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private void sleep(long ms) throws LlmCallException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 正确做法：恢复中断标记再抛出，否则中断信号会被吞掉
            Thread.currentThread().interrupt();
            throw new LlmCallException("调用被中断", false, "interrupted");
        }
    }
}
