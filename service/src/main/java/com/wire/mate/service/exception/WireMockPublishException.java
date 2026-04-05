package com.wire.mate.service.exception;

public class WireMockPublishException extends RuntimeException {

    public WireMockPublishException(String message) {
        super(message);
    }

    public WireMockPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
