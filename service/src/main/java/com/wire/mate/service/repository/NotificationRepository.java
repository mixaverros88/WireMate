package com.wire.mate.service.repository;

import com.wire.mate.service.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Notifications are pure read/append; ordering is supplied by the caller via
 * {@link org.springframework.data.domain.Pageable}, so no derived query
 * methods are needed.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
