-- Member defaults (Settings > Membership > Member defaults, MemberDefaults): the department and office a new member is given when none is chosen, and the
-- member types that may be registered, per cooperative (each database is its own cooperative), chosen by a super administrator. Until now they were built
-- into the application (department 2, office 1, Regular Individual and Associate). This stores those same choices, but only where this database has them,
-- and never over a choice already made, so a cooperative keeps working as before and another one is not given things it does not have.
-- The MySQL copy is V074. Applied with scripts/migrate-postgres.ps1, or from the application (Apply them now).
INSERT INTO legacy.ecis_setting (setting_key, setting_value, updated_by, updated_at)
SELECT 'membership.default_department', '2', 'system', CURRENT_TIMESTAMP WHERE EXISTS (SELECT 1 FROM legacy.dept WHERE deptid = 2)
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO legacy.ecis_setting (setting_key, setting_value, updated_by, updated_at)
SELECT 'membership.default_office', '1', 'system', CURRENT_TIMESTAMP WHERE EXISTS (SELECT 1 FROM legacy.restrik WHERE restrikid = 1)
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO legacy.ecis_setting (setting_key, setting_value, updated_by, updated_at)
SELECT 'membership.registration_types', string_agg(clienttypeid::text, ',' ORDER BY clienttypeid), 'system', CURRENT_TIMESTAMP
FROM legacy.clienttype WHERE clienttypeid IN (1, 2) HAVING COUNT(*) > 0
ON CONFLICT (setting_key) DO NOTHING;
