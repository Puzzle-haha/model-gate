package com.modelgate.provider;

/**
 * 上游返回的统一结果。
 *
 * token 计数刻意用 Integer 而不是 int：不是所有供应商都会返回用量，
 * 用 null 明确表示"上游没给"，而不是伪造一个 0。
 * 成本核算时必须能区分"用量为 0"和"用量未知"。
 */
public record ProviderResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        String finishReason) {

    public Integer totalTokens() {
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
    }
}
