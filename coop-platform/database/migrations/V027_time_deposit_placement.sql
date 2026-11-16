-- Placing a time deposit at the front office writes the certificate row in td (the account master row in slmastersum and the ledger rows are already
-- covered by V011, V014 and V016). Run by an administrator. Idempotent.
GRANT INSERT ON td TO 'coop_app'@'localhost';
GRANT INSERT ON td TO 'coop_app'@'%';
