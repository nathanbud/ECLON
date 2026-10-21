-- The Physical section (height, weight, blood type) is removed from the client profile.
-- The columns stay in the legacy MySQL database; they are not carried into PostgreSQL.
-- scripts/load-legacy-to-postgres.py skips them (OMIT).
ALTER TABLE legacy."client" DROP COLUMN IF EXISTS "height", DROP COLUMN IF EXISTS "weight", DROP COLUMN IF EXISTS "bloodtype";
