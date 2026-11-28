-- A product type's name may be up to 100 characters; see the PostgreSQL copy V048. Only widens. Run with scripts/migrate.ps1.
ALTER TABLE sltype MODIFY SLTypeM_DESC VARCHAR(100) NULL, MODIFY SLTypeM_DESC2 VARCHAR(100) NULL;
