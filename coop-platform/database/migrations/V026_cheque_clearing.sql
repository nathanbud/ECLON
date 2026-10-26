-- Clearing of cheques taken at the front office: the old system marks a received cheque cleared by setting transck.CKISCLEARED to 1
-- (CKCLR_DATE is never filled). The app may change that one column and nothing else on transck. Run by an administrator. Idempotent.
GRANT UPDATE (CKISCLEARED) ON transck TO 'coop_app'@'localhost';
GRANT UPDATE (CKISCLEARED) ON transck TO 'coop_app'@'%';
