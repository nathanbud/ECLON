-- The member ledger (sldtl) refuses an entry unless slsum has a row for that account and event, dated by the first entry. A loan's first
-- interest payment is the first entry of its interest event, so the app has to add that row. Run by an administrator. Idempotent.
GRANT INSERT ON slsum TO 'coop_app'@'localhost';
GRANT INSERT ON slsum TO 'coop_app'@'%';
