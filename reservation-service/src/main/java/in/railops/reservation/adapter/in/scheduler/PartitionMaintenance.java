package in.railops.reservation.adapter.in.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Keeps monthly partitions (and each allocation partition's no-double-berth EXCLUDE constraint) created ahead of
 * the booking horizon.
 *
 * <p>Found in Phase 11: V3 created partitions ONCE at migration time, 4 months ahead, and nothing ever extended
 * them. A booking for a run date past that window fails with "no partition of relation found for row": every new
 * booking would start failing a few months after a deploy with no code change.
 *
 * <p>Runs at startup and daily. Advisory-locked so concurrent instances don't race on CREATE TABLE. Exposes
 * {@code reservation.partitions.runway.days}: alert well above the advance-reservation period.
 */
@Component
public class PartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);
    private static final long LOCK = 0x5041525449544E4CL;   // "PARTITNL"

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final int monthsAhead;

    public PartitionMaintenance(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry meters,
                                @Value("${railops.partitions.months-ahead:6}") int monthsAhead) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.monthsAhead = monthsAhead;
        Gauge.builder("reservation.partitions.runway.days", this, PartitionMaintenance::runwayDays)
                .description("days from today to the end of the last allocation partition").register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        try {
            ensure();
        } catch (RuntimeException e) {
            log.error("partition maintenance failed at startup (runway {} days)", runwayDays(), e);
        }
    }

    @Scheduled(cron = "${railops.partitions.cron:0 17 2 * * *}", zone = "UTC")
    public void daily() {
        atStartup();
    }

    /** Idempotent. Returns true if this instance did the work, false if another holds the lock. */
    public boolean ensure() {
        Boolean did = tx.execute(s -> {
            Boolean got = jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)").param("k", LOCK).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(got)) {
                return false;
            }
            jdbc.sql("CALL reservation.ensure_partitions(1, :ahead)").param("ahead", monthsAhead).update();
            return true;
        });
        log.info("partitions ensured {} months ahead; runway {} days", monthsAhead, runwayDays());
        return Boolean.TRUE.equals(did);
    }

    public long runwayDays() {
        String last = jdbc.sql("""
                        SELECT max(substring(c.relname FROM 'allocation_([0-9]{6})$'))
                        FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
                        WHERE p.relname = 'allocation' AND p.relnamespace = 'reservation'::regnamespace
                        """).query(String.class).optional().orElse(null);
        if (last == null) {
            return 0;
        }
        LocalDate end = YearMonth.parse(last, DateTimeFormatter.ofPattern("yyyyMM")).plusMonths(1).atDay(1);
        // Partitions are by run_date, an IST calendar date; the JVM runs in UTC (off by a day 00:00-05:30 IST).
        return ChronoUnit.DAYS.between(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")), end);
    }
}
