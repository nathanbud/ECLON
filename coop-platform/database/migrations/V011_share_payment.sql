-- Share capital payments from the front office (guide phase 7). The app already may INSERT into transsum, transdt and gltrans
-- (V006); a payment also adds one row to the member's subsidiary ledger. Run by an administrator. Idempotent.
GRANT INSERT ON sldtl TO 'coop_app'@'localhost';
GRANT INSERT ON sldtl TO 'coop_app'@'%';
