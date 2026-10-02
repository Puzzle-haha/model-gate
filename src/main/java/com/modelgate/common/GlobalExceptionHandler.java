package com.modelgate.common;

import com.modelgate.ratelimit.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常处理。
 *
 * 目的：把所有错误收敛成同一个响应形状 {timestamp, status, error, message}，
 * 前端只需认一种结构，而不是为每种失败场景写一套解析。
 *
 * ============================================================================
 * ⚠️ 这个类是本项目最有价值的一课，因为它是【踩了两次同一个坑】之后才写对的。
 *
 * 最初的版本只有一个兜底：
 *
 *     @ExceptionHandler(Exception.class)
 *     public ... handleUnexpected(Exception ex) { return 500; }
 *
 * 看起来很稳妥——"什么都接住，绝不会漏"。实际结果是：
 *
 *   1. 请求 /api/this-route-does-not-exist
 *      → Spring 抛 NoResourceFoundException（本该 404）
 *      → 被兜底接住，改写成 500
 *
 *   2. 请求体是坏 JSON
 *      → Spring 抛 HttpMessageNotReadableException（本该 400）
 *      → 被兜底接住，改写成 500
 *
 * 危害不只是状态码不好看：
 *   - 客户端 bug 被记成服务端故障，监控告警天天响
 *   - 日志里堆满"未预期异常"，真正的线上故障被淹没
 *   - 前端无法区分"我传错了"和"服务器炸了"，重试策略会做错
 *     （4xx 不该重试，5xx 才该重试——这个区别决定了要不要做重试）
 *
 * 结论：**兜底异常处理器必须为框架已经表达清楚语义的异常显式放行。**
 * 下面每一条都是明确列举的，不靠"抓到什么算什么"。
 *
 * ----------------------------------------------------------------------------
 * 关于 @Order(LOWEST_PRECEDENCE)：
 * 本项目里还有另一个 advice —— OpenAiExceptionHandler，专门给 /v1/** 返回
 * OpenAI 形状的错误。它标了 HIGHEST_PRECEDENCE 并用 basePackages 限定作用域。
 *
 * Spring 处理异常时会按顺序询问各个 advice，谁先匹配上就用谁。
 * 所以：/v1/** 先被 OpenAI 那个接住；其他请求到不了它（作用域不匹配），
 * 落到这里。把这个标成 LOWEST_PRECEDENCE 是为了让顺序明确、不依赖默认值。
 * ============================================================================
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------- 400 客户端请求有问题

    /** 参数校验失败（@Valid 触发）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage() == null ? e.getField() : e.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /** 请求体不是合法 JSON，或类型对不上（比如把 "abc" 传给 Integer 字段）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        // 注意：不要把 ex.getMessage() 原样返回给客户端，
        // 它会暴露你的实体类名和字段结构（信息泄露）。
        log.warn("请求体解析失败: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "请求体格式不正确，应为合法 JSON");
    }

    /** 路径变量或查询参数类型不匹配。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "参数 " + ex.getName() + " 的类型不正确");
    }

    // ---------------------------------------------------------------- 404 找不到

    /**
     * 请求路径不存在。
     *
     * Spring 6.1 起，未匹配到任何 handler 的请求会抛 NoResourceFoundException
     * （它继承 ServletException，所以会被 Exception 兜底接住——这就是当初那个 bug）。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
        return build(HttpStatus.NOT_FOUND, "请求的路径不存在");
    }

    // ---------------------------------------------------------------- 405 方法不对

    /** 用 GET 请求了一个只接受 POST 的接口。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "该路径不支持 " + ex.getMethod() + " 方法");
    }

    // ---------------------------------------------------------------- 429 限流与配额

    /**
     * 限流 / 配额超限。
     *
     * 注意它和 OpenAI 那套错误契约的差别：这里返回的是项目内部的
     * {timestamp,status,error,message} 结构，而 /v1/** 返回 OpenAI 的
     * {error:{message,type,code}} 结构。**同一个异常，两种外形。**
     * 这正是"不同 API 表面各自维护错误契约"的体现 —— 两边共用一个 Handler
     * 反而会让某一方的客户端解析失败。
     *
     * 共同点是：都返回 429，都带 Retry-After。
     * 状态码和关键头必须一致（这是 HTTP 语义层面的约定），
     * 响应体的形状可以按客户端期望来定（这是应用层面的约定）。
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(RateLimitedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", "Too Many Requests");
        body.put("message", ex.getMessage());
        body.put("code", ex.code());
        body.put("limitType", ex.limitType());
        body.put("remaining", ex.remaining());

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (ex.retryAfterMs() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER,
                    String.valueOf(Math.max(1, (ex.retryAfterMs() + 999) / 1000)));
        }
        return builder.body(body);
    }

    // ---------------------------------------------------------------- 500 真的是服务端的问题

    /**
     * 兜底：走到这里说明是【我们自己的代码】没处理好的异常。
     *
     * 到这一步返回 500 才是正确的——因为前面所有"客户端错"和"框架语义"都已显式放行。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        // 对外只给一句笼统的话，不暴露堆栈（堆栈可能含表名、SQL、内网地址）
        // 对内必须打全，否则线上出事无从查起
        log.error("未预期的异常", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
