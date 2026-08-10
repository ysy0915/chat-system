package com.example.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- 业务无关的框架级异常 ----

    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbort(org.apache.catalina.connector.ClientAbortException ex, HttpServletResponse response) {
        log.debug("[ClientAbort] 客户端提前断开连接: {}", ex.getMessage());
    }

    /** 参数校验失败 (jakarta.validation) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.info("[Validation] 参数校验失败: {}", errors);
        return buildResponse(HttpStatus.BAD_REQUEST, "参数错误: " + errors);
    }

    /** 请求体解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBadBody(HttpMessageNotReadableException ex) {
        log.info("[BadRequest] 请求体解析失败: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    /** 缺少必填参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.info("[BadRequest] 缺少参数: {}", ex.getParameterName());
        return buildResponse(HttpStatus.BAD_REQUEST, "缺少必填参数: " + ex.getParameterName());
    }

    /** 参数类型错误 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.info("[BadRequest] 参数类型错误: {}={}", ex.getName(), ex.getValue());
        return buildResponse(HttpStatus.BAD_REQUEST, "参数 " + ex.getName() + " 类型不正确");
    }

    /** 权限不足 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("[AccessDenied] 权限不足: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "权限不足");
    }

    // ---- 兜底 ----

    /** 未捕获的异常统一返回 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("[UnhandledException] 未捕获异常: {} ({})", ex.getMessage(), ex.getClass().getSimpleName(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
