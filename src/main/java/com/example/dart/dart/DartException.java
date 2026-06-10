package com.example.dart.dart;

public class DartException extends RuntimeException {

    public DartException(String message) {
        super(message);
    }

    public DartException(String message, Throwable cause) {
        super(message, cause);
    }
}
