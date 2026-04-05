package com.wire.mate.service.task;

import com.wire.mate.service.config.WireMateProperties;
import com.wire.mate.service.entity.Notification;
import com.wire.mate.service.exception.WireMockPublishException;
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

/**
 * Periodically reconciles the set of stubs known to WireMate (DB) with the set
 * known to WireMock (admin API).
 *
 * <p>Two kinds of drift are detected and converted into {@link Notification}
 * rows:</p>
 * <ul>
 *   <li><b>DB → WireMock gap</b> — present in DB, missing from WireMock (e.g.
 *       WireMock was restarted, or a stub was deleted directly).</li>
 *   <li><b>WireMock → DB gap</b> — present in WireMock, missing from DB (e.g.
 *       a stub was registered against WireMock bypassing WireMate).</li>
 * </ul>
 *
 * <p>Implementation notes:</p>
 * <ul>
 *   <li>Only IDs are loaded from the database — the heavy {@code request_definition}
 *       and {@code response_definition} JSONB columns are <em>not</em> fetched.</li>
 *   <li>The HTTP call to WireMock happens outside any transaction, so the DB
 *       connection is not held while waiting on the network.</li>
 *   <li>Notifications are inserted in a batch via {@code saveAll} so a large
 *       drift produces a single round-trip rather than N.</li>
 *   <li>The whole task is wrapped in a try/catch — a failure to reach WireMock
 *       must not prevent the next tick from running.</li>
 *   <li>Disabled by setting {@code app.cross-check.enabled=false} (e.g. in
 *       integration tests where there is no WireMock).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.cross-check", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrossCheckTask {

    private static final Logger log = LoggerFactory.getLogger(CrossCheckTask.class);

    private final MockRepository mockRepository;
    private final WireMockGateway wireMockGateway;
    private final NotificationRepository notificationRepository;

    public CrossCheckTask(MockRepository mockRepository,
                          WireMockGateway wireMockGateway,
                          NotificationRepository notificationRepository) {
        this.mockRepository = mockRepository;
        this.wireMockGateway = wireMockGateway;
        this.notificationRepository = notificationRepository;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${app.cross-check.interval}').toMillis()}")
    public void execute() {
        Set<String> wireMockStubs;
        try {
            wireMockStubs = wireMockGateway.getAllMappingIds();
        } catch (WireMockPublishException ex) {
            // WireMock unreachable — log once and back off until the next tick.
            // Do NOT propagate: the @Scheduled executor would suppress repeats.
            log.warn("Cross-check skipped: cannot reach WireMock ({})", ex.getMessage());
            return;
        }

        Set<String> databaseStubs = loadDatabaseStubIds();
        recordDrift(databaseStubs, wireMockStubs);
    }

    /**
     * Read-only DB call wrapped in its own short-lived transaction so the
     * connection is returned before the bulk-insert below.
     */
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
                        "Mock " + id + " is in DB but not in WireMock, \n <a href='/mock/" + id + "'>Wiremate</>")
                )
                .forEach(batch::add);
        missingFromDb.stream()
                .map(id -> new Notification(
                        "Mock " + id + " is in WireMock but not in DB, \n <a href='/stubs/" + id + "'>Wiremock</>")
                )
                .forEach(batch::add);

        notificationRepository.saveAll(batch);
        log.info("Cross-check produced {} drift notifications ({} DB-only, {} WireMock-only).",
                batch.size(), missingFromWireMock.size(), missingFromDb.size());
    }
}
