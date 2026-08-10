package com.example.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖：参数校验失败、请求体解析失败、缺少必填参数、参数类型错误、权限不足、未捕获异常兜底
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("参数校验失败返回 400 并包含字段错误信息")
    void handleValidation_returns400_withFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "test");
        bindingResult.addError(new FieldError("test", "username", "不能为空"));
        bindingResult.addError(new FieldError("test", "email", "格式不正确"));
        MethodArgumentNotValidException ex = createValidationException(bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("message").toString().contains("username"));
        assertTrue(response.getBody().get("message").toString().contains("email"));
    }

    @Test
    @DisplayName("参数校验失败响应体包含 timestamp 和 status")
    void handleValidation_responseContainsTimestamp() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "test");
        bindingResult.addError(new FieldError("test", "field", "error"));
        MethodArgumentNotValidException ex = createValidationException(bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        assertNotNull(response.getBody().get("timestamp"));
        assertEquals(400, response.getBody().get("status"));
    }

    @Test
    @DisplayName("请求体解析失败返回 400")
    void handleBadBody_returns400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON 解析错误", new Exception());

        ResponseEntity<Map<String, Object>> response = handler.handleBadBody(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("请求体格式错误", response.getBody().get("message"));
    }

    @Test
    @DisplayName("缺少必填参数返回 400 并包含参数名")
    void handleMissingParam_returns400_withParamName() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("userId", "Long");

        ResponseEntity<Map<String, Object>> response = handler.handleMissingParam(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("userId"));
    }

    @Test
    @DisplayName("参数类型错误返回 400")
    void handleTypeMismatch_returns400() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "id", null, new NumberFormatException());

        ResponseEntity<Map<String, Object>> response = handler.handleTypeMismatch(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("id"));
    }

    @Test
    @DisplayName("权限不足返回 403")
    void handleAccessDenied_returns403() {
        AccessDeniedException ex = new AccessDeniedException("无权访问");

        ResponseEntity<Map<String, Object>> response = handler.handleAccessDenied(ex);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("权限不足", response.getBody().get("message"));
    }

    @Test
    @DisplayName("未捕获异常返回 500")
    void handleGeneral_returns500() {
        Exception ex = new RuntimeException("未知错误");

        ResponseEntity<Map<String, Object>> response = handler.handleGeneral(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("服务器内部错误", response.getBody().get("message"));
        assertEquals(500, response.getBody().get("status"));
    }

    @Test
    @DisplayName("所有异常响应包含 timestamp、status、error、message 四个字段")
    void allResponses_haveStandardStructure() {
        Exception ex = new RuntimeException("test");
        ResponseEntity<Map<String, Object>> response = handler.handleGeneral(ex);

        Map<String, Object> body = response.getBody();
        assertNotNull(body.get("timestamp"));
        assertNotNull(body.get("status"));
        assertNotNull(body.get("error"));
        assertNotNull(body.get("message"));
        assertEquals(4, body.size());
    }

    /**
     * 使用反射避开 MethodArgumentNotValidException 构造函数二义性
     */
    private MethodArgumentNotValidException createValidationException(BeanPropertyBindingResult bindingResult) {
        // 找 Executable 类型的构造函数
        for (Constructor<?> ctor : MethodArgumentNotValidException.class.getDeclaredConstructors()) {
            Type[] paramTypes = ctor.getGenericParameterTypes();
            if (paramTypes.length == 2) {
                ctor.setAccessible(true);
                try {
                    return (MethodArgumentNotValidException) ctor.newInstance(null, bindingResult);
                } catch (Exception ignored) {
                    // try next
                }
            }
        }
        throw new RuntimeException("无法创建 MethodArgumentNotValidException");
    }
}
