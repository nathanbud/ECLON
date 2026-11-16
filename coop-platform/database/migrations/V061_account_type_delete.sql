-- Settings > Account Types can delete a type that nothing ever used; see the PostgreSQL copy V024. Run with scripts/migrate.ps1. Idempotent.
GRANT DELETE ON sltype TO 'coop_app'@'localhost';
GRANT DELETE ON sltype TO 'coop_app'@'%';
