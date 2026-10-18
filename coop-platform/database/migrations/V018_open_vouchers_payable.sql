-- A member's first loan application opens their vouchers-payable account (class 29 type 1): one row in osl (and one in slmastersum, granted in V016).
-- Insert only. Run by an administrator. Idempotent.
GRANT INSERT ON osl TO 'coop_app'@'localhost';
GRANT INSERT ON osl TO 'coop_app'@'%';
