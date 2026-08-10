package com.example.chat.exception;

public class CalculationException extends RuntimeException {
    public CalculationException(String message) {
        super(message);
    }
    public CalculationException(String message, Throwable cause) {
        super(message, cause);
    }
}
