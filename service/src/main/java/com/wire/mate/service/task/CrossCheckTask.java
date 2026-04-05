package com.wire.mate.service.task;

import com.wire.mate.service.entity.Notification;
import com.wire.mate.service.gateway.WireMockGateway;
import com.wire.mate.service.repository.MockRepository;
import com.wire.mate.service.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "app.cross-check", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrossCheckTask {

    private static final Logger log = LoggerFactory.getLogger(CrossCheckTask.class);

    private final MockRepository mockRepository;
    private final WireMockGateway wireMockGateway;
    private final NotificationRepository notificationRepository;

    public CrossCheckTask(
            MockRepository mockRepository,
            WireMockGateway wireMockGateway,
            NotificationRepository notificationRepository
    ) {
        this.mockRepository = mockRepository;
        this.wireMockGateway = wireMockGateway;
        this.notificationRepository = notificationRepository;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${app.cross-check.interval}').toMillis()}")
    public void execute() {
        Set<String> wireMockStubs = wireMockGateway.getAllMappingIds();
        Set<String> databaseStubs = loadDatabaseStubIds();
        recordDrift(databaseStubs, wireMockStubs);
    }

    @Transactional(readOnly = true)
    protected Set<String> loadDatabaseStubIds() {
        return mockRepository
                .findAllIds()
                .stream()
                .map(UUID::toString)
                .collect(Collectors.toSet());
    }

    @Transactional
    protected void recordDrift(Set<String> databaseStubs, Set<String> wireMockStubs) {
        Set<String> missingFromWireMock = new HashSet<>(databaseStubs);
        missingFromWireMock.removeAll(wireMockStubs);

        Set<String> missingFromDb = new HashSet<>(wireMockStubs);
        missingFromDb.removeAll(databaseStubs);

        if (missingFromWireMock.isEmpty() && missingFromDb.isEmpty()) {
            return;
        }

        List<Notification> batch = new ArrayList<>(missingFromWireMock.size() + missingFromDb.size());

        missingFromWireMock.stream()
                .map(id -> new Notification(
                        "Mock " + id + " is in DB but not in WireMock, \n <a href='/mock/" + id + "'>WireMate</>")
                )
                .forEach(batch::add);

        missingFromDb.stream()
                .map(id -> new Notification(
                        "Mock " + id + " is in WireMock but not in DB, \n <a href='/stubs/" + id + "'>WireMate</>")
                )
                .forEach(batch::add);

        notificationRepository.saveAll(batch);
        log.info("Cross-check produced {} drift notifications ({} DB-only, {} WireMock-only).", batch.size(), missingFromWireMock.size(), missingFromDb.size());
    }
}
