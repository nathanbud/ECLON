CREATE SCHEMA IF NOT EXISTS legacy;
CREATE SCHEMA IF NOT EXISTS core;

COMMENT ON SCHEMA legacy IS 'Copy of the old MySQL database; names lowercased, nothing else changed. Read-only.';
COMMENT ON SCHEMA core IS 'New cooperative platform design.';
