package com.modelgate.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 上游 OpenAI 兼容接口的响应结构。
 *
 * 单独定义而不是复用对外的 ChatCompletionResponse，原因是这两个会各自演进：
 *   - 对外响应受"必须兼容 OpenAI"约束，字段不能乱动
 *   - 上游响应要容忍各家的小差异（有的不返回 usage，有的多返回字段）
 *
 * 共用一个类会让两边的演进互相牵制 —— 这是典型的"不要把对外契约和
 * 上游契约合并"的场景。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstreamChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Integer index,
            Message message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
