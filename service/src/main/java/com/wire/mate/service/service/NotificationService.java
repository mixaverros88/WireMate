package com.wire.mate.service.service;

import com.wire.mate.service.dto.notification.NotificationResponse;
import com.wire.mate.service.entity.Notification;
import com.wire.mate.service.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist a new notification — used by the cross-check task to record drift.
     * Kept as its own method (not exposed via controller) so the call site stays
     * inside a single transaction and is easy to mock in tests.
     */
    @Transactional
    public Notification record(String message) {
        return repository.save(new Notification(message));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(Pageable pageable) {
        return repository.findAll(pageable).map(Notification::toDto);
    }
}
