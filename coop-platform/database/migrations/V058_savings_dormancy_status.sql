-- Dormant savings accounts (Settings > Savings dormancy): an administrator marks a quiet savings account dormant and an administrator or Membership Officer reactivates it.
-- The application may change only the status of a savings account and its "updated by / at" stamps; every other column of sd stays read-only for it.
-- Each change is audited by the application (savings.dormant.mark, savings.dormant.reactivate). The PostgreSQL copy is V021. Run by an administrator. Idempotent.
GRANT UPDATE (SDSTATUS, SDUPDDATETIME, SDUPDUSER) ON sd TO 'coop_app'@'localhost';
GRANT UPDATE (SDSTATUS, SDUPDDATETIME, SDUPDUSER) ON sd TO 'coop_app'@'%';
