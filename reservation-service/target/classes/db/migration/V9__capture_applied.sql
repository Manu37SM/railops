-- INC-003 (Phase 13): "has this payment's capture already been applied to this booking?" must be RECORDED, not
-- inferred from status + amount. Without it, a duplicate/replayed/corrupted PaymentCaptured for a CONFIRMED
-- booking failed the amount check and emitted a refund for the payment that had confirmed it (seat + refund).
--
-- Expand-only: nullable column, no default rewrite, old code ignores it. NULL = unknown (the previous behaviour
-- applies), so the backfill is deliberately conservative: only states that are reachable solely by consuming the
-- capture. Large tables: run the UPDATE in batches out of band before deploying (docs/runbooks).
ALTER TABLE reservation.booking ADD COLUMN captured_payment_id UUID;

UPDATE reservation.booking SET captured_payment_id = payment_id
WHERE payment_id IS NOT NULL AND captured_payment_id IS NULL
  AND status IN ('CONFIRMED', 'PARTIALLY_CANCELLED', 'FAILED_REFUNDING');
