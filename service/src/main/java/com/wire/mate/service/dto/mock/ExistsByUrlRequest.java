package com.wire.mate.service.dto.mock;

import jakarta.validation.constraints.NotBlank;

public record ExistsByUrlRequest(
        @NotBlank(message = "Mock URL is required")
        String url
) {
}
