package com.modelgate.openai;

import com.modelgate.gateway.GatewayProperties;
import com.modelgate.gateway.GatewayService;
import com.modelgate.openai.dto.ChatCompletionRequest;
import com.modelgate.openai.dto.ChatCompletionResponse;
import com.modelgate.provider.ChatMessage;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

/**
 * OpenAI 兼容入口。
 *
 * 这个 Controller 的职责边界很窄：**只做 HTTP 协议的翻译**。
 *   - 把 OpenAI 的请求结构转成网关内部的统一模型
 *   - 把网关结果转回 OpenAI 的响应结构
 * 路由、容错、日志这些全部在 GatewayService 里，不在这里。
 *
 * 保持这个边界，后面加 Anthropic 兼容入口时，只需要再写一个这样的翻译层。
 */
@RestController
@RequestMapping("/v1")
public class OpenAiController {

    private final GatewayService gateway;
    private final GatewayProperties props;

    public OpenAiController(GatewayService gateway, GatewayProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @PostMapping("/chat/completions")
    public ChatCompletionResponse chatCompletions(
            @RequestBody @Valid ChatCompletionRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletResponse servletResponse) {

        // M1 只把 key 取出来备用（M3 会用它做鉴权、配额和成本归属）
        String apiKey = extractApiKey(authorization);
        if (props.isRequireApiKey() && apiKey == null) {
            throw new MissingApiKeyException();
        }

        // 明确拒绝而不是静默降级：如果客户端要流式、我们却返回完整 JSON，
        // 它的解析逻辑会挂在一个更难懂的地方。宁可现在就说清楚。
        if (Boolean.TRUE.equals(request.stream())) {
            throw new UnsupportedFeatureException(
                    "暂不支持 stream=true（流式输出）。请改用 stream=false，或在后续版本中使用。");
        }

        List<ChatMessage> messages = request.messages().stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();

        GatewayService.GatewayResult result = gateway.chat(
                request.model(), messages, request.temperature(), request.maxTokens(), apiKey);

        // ------------------------------------------------------------------
        // 把网关的内部决策通过响应头暴露出去。
        //
        // 为什么值得做：客户端（尤其是排查问题时）需要知道"这次是谁服务的"。
        // 没有这几个头，你只能去翻网关日志，而客户端那个 requestId 在日志里
        // 根本对不上号。真实的网关产品（OpenRouter、Cloudflare AI Gateway 等）
        // 都会返回类似的元信息头。
        //
        // 用 X- 前缀是历史惯例（RFC 6648 建议不要用，但生态里已经
        // 固化下来了，强行改用无前缀反而让人困惑）。
        // ------------------------------------------------------------------
        servletResponse.setHeader("X-ModelGate-Request-Id", result.requestId());
        servletResponse.setHeader("X-ModelGate-Provider", result.provider());
        servletResponse.setHeader("X-ModelGate-Model", result.model());
        servletResponse.setIntHeader("X-ModelGate-Providers-Tried", result.providersTried());
        servletResponse.setIntHeader("X-ModelGate-Upstream-Attempts", result.invocation().attempts());
        servletResponse.setHeader("X-ModelGate-Degraded", String.valueOf(result.invocation().degraded()));

        // 降级时也要返回 200 —— 客户端拿到的是"可用但不完美"的结果，
        // 而不是一个异常。这正是降级的意义：不让上游故障穿透到调用方。
        String content = result.invocation().content();

        int promptTokens = 0;
        int completionTokens = 0;
        if (result.invocation().response() != null) {
            promptTokens = nz(result.invocation().response().promptTokens());
            completionTokens = nz(result.invocation().response().completionTokens());
        }

        return new ChatCompletionResponse(
                result.requestId(),
                "chat.completion",
                System.currentTimeMillis() / 1000L,
                result.model(),
                List.of(new ChatCompletionResponse.Choice(
                        0,
                        new ChatCompletionResponse.Message("assistant", content),
                        result.invocation().success() ? "stop" : "degraded")),
                new ChatCompletionResponse.Usage(promptTokens, completionTokens,
                        promptTokens + completionTokens));
    }

    private String extractApiKey(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        return authorization.regionMatches(true, 0, prefix, 0, prefix.length())
                ? authorization.substring(prefix.length()).trim()
                : authorization.trim();
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
