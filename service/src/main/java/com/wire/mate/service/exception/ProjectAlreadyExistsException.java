package com.wire.mate.service.exception;

public class ProjectAlreadyExistsException extends RuntimeException {

    public ProjectAlreadyExistsException(String projectName) {
        super("Project already exists: " + projectName);
    }
}
