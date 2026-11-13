CREATE SCHEMA IF NOT EXISTS payment;

CREATE FUNCTION payment.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TABLE payment.payment (
  id                 UUID          PRIMARY KEY,
  pnr                CHAR(10)      NOT NULL,
  user_id            UUID          NOT NULL,
  amount             NUMERIC(12,2) NOT NULL CHECK (amount > 0),
  currency           CHAR(3)       NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  status             TEXT          NOT NULL CHECK (status IN ('CREATED','PENDING','CAPTURED','FAILED','EXPIRED')),
  gateway            TEXT          NOT NULL,
  gateway_order_id   TEXT          UNIQUE,
  gateway_payment_id TEXT          UNIQUE,
  captured_amount    NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (captured_amount >= 0),
  pay_by             TIMESTAMPTZ   NOT NULL,
  created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
  version            INT           NOT NULL DEFAULT 0
);
-- At most one OPEN (not yet decided) payment per PNR. CAPTURED is deliberately excluded: a passenger can finish
-- an old checkout tab after a newer attempt exists, and a capture is a fact that must be recorded (then refunded
-- by reservation as a superseded payment), not rejected by an index into an endless webhook retry loop.
CREATE UNIQUE INDEX ux_payment_open_pnr ON payment.payment (pnr) WHERE status IN ('CREATED','PENDING');
CREATE INDEX ix_payment_pending ON payment.payment (created_at) WHERE status = 'PENDING';

CREATE TABLE payment.webhook_event (
  gateway          TEXT        NOT NULL,
  gateway_event_id TEXT        NOT NULL,
  event_type       TEXT        NOT NULL,
  payload          JSONB       NOT NULL,
  received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (gateway, gateway_event_id)
);

CREATE TABLE payment.refund (
  id                UUID          PRIMARY KEY,
  payment_id        UUID          NOT NULL REFERENCES payment.payment,
  pnr               CHAR(10)      NOT NULL,
  reason            TEXT          NOT NULL,
  amount            NUMERIC(12,2) NOT NULL CHECK (amount > 0),
  status            TEXT          NOT NULL CHECK (status IN
                    ('REQUESTED','SUBMITTING','SUBMITTED','SUCCEEDED','FAILED','MANUAL_REVIEW')),
  idempotency_key   TEXT          NOT NULL UNIQUE,
  gateway_refund_id TEXT          UNIQUE,
  attempts          INT           NOT NULL DEFAULT 0,
  next_attempt_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
  last_error        TEXT,
  created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_refund_due ON payment.refund (next_attempt_at) WHERE status IN ('REQUESTED','FAILED','SUBMITTING');

CREATE TABLE payment.ledger_entry (
  id          BIGSERIAL     PRIMARY KEY,
  txn_id      UUID          NOT NULL,
  account     TEXT          NOT NULL CHECK (account IN ('GATEWAY_CLEARING','CUSTOMER_PAYABLE','TICKET_REVENUE',
                                                        'GST_PAYABLE','CANCELLATION_FEE_REVENUE','REFUND_CLEARING')),
  direction   CHAR(1)       NOT NULL CHECK (direction IN ('D','C')),
  amount      NUMERIC(12,2) NOT NULL CHECK (amount > 0),
  pnr         CHAR(10),
  payment_id  UUID,
  refund_id   UUID,
  memo        TEXT          NOT NULL,
  created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_ledger_txn ON payment.ledger_entry (txn_id);
CREATE INDEX ix_ledger_payment ON payment.ledger_entry (payment_id);
CREATE TRIGGER trg_ledger_immutable BEFORE UPDATE OR DELETE ON payment.ledger_entry
  FOR EACH ROW EXECUTE FUNCTION payment.forbid_mutation();

-- Balanced at COMMIT: a deferred constraint trigger sees every row of the transaction.
CREATE FUNCTION payment.check_txn_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE imbalance NUMERIC;
BEGIN
  SELECT COALESCE(SUM(CASE direction WHEN 'D' THEN amount ELSE -amount END), 0)
    INTO imbalance FROM payment.ledger_entry WHERE txn_id = NEW.txn_id;
  IF imbalance <> 0 THEN
    RAISE EXCEPTION 'ledger txn % unbalanced by %', NEW.txn_id, imbalance;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_ledger_balanced AFTER INSERT ON payment.ledger_entry
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payment.check_txn_balanced();

CREATE TABLE payment.outbox (
  id             BIGSERIAL   PRIMARY KEY,
  event_id       UUID        NOT NULL UNIQUE,
  aggregate_type TEXT        NOT NULL,
  aggregate_id   TEXT        NOT NULL,
  topic          TEXT        NOT NULL,
  event_type     TEXT        NOT NULL,
  payload        JSONB       NOT NULL,
  headers        JSONB       NOT NULL DEFAULT '{}',
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ,
  attempts       INT         NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_unpublished ON payment.outbox (id) WHERE published_at IS NULL;

CREATE TABLE payment.processed_event (
  consumer     TEXT        NOT NULL,
  event_id     UUID        NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
