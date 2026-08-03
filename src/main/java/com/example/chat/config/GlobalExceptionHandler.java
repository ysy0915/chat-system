package com.example.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import jakarta.servlet.http.HttpServletResponse;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbort(org.apache.catalina.connector.ClientAbortException ex, HttpServletResponse response) {
        log.debug("[ClientAbort] 客户端提前断开连接: {}", ex.getMessage());
    }
}
