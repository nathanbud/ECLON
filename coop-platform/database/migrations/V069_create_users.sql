-- Creating a user from Settings > Users > New user; see the PostgreSQL copy V032. Run with scripts/migrate.ps1.
GRANT INSERT ON access TO 'coop_app'@'localhost';
GRANT INSERT ON access TO 'coop_app'@'%';
GRANT INSERT ON access_trans TO 'coop_app'@'localhost';
GRANT INSERT ON access_trans TO 'coop_app'@'%';
