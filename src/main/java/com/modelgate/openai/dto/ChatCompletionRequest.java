package com.modelgate.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * OpenAI 兼容的对话补全请求。
 *
 * 两个刻意的设计：
 *
 * 1. {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 *    客户端（各家 SDK）会带很多可选字段：top_p、frequency_penalty、tools、stream_options…
 *    网关不能因为见到不认识的字段就 400。协议兼容的第一原则是【宽容接收】。
 *    真正需要透传的字段，等有需求再加。
 *
 * 2. 字段名用 {@code @JsonProperty} 显式绑定 snake_case
 *    不依赖全局命名策略 —— 全局策略一改，所有接口都会被影响。
 *    局部显式绑定，改动范围可见。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionRequest(

        /** 模型名。"auto" 表示由网关按策略决定用哪家。 */
        String model,

        @NotEmpty(message = "messages 不能为空")
        List<Message> messages,

        Double temperature,

        @JsonProperty("max_tokens")
        Integer maxTokens,

        /** M1 暂不支持流式，收到 true 会明确报错，而不是静默降级成非流式。 */
        Boolean stream

) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }
}
