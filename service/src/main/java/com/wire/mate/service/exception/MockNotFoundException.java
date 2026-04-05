package com.wire.mate.service.exception;

import java.util.UUID;

public class MockNotFoundException extends RuntimeException {

    public MockNotFoundException(UUID mockId) {
        super("Mock not found: " + mockId);
    }
}
