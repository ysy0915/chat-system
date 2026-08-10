package com.example.chat.exception;

public class MediaGenException extends RuntimeException {
    public MediaGenException(String message) {
        super(message);
    }
    public MediaGenException(String message, Throwable cause) {
        super(message, cause);
    }
}
