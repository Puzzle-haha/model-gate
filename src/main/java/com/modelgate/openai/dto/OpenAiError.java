package com.modelgate.openai.dto;

/**
 * OpenAI 风格的错误响应。
 *
 * 为什么要单独一套，而不是复用内部接口的错误结构：
 * 客户端 SDK 是按 OpenAI 的格式来解析错误的（读 error.message / error.type），
 * 格式不对它就抛一个语焉不详的解析异常，用户根本看不到真正的原因。
 *
 * 教训：**不同的 API 表面需要各自的错误契约。**
 * 对外兼容 OpenAI 的接口，错误也必须是 OpenAI 的形状。
 */
public record OpenAiError(ErrorBody error) {

    public record ErrorBody(String message, String type, String param, String code) {
    }

    public static OpenAiError of(String message, String type, String code) {
        return new OpenAiError(new ErrorBody(message, type, null, code));
    }
}
