-- Phase 15: the claim row now commits BEFORE the provider call (SENDING), then becomes SENT or FAILED.
-- Superset of the old CHECK, so old and new code can run side by side during a rollout (expand only).
ALTER TABLE notification.delivery DROP CONSTRAINT IF EXISTS delivery_status_check;
ALTER TABLE notification.delivery ADD CONSTRAINT delivery_status_check
  CHECK (status IN ('SENDING','SENT','FAILED','SUPPRESSED_RATE_LIMIT','NO_CONTACT'));
ALTER TABLE notification.delivery ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
-- Stuck claims (process died between claim and provider call) are visible to ops instead of silently lost.
CREATE INDEX IF NOT EXISTS ix_delivery_sending ON notification.delivery (created_at) WHERE status = 'SENDING';
