package com.modelgate.openai;

import com.modelgate.openai.dto.OpenAiError;
import com.modelgate.provider.UnknownModelException;
import com.modelgate.ratelimit.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 专门服务 /v1/** 的错误处理 —— 返回 OpenAI 形状的错误。
 *
 * 为什么需要它：项目里已经有一个全局的 GlobalExceptionHandler，
 * 它返回的是内部接口用的 {timestamp, status, error, message} 结构。
 * 但 OpenAI 客户端 SDK 只认 {error:{message,type,code}} ——
 * 格式不对，SDK 会抛一个语焉不详的解析异常，用户看不到真正原因。
 *
 * 两个 @ControllerAdvice 同时存在时，Spring 按 @Order 决定先问谁。
 * 这里用 HIGHEST_PRECEDENCE + basePackages 限定作用域：
 *   - /v1/**   的请求 → 先匹配到这里（只覆盖 openai 包的控制器）
 *   - 其他请求        → 这里不匹配，落到全局的 GlobalExceptionHandler
 *
 * 这是"同一个应用里为不同 API 表面提供不同错误契约"的标准做法。
 */
@RestControllerAdvice(basePackages = "com.modelgate.openai")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpenAiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OpenAiExceptionHandler.class);

    /** 模型不存在 —— OpenAI 用 404 + model_not_found。 */
    @ExceptionHandler(UnknownModelException.class)
    public ResponseEntity<OpenAiError> handleUnknownModel(UnknownModelException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(OpenAiError.of(ex.getMessage(), "invalid_request_error", "model_not_found"));
    }

    /** 缺 key —— OpenAI 用 401 + invalid_api_key。 */
    @ExceptionHandler(MissingApiKeyException.class)
    public ResponseEntity<OpenAiError> handleMissingKey(MissingApiKeyException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(OpenAiError.of(ex.getMessage(), "invalid_request_error", "invalid_api_key"));
    }

    /** 调用了不支持的能力（如 stream=true）。 */
    @ExceptionHandler(UnsupportedFeatureException.class)
    public ResponseEntity<OpenAiError> handleUnsupported(UnsupportedFeatureException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(OpenAiError.of(ex.getMessage(), "invalid_request_error", "unsupported_feature"));
    }

    /**
     * 限流 / 配额超限 —— HTTP 429。
     *
     * 两个细节很重要：
     *   1. 带 Retry-After 头。没有它，客户端只能盲目重试，
     *      在限流场景下盲目重试等于火上浇油。这是 HTTP 规范里
     *      专门为 429/503 定义的语义。
     *   2. error.type 用具体限制类型（requests / daily_tokens），
     *      而不是笼统的 "rate_limit"。客户端据此能区分
     *      「我发太快了，等一会儿就好」和「我今天额度用完了，等也没用」——
     *      这两种情况的正确处理方式完全不同。
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<OpenAiError> handleRateLimited(RateLimitedException ex) {
        long retryAfterMs = ex.retryAfterMs();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);

        if (retryAfterMs > 0) {
            // HTTP 规范里 Retry-After 是【秒】，不是毫秒。
            // 向上取整，避免算出 0 导致客户端立刻重试。
            long seconds = Math.max(1, (retryAfterMs + 999) / 1000);
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        }
        // 非标准但很有用的补充头，让客户端不必解析整数秒就能拿到精确值
        builder.header("X-ModelGate-Retry-After-Ms", String.valueOf(Math.max(0, retryAfterMs)));
        builder.header("X-ModelGate-Limit-Type", ex.limitType());

        return builder.body(OpenAiError.of(ex.getMessage(), ex.limitType(), ex.code()));
    }

    /** 参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<OpenAiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> (e.getField() + ": " + (e.getDefaultMessage() == null ? "参数不合法" : e.getDefaultMessage())))
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(OpenAiError.of(message, "invalid_request_error", "invalid_parameter"));
    }

    /** 请求体不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OpenAiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(OpenAiError.of("请求体格式不正确，应为合法 JSON", "invalid_request_error", "invalid_json"));
    }

    /**
     * 兜底。
     *
     * 注意这里【没有】放行 404 —— 原因和 GlobalExceptionHandler 不同：
     * 这个 advice 的作用域是本包的控制器，未匹配路径的 NoResourceFoundException
     * 不经过这里（它由全局 advice 处理）。所以这里接到的都是真的服务端问题。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenAiError> handleUnexpected(Exception ex) {
        log.error("网关未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(OpenAiError.of("网关内部错误，请稍后重试", "server_error", "internal_error"));
    }
}
