-- Links a posted journal to the legacy ledger entry it created (General Journal: transsum module 1, code 1).
-- NULL means the journal was marked POSTED before posting to the ledger existed. Run by an administrator.
ALTER TABLE ecis_journal
  ADD COLUMN tr_year  SMALLINT UNSIGNED NULL AFTER posted_at,
  ADD COLUMN tr_ctlno INT UNSIGNED NULL AFTER tr_year;
