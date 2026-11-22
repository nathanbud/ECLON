-- Member defaults: the choices the application had built in, stored as settings where this database has them; see the PostgreSQL copy V037. Run with scripts/migrate.ps1.
INSERT IGNORE INTO ecis_setting (setting_key, setting_value, updated_by, updated_at)
SELECT 'membership.default_department', '2', 'system', NOW() FROM DUAL WHERE EXISTS (SELECT 1 FROM dept WHERE DeptID = 2);
INSERT IGNORE INTO ecis_setting (setting_key, setting_value, updated_by, updated_at)
SELECT 'membership.default_office', '1', 'system', NOW() FROM DUAL WHERE EXISTS (SELECT 1 FROM restrik WHERE RestrikID = 1);
INSERT IGNORE INTO ecis_setting (setting_key, setting_value, updated_by, updated_at)
SELECT 'membership.registration_types', GROUP_CONCAT(ClientTypeID ORDER BY ClientTypeID), 'system', NOW() FROM clienttype WHERE ClientTypeID IN (1, 2) HAVING COUNT(*) > 0;
