package com.example.chat.common;

import java.util.Map;

public final class ApiResponse {

    private ApiResponse() {}

    public static <T> Map<String, Object> ok(T data) {
        return Map.of("ok", true, "data", data);
    }

    public static Map<String, Object> ok() {
        return Map.of("ok", true);
    }

    public static Map<String, Object> error(String message) {
        return Map.of("ok", false, "error", message);
    }

    public static Map<String, Object> error(int code, String message) {
        return Map.of("ok", false, "code", code, "error", message);
    }
}
