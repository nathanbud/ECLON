-- Offices (Settings > Organization > Offices, OfficeController): the cooperative's offices, the old table restrik (RestrikID, RestrikDesc), which a member
-- (client.RestrikID, client_temp.RestrikID) and a user's office list (access.AccessRestrik, codes like "003,002,001") point to. A new member is given
-- office 1 when none is chosen, so a database without offices cannot register members. Administrators add, rename, and delete an office nothing uses;
-- the number never changes. Every change needs a reason and is audited. The MySQL copy is V072. Apply with scripts/migrate-postgres.ps1.
GRANT INSERT, DELETE ON legacy.restrik TO coop_app;
GRANT UPDATE (restrikdesc) ON legacy.restrik TO coop_app;
