package in.railops.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the audit chain head outside the database, where nobody with database access can change it.
 *
 * <p>Production (GCP): the dedicated logger {@code railops.audit.anchor} is routed by a Cloud Logging sink to a log
 * bucket with a LOCKED retention policy (WORM: entries can't be deleted or changed, even by project owners, until
 * retention expires). Optional file sink for compose/dev ({@code railops.audit.anchor-file}, append-only).
 * Verification: export the anchors and POST them to {@code /api/v1/admin/audit/verify-anchors}.
 *
 * <p>Every instance may publish; duplicates of the same head are harmless (same id, same hash).
 */
@Component
public class AuditAnchorPublisher {

    private static final Logger ANCHOR = LoggerFactory.getLogger("railops.audit.anchor");
    private static final Logger log = LoggerFactory.getLogger(AuditAnchorPublisher.class);

    private final AuditService audit;
    private final String file;
    private final AtomicLong lastPublishedId = new AtomicLong(-1);

    public AuditAnchorPublisher(AuditService audit, @Value("${railops.audit.anchor-file:}") String file) {
        this.audit = audit;
        this.file = file;
    }

    @Scheduled(fixedDelayString = "${railops.audit.anchor-ms:300000}", initialDelayString = "${railops.audit.anchor-ms:300000}")
    public void publish() {
        audit.head().ifPresent(h -> {
            if (h.id() == lastPublishedId.get()) {
                return;                      // nothing new since the last anchor
            }
            String line = "{\"type\":\"AUDIT_ANCHOR\",\"id\":" + h.id() + ",\"hash\":\"" + h.hash()
                    + "\",\"at\":\"" + Instant.now() + "\"}";
            ANCHOR.info(line);
            if (!file.isBlank()) {
                try {
                    Files.writeString(Path.of(file), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                            StandardOpenOption.WRITE);
                } catch (IOException e) {
                    log.error("audit anchor file sink failed ({}); the log sink still has it", file, e);
                }
            }
            lastPublishedId.set(h.id());
        });
    }
}
