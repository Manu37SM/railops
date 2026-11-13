-- Phase 12: least privilege. Flyway runs as the schema OWNER; the service connects as ${appRole}, which gets
-- DML only, and on append-only tables (ledger_entry, webhook_event) only SELECT/INSERT. The runtime role can
-- therefore never ALTER/DROP, never disable the immutability triggers, and never rewrite those tables even if a
-- trigger were dropped. When ${appRole} IS the owner (local dev, tests) there is nothing to grant.
DO $$
DECLARE
  app TEXT := '${appRole}';
  t   RECORD;
BEGIN
  IF app = current_user THEN
    RETURN;
  END IF;
  EXECUTE format('GRANT USAGE ON SCHEMA payment TO %I', app);
  FOR t IN SELECT c.relname FROM pg_class c
           WHERE c.relnamespace = 'payment'::regnamespace AND c.relkind IN ('r','p') AND NOT c.relispartition LOOP
    IF t.relname = 'flyway_schema_history' THEN
      EXECUTE format('GRANT SELECT ON payment.%I TO %I', t.relname, app);
    ELSIF t.relname = ANY (ARRAY['ledger_entry','webhook_event']::text[]) THEN
      EXECUTE format('GRANT SELECT, INSERT ON payment.%I TO %I', t.relname, app);
    ELSE
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON payment.%I TO %I', t.relname, app);
    END IF;
  END LOOP;
  EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA payment TO %I', app);
  EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA payment GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', app);
  EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA payment GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', app);
END $$;
