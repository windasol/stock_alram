package com.example.dart.disclosure.infra;

public class DartException extends RuntimeException {

    public DartException(String message) {
        super(message);
    }

    public DartException(String message, Throwable cause) {
        super(message, cause);
    }
}
