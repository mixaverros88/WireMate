package com.wire.mate.service.dto.mock;

import com.wire.mate.service.entity.Mock;
import com.wire.mate.service.entity.Project;
import com.wire.mate.service.util.Util;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateMockRequest(

        @NotBlank(message = "Mock name is required")
        @Size(max = 255, message = "Mock name must be at most 255 characters")
        String name,

        @Size(max = 1024, message = "Description must be at most 1024 characters")
        String description,

        @NotNull(message = "Project ID is required")
        UUID projectId,

        @NotNull(message = "Request definition is required")
        Map<String, Object> request,

        @NotNull(message = "Response definition is required")
        Map<String, Object> response,

        boolean persistent,
        int priority,
        Map<String, Object> metadata,
        List<Map<String, Object>> serveEventListeners,
        String curl
) {

    public Mock toEntity(Project project) {
        return new Mock(
                this.name,
                this.description,
                project,
                this.request,
                this.response,
                this.persistent,
                this.priority,
                Util.buildMetadata(project.getId()),
                this.serveEventListeners,
                this.curl
        );
    }
}
