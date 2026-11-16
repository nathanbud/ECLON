-- MySQL compares text without regard to case for these columns (the tables are latin1_swedish_ci): a member is found by "budikey" or "BUDIKEY",
-- and a person signs in as "Admin" or "admin". PostgreSQL compares exactly, so the member name columns and the sign-in name get a case-insensitive
-- (nondeterministic ICU) collation. They stay varchar, so the JDBC reads and the SQL are unchanged, and = and LIKE ignore case (LIKE needs PostgreSQL 18 or later).
-- Prefix searches on names scan the table until a trigram index is added.
SET search_path = legacy;
CREATE COLLATION IF NOT EXISTS legacy.ci (provider = icu, locale = 'und-u-ks-level2', deterministic = false);
ALTER TABLE legacy.client ALTER COLUMN lname TYPE varchar(50) COLLATE legacy.ci, ALTER COLUMN fname TYPE varchar(70) COLLATE legacy.ci,
    ALTER COLUMN mname TYPE varchar(30) COLLATE legacy.ci, ALTER COLUMN sname TYPE varchar COLLATE legacy.ci, ALTER COLUMN accountname TYPE varchar(100) COLLATE legacy.ci;
ALTER TABLE legacy.access ALTER COLUMN accessusername TYPE varchar COLLATE legacy.ci;
