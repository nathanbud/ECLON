-- Member address, as people write it: the house, unit, block and lot number, and the subdivision, village or building name, for the present and the provincial address.
-- The old client table only had street, barangay, city, province and zip. New nullable columns; existing members start blank and the old system ignores them.
-- The app user already has table-level INSERT and UPDATE on client, so these need no grant. Apply with scripts/migrate-postgres.ps1.
ALTER TABLE legacy.client
    ADD COLUMN resaddhouseno varchar(60),
    ADD COLUMN resaddsubdivision varchar(100),
    ADD COLUMN provaddhouseno varchar(60),
    ADD COLUMN provaddsubdivision varchar(100);
