package com.wire.mate.service.dto.project;

import jakarta.validation.constraints.NotBlank;

public record CloneProjectRequest(
        @NotBlank(message = "New project name is required")
        String name
) {
}
