package com.wire.mate.service.dto.mock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Request body for updating an existing mock. {@code projectId} is intentionally
 * not part of this DTO — moving a mock between projects goes through
 * {@link MoveMockRequest} so the two operations stay distinct.
 */
public record UpdateMockRequest(

        @NotBlank(message = "Mock name is required")
        @Size(max = 255, message = "Mock name must be at most 255 characters")
        String name,

        @Size(max = 1024, message = "Description must be at most 1024 characters")
        String description,

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
}
