-- Phase 12: least privilege. Flyway runs as the schema OWNER; the service connects as ${appRole}, which gets
-- DML only, and on append-only tables (audit_log) only SELECT/INSERT. The runtime role can
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
  EXECUTE format('GRANT USAGE ON SCHEMA identity TO %I', app);
  FOR t IN SELECT c.relname FROM pg_class c
           WHERE c.relnamespace = 'identity'::regnamespace AND c.relkind IN ('r','p') AND NOT c.relispartition LOOP
    IF t.relname = 'flyway_schema_history' THEN
      EXECUTE format('GRANT SELECT ON identity.%I TO %I', t.relname, app);
    ELSIF t.relname = ANY (ARRAY['audit_log']::text[]) THEN
      EXECUTE format('GRANT SELECT, INSERT ON identity.%I TO %I', t.relname, app);
    ELSE
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON identity.%I TO %I', t.relname, app);
    END IF;
  END LOOP;
  EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA identity TO %I', app);
  EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA identity GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', app);
  EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA identity GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', app);
END $$;
