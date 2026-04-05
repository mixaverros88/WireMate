package com.wire.mate.service.gateway.dto;

import java.util.Map;

public record ResponseDto(
        Integer status,
        String statusMessage,
        String body,
        Map<String, String> headers,
        Map<String, Object> jsonBody
) {}