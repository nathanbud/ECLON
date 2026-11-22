-- Offices (Settings > Organization > Offices): the application may add, rename and delete an unused office in restrik; see the PostgreSQL copy V035.
-- Run with scripts/migrate.ps1.
GRANT INSERT, DELETE ON restrik TO 'coop_app'@'localhost';
GRANT INSERT, DELETE ON restrik TO 'coop_app'@'%';
GRANT UPDATE (RestrikDesc) ON restrik TO 'coop_app'@'localhost';
GRANT UPDATE (RestrikDesc) ON restrik TO 'coop_app'@'%';
