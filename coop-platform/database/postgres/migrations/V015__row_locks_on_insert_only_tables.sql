-- The application takes row locks (SELECT ... FOR UPDATE) to hand out the next control or reference number safely. MySQL needs only SELECT for that,
-- PostgreSQL also needs the UPDATE privilege. These tables are insert-only for the application (posted records are never changed), so it gets the
-- UPDATE privilege here and a trigger turns any real UPDATE statement into a permission error: locking works, changing does not.
SET search_path = legacy;
CREATE OR REPLACE FUNCTION legacy.deny_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'permission denied: % is insert-only for the application', TG_TABLE_NAME USING ERRCODE = '42501';
END $$;
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['transsum', 'slmastersum', 'sc', 'sd', 'td', 'loan', 'sldtl', 'osl', 'hold', 'ecis_inventory_txn'] LOOP
        EXECUTE format('GRANT UPDATE ON legacy.%I TO coop_app', t);
        EXECUTE format('DROP TRIGGER IF EXISTS deny_update ON legacy.%I', t);
        EXECUTE format('CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.%I FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update()', t);
    END LOOP;
END $$;
