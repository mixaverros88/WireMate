package com.wire.mate.service.controller;

import com.wire.mate.service.dto.notification.NotificationResponse;
import com.wire.mate.service.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backoffice")
public class BackofficeController {

    /**
     * Hard cap so a client can never force the server to serialize an unbounded list.
     */
    public static final int MAX_PAGE_SIZE = 500;
    public static final int DEFAULT_PAGE_SIZE = 100;

    private final NotificationService service;

    public BackofficeController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/notifications")
    @ResponseStatus(HttpStatus.OK)
    public Page<NotificationResponse> getAllNotifications(
            @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "createdAt") Pageable pageable
    ) {
        Pageable safe = pageable.getPageSize() > MAX_PAGE_SIZE
                ? PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort())
                : pageable;
        return service.getNotifications(safe);
    }

    @DeleteMapping("/notifications/{notificationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNotification(@PathVariable Long notificationId) {
        service.delete(notificationId);
    }

    @DeleteMapping("/notifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAllNotifications() {
        service.deleteAll();
    }

}
