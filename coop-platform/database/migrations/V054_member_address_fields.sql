-- Member address, as people write it: the house, unit, block and lot number, and the subdivision, village or building name, for the present and the provincial address.
-- The old client table only had street, barangay, city, province and zip. New nullable columns (the PostgreSQL copy has the same, V017); existing members start blank and the
-- old system ignores them. coop_app already has table-level INSERT and UPDATE on client, so these need no grant. Run with scripts/migrate.ps1.
ALTER TABLE client
    ADD COLUMN ResAddHouseNo VARCHAR(60) NULL,
    ADD COLUMN ResAddSubdivision VARCHAR(100) NULL,
    ADD COLUMN ProvAddHouseNo VARCHAR(60) NULL,
    ADD COLUMN ProvAddSubdivision VARCHAR(100) NULL;
