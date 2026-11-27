-- Starter setup also loads the chart of accounts' own lists; see the PostgreSQL copy V047. Run with scripts/migrate.ps1.
GRANT INSERT ON acctlevel TO 'coop_app'@'localhost';
GRANT INSERT ON acctlevel TO 'coop_app'@'%';
GRANT INSERT ON glaccttype TO 'coop_app'@'localhost';
GRANT INSERT ON glaccttype TO 'coop_app'@'%';
GRANT INSERT ON baltype TO 'coop_app'@'localhost';
GRANT INSERT ON baltype TO 'coop_app'@'%';
