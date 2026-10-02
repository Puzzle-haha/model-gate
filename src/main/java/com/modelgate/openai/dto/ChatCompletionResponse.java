package com.modelgate.openai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 兼容的对话补全响应。
 *
 * 字段名和结构必须和 OpenAI 严格一致，否则客户端 SDK 解析会失败 ——
 * 这是"兼容"的全部意义所在：让现有客户端把 base_url 指过来就能用，
 * 一行代码都不用改。
 */
public record ChatCompletionResponse(

        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage

) {

    public record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    public record Message(String role, String content) {
    }

    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }
}
