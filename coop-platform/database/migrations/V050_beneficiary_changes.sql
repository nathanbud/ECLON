-- Beneficiary changes, maker-checker. A beneficiary is added, changed or removed by a request in ecis_member_request (kind BENEF) that a Membership Officer other than the requester
-- approves; nothing touches the legacy benef table until then. Approval inserts, updates or deletes the benef row, so the app's database user needs those rights on it. The benef
-- table has no "removed" flag, so a removal deletes the row; the audit row written with it keeps the name, relationship, birth date, occupation and kind that were removed.
-- Run with scripts/migrate.ps1.

GRANT INSERT, UPDATE, DELETE ON benef TO 'coop_app'@'localhost';
GRANT INSERT, UPDATE, DELETE ON benef TO 'coop_app'@'%';
