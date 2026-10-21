-- Tables in the legacy schema are created by the loader (coop_admin), so give the application
-- user read access to anything created there from now on, and to what exists already.

ALTER DEFAULT PRIVILEGES FOR ROLE coop_admin IN SCHEMA legacy GRANT SELECT ON TABLES TO coop_app;
GRANT SELECT ON ALL TABLES IN SCHEMA legacy TO coop_app;
