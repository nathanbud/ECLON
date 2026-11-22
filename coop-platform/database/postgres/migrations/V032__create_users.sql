-- Creating a user from Settings > Users > New user (UserAccountController.create, documentation/state/sign-in.md): the application may add a row to the old
-- system's access table (taking the next AccessID from its sequence) and copy another user's per-transaction-type rights into access_trans. It still never
-- reads or writes the old password or one-time-code columns: a new person's password lives only in ecis_user_credential, as a temporary one.
-- The MySQL copy is V069. Apply with scripts/migrate-postgres.ps1.
GRANT INSERT ON legacy.access TO coop_app;
GRANT USAGE, SELECT ON SEQUENCE legacy.access_accessid_seq TO coop_app;
GRANT INSERT ON legacy.access_trans TO coop_app;
