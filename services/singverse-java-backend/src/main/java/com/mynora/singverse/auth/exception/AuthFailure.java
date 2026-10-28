package com.mynora.singverse.auth.exception;

public class AuthFailure extends RuntimeException {
    public final int status;
    public final int retryAfter;

    public AuthFailure(int status, String message) {
        this(status, message, 0);
    }

    public AuthFailure(int status, String message, int retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }
}
