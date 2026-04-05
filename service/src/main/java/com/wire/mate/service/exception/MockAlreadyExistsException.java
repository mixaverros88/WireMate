package com.wire.mate.service.exception;

public class MockAlreadyExistsException extends RuntimeException {

    public MockAlreadyExistsException(String mockName, String projectName) {
        super("Mock '" + mockName + "' already exists in project '" + projectName + "'");
    }
}
