package com.wire.mate.service.dto.notification;

import java.util.List;

/**
 * Paginated envelope for notifications. Keeps the wire contract stable even when
 * the database contains thousands of rows — the client can page through results
 * instead of receiving one unbounded JSON blob that hangs the renderer.
 */
public record NotificationPage(
        List<NotificationResponse> items,
        long total,
        int limit,
        int offset
) {
}
