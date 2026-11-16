-- Settings > Account Types can delete a type that nothing ever used (a type added by mistake): no accounts, no posted or front-office entries,
-- no cheques, passbooks, holds, loan deductions or settings point at it (AccountTypeRules.deleteProblems). Its ledger links (glcontrol, where the
-- application already has DELETE) go with it, and the whole row and its links are written to the audit log as account.type.delete.
-- A type that was used is only switched off. The MySQL copy is V061. Apply with scripts/migrate-postgres.ps1.
GRANT DELETE ON legacy.sltype TO coop_app;
