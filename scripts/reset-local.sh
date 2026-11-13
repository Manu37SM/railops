#!/usr/bin/env bash
# Resets LOCAL test state so the end-to-end suites are repeatable:
#   - wipes bookings/allocations and resets counters for the seeded run
#   - drops the identity schema (identity-service recreates it and the bootstrap admin on next start)
# Never point this at anything but a local database.
set -euo pipefail
export PGPASSWORD=${PGPASSWORD:-railops}
DB="psql -h localhost -U railops -d railops -q"

$DB -c "TRUNCATE reservation.wl_prediction, reservation.allocation, reservation.booking, reservation.passenger, reservation.waitlist_entry,
               reservation.pnr_directory, reservation.idempotency_key, reservation.outbox,
               reservation.allocation_history, reservation.cancellation,
               reservation.bulk_job, reservation.processed_event;
        DELETE FROM reservation.chart;
        -- Restore the SEEDED shape, not just zero the counters: benchmarks resize pools and add coaches, and a
        -- reset that only zeroes usage silently leaves the next suite running against the wrong train.
        DELETE FROM reservation.segment_inventory WHERE train_run_id = 1;
        DELETE FROM reservation.waitlist_counter WHERE train_run_id = 1;
        DELETE FROM reservation.run_coach WHERE train_run_id = 1;
        DELETE FROM reservation.run_stop WHERE train_run_id = 1;
        DELETE FROM reservation.train_run WHERE id = 1;"
$DB -f "$(dirname "$0")/seed.sql"
sudo -u postgres psql -d railops -q -c 'DROP SCHEMA IF EXISTS identity CASCADE;' 2>/dev/null || true
echo "reset done: restart identity-service before running scripts/security_e2e.py"
