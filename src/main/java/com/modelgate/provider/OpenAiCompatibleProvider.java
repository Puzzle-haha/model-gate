package com.modelgate.provider;

import com.modelgate.resilience.LlmCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 OpenAI 兼容供应商适配器。
 *
 * 一个类覆盖一大片上游：DeepSeek、通义（兼容模式）、智谱、Moonshot、
 * 各类 OneAPI/NewAPI 中转站、本地 vLLM / Ollama / LM Studio ——
 * 它们讲的是同一套协议，差别只在 base_url、密钥和模型名。
 *
 * 只有协议确实不同的（Anthropic Messages API、Gemini 原生接口），
 * 才值得另写一个实现。
 *
 * 本类【必须线程安全】：容错层会从多个线程并发调用它。
 * 所以字段全部 final，RestClient 与 ApiKeyPool 本身都是线程安全的。
 */
public class OpenAiCompatibleProvider implements Provider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private static final int MAX_ERROR_BODY = 300;

    private final ProviderProperties.Definition config;
    private final RestClient restClient;
    private final ApiKeyPool keyPool;

    public OpenAiCompatibleProvider(ProviderProperties.Definition config,
                                    RestClient.Builder builder,
                                    ApiKeyPool keyPool) {
        this.config = config;
        this.keyPool = keyPool;
        this.restClient = builder
                .baseUrl(config.getBaseUrl())
                // 不在客户端设置超时：超时由上层的容错层统一控制。
                // 如果这里也设一个，就会出现两层超时互相打架，
                // 出问题时你根本不知道是哪一层先触发的。
                .build();
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public List<String> models() {
        return List.copyOf(config.getModels());
    }

    @Override
    public Long defaultTimeoutMs() {
        return config.getTimeoutMs();
    }

    @Override
    public int priority() {
        return config.getPriority();
    }

    public ApiKeyPool keyPool() {
        return keyPool;
    }

    @Override
    public ProviderResponse chat(ProviderRequest request) throws LlmCallException {
        // 每次调用都重新取一把密钥：冷却中的密钥自然会被跳过，
        // 于是"重试"就自动变成了"换一把凭证再试"。
        String key = keyPool.acquire();
        if (key == null) {
            // ================================================================
            // 标记为【不可重试】—— 这里曾经是 true，是个实测出来的浪费。
            //
            // 冷却时长是分钟级（鉴权失败 600s、限流 30s），而重试退避只有
            // 几百毫秒（215ms + 538ms）。**在退避窗口内密钥不可能恢复**，
            // 所以 3 次尝试注定拿到同样的结果。
            //
            // 实测对照（详见 tools/localtest.yml 的 localtest-allbad）：
            //   retryable=true  → attempts=3，耗时 758ms，退避两次
            //   retryable=false → attempts=1，耗时 ~50ms
            // 每个请求白白多等 700ms，熔断器还多记了 3 倍失败。
            //
            // 注意：标记为不可重试【不影响故障转移】——
            // GatewayService 拿到失败结果后照样会切到下一个候选供应商。
            // 这个标记只控制"对同一家要不要再来几次"。
            // ================================================================
            throw new LlmCallException(
                    "供应商 " + config.getName() + " 的所有密钥都在冷却中（全部失效或被限流），"
                            + "重试无法改变（冷却时长以分钟计，退避只有几百毫秒）",
                    false, "all_keys_cooling");
        }

        Map<String, Object> body = buildRequestBody(request);

        try {
            UpstreamChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(UpstreamChatResponse.class);

            keyPool.reportSuccess(key);
            return toProviderResponse(response, request.model());

        } catch (HttpStatusCodeException e) {
            throw handleHttpError(e, key);

        } catch (ResourceAccessException e) {
            // 连不上、读超时、连接被重置。这类【可重试】——
            // 网络抖动重试一次往往就好了。跟密钥无关，不冷却它。
            throw new LlmCallException(
                    "连接 " + config.getName() + " 失败: " + abbreviate(e.getMessage()),
                    true, "connection_error");

        } catch (RestClientException e) {
            // 其余协议层问题（响应无法解析等）
            throw new LlmCallException(
                    "调用 " + config.getName() + " 异常: " + abbreviate(e.getMessage()),
                    true, "upstream_protocol_error");
        }
    }

    /**
     * 把 HTTP 状态码翻译成「能不能重试」，并在需要时冷却密钥。
     *
     * 这个映射是整个网关里最需要想清楚的一段：
     *
     *   400 / 404              → 【不可重试】。请求本身有问题，换谁、试几次都一样。
     *   401 / 403              → 密钥问题。**如果池里还有别的健康密钥，就可重试**
     *                            （重试会自动换一把凭证）；只有一把时不可重试。
     *                            这个判断是密钥池带来的新维度。
     *   429                    → 密钥被限流。冷却这把、可重试（下次会用别的密钥）。
     *   5xx                    → 上游整体问题。可重试，但【不】冷却密钥 ——
     *                            否则会把所有密钥一起误伤。
     */
    private LlmCallException handleHttpError(HttpStatusCodeException e, String key) {
        int status = e.getStatusCode().value();
        String type = classify(status);
        boolean retryable = isRetryableStatus(status);

        boolean keyRelated = "upstream_401".equals(type) || "upstream_403".equals(type)
                || "rate_limited".equals(type);
        if (keyRelated) {
            keyPool.reportFailure(key, type);
        }

        boolean authError = "upstream_401".equals(type) || "upstream_403".equals(type);
        if (authError) {
            // 关键判断：还有别的健康密钥才值得重试
            boolean another = keyPool.hasHealthyKeyOtherThan(key);
            retryable = another;
            if (!another) {
                log.warn("供应商 {} 密钥 {} 鉴权失败，且池中没有其他健康密钥，放弃重试",
                        config.getName(), ApiKeyPool.mask(key));
            }
        }

        String bodyText = abbreviate(e.getResponseBodyAsString());
        String message = "上游 " + config.getName() + " 返回 " + status
                + "（密钥 " + ApiKeyPool.mask(key) + "）"
                + (bodyText.isEmpty() ? "" : ": " + bodyText);

        log.warn("{}(retryable={})", message, retryable);
        return new LlmCallException(message, retryable, type);
    }

    private String classify(int status) {
        if (status == 429) {
            return "rate_limited";
        }
        if (status >= 500) {
            return "upstream_5xx";
        }
        return "upstream_" + status;
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    private Map<String, Object> buildRequestBody(ProviderRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());

        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage m : request.messages()) {
            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            msg.put("content", m.content());
            messages.add(msg);
        }
        body.put("messages", messages);

        // 只透传客户端显式给过的参数。
        // 不要填默认值 —— 各家的默认值不同，网关强加默认值会改变客户端预期的行为。
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        body.put("stream", false);
        return body;
    }

    private ProviderResponse toProviderResponse(UpstreamChatResponse response, String requestedModel) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return new ProviderResponse("", response == null ? requestedModel : response.model(),
                    null, null, "empty");
        }

        UpstreamChatResponse.Choice first = response.choices().get(0);
        String content = (first.message() == null) ? "" : first.message().content();

        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.usage() != null) {
            promptTokens = response.usage().promptTokens();
            completionTokens = response.usage().completionTokens();
        }

        return new ProviderResponse(
                content,
                response.model() != null ? response.model() : requestedModel,
                promptTokens,
                completionTokens,
                first.finishReason());
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= MAX_ERROR_BODY ? t : t.substring(0, MAX_ERROR_BODY) + "…";
    }
}
