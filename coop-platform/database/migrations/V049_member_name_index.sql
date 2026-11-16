-- Member search by first name. The client table already has NAME (LName, FName, MName, SName) and idx_client_lname, so a last-name prefix is indexed; a first-name prefix was not,
-- so searching by first name read the whole table. The columns are latin1_swedish_ci (case-insensitive), so a plain LIKE 'abc%' uses the index without lower().
-- This adds an index to a legacy table (no data changes). Run with scripts/migrate.ps1.

ALTER TABLE client ADD INDEX idx_client_fname (FName);
