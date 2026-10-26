\set ON_ERROR_STOP on
-- Execute with psql while connected to tori_main as a database administrator.
-- This is an operations script, not a Flyway migration.
-- It deliberately fails if the role already exists; inspect existing grants
-- rather than silently changing an existing principal.

BEGIN;

CREATE ROLE tori_dbx_readonly
  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;

ALTER ROLE tori_dbx_readonly SET default_transaction_read_only = on;
GRANT CONNECT ON DATABASE tori_main TO tori_dbx_readonly;
GRANT USAGE ON SCHEMA public TO tori_dbx_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO tori_dbx_readonly;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO tori_dbx_readonly;

-- Tori's checked-in Compose config runs PostgreSQL/Flyway as role `tori`.
ALTER DEFAULT PRIVILEGES FOR ROLE tori IN SCHEMA public
  GRANT SELECT ON TABLES TO tori_dbx_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE tori IN SCHEMA public
  GRANT SELECT ON SEQUENCES TO tori_dbx_readonly;

COMMIT;
