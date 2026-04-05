package com.wire.mate.service.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Util {

    public static Map<String, Object> buildMetadata(UUID projectId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("generatedBy", "wiremate");
        metadata.put("projectId", projectId);
        return metadata;
    }

    public static String replaceFirstPath(String url, String newPath) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        String[] parts = url.split("/");

        // parts[0] will be empty because URL starts with "/"
        if (parts.length > 1) {
            parts[1] = newPath;
        }

        return String.join("/", parts);
    }
}
