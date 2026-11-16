-- Cheques taken at the front office (cash receipts by cheque, mixed cash and cheque). Every cash receipt of the old system also writes one
-- transck row per tender (a cash row, plus one row per cheque), so the app may INSERT into transck. Run by an administrator. Idempotent.
GRANT INSERT ON transck TO 'coop_app'@'localhost';
GRANT INSERT ON transck TO 'coop_app'@'%';
