package com.example.chat.config;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.exception.LLMCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理（统一输出 ApiResponse 风格错误体：
 * <code>{"ok": false, "code": <HTTP状态码>, "error": "..."}</code>）。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- 业务无关的框架级异常 ----

    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbort(org.apache.catalina.connector.ClientAbortException ex, jakarta.servlet.http.HttpServletResponse response) {
        log.debug("[ClientAbort] 客户端提前断开连接: {}", ex.getMessage());
    }

    /** 参数校验失败 (jakarta.validation) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.info("[Validation] 参数校验失败: {}", errors);
        return buildResponse(ErrorCode.BAD_REQUEST, "参数错误: " + errors);
    }

    /** 请求体解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBadBody(HttpMessageNotReadableException ex) {
        log.info("[BadRequest] 请求体解析失败: {}", ex.getMessage());
        return buildResponse(ErrorCode.BAD_REQUEST, "请求体格式错误");
    }

    /** 缺少必填参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.info("[BadRequest] 缺少参数: {}", ex.getParameterName());
        return buildResponse(ErrorCode.BAD_REQUEST, "缺少必填参数: " + ex.getParameterName());
    }

    /** 参数类型错误 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.info("[BadRequest] 参数类型错误: {}={}", ex.getName(), ex.getValue());
        return buildResponse(ErrorCode.BAD_REQUEST, "参数 " + ex.getName() + " 类型不正确");
    }

    /** 权限不足 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("[AccessDenied] 权限不足: {}", ex.getMessage());
        return buildResponse(ErrorCode.FORBIDDEN, "权限不足");
    }

    /** LLM 调用失败：区分「上游模型 4xx/5xx」与「网络/解析异常」，返回更清晰的语义 */
    @ExceptionHandler(LLMCallException.class)
    public ResponseEntity<Map<String, Object>> handleLlmCall(LLMCallException ex) {
        int status = ex.getHttpStatus();
        if (status >= 400 && status < 500) {
            // 上游模型返回 4xx（如 401 鉴权失败、429 限流、400 参数错误）——问题在请求侧，属可重试/可修复
            log.warn("[LLMCall] 上游模型 {} 返回 {}: {}", ex.getModel(), status, ex.getMessage());
            return buildResponse(ErrorCode.BAD_REQUEST, "AI 服务暂时不可用（上游返回 " + status + "），请稍后重试");
        }
        // 5xx / 网络异常 / 超时 / 解析失败——问题在模型服务侧，属暂时性故障
        log.error("[LLMCall] 模型 {} 调用失败: {}", ex.getModel(), ex.getMessage());
        return buildResponse(ErrorCode.INTERNAL_ERROR, "AI 服务繁忙或暂时不可用，请稍后重试");
    }

    // ---- 兜底 ----

    /** 未捕获的异常统一返回 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("[UnhandledException] 未捕获异常: {} ({})", ex.getMessage(), ex.getClass().getSimpleName(), ex);
        return buildResponse(ErrorCode.INTERNAL_ERROR, "服务器内部错误");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(ErrorCode code, String message) {
        return ResponseEntity.status(code.getCode()).body(ApiResponse.error(code, message));
    }
}
