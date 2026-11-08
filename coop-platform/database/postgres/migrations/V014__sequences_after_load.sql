-- The copy of the old tables brought the rows with their ids but left every sequence at its start, so the first INSERT of a new row
-- collided with an existing id (pk_benef, pk_image ...). Each sequence now continues after the highest id of its column.
SET search_path = legacy;
DO $$
DECLARE r record; hi bigint;
BEGIN
    FOR r IN SELECT c.relname AS seq, d.refobjid::regclass AS tbl, a.attname AS col
             FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             JOIN pg_depend d ON d.objid = c.oid AND d.deptype IN ('a', 'i')
             JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid
             WHERE c.relkind = 'S' AND n.nspname = 'legacy' LOOP
        EXECUTE format('SELECT COALESCE(MAX(%I), 0) FROM %s', r.col, r.tbl) INTO hi;
        PERFORM setval(format('legacy.%I', r.seq), hi + 1, false);
    END LOOP;
END $$;
