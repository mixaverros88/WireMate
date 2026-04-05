package com.wire.mate.service.dto.mock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExistsByNameRequest(
        @NotBlank(message = "Mock name is required")
        @Size(max = 255, message = "Mock name must be at most 255 characters")
        String name
) {
}
