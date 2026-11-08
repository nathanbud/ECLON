-- MySQL keeps flags as tinyint(1) and the application reads and writes them as 0/1 numbers (COALESCE(flag, 0) = 0, getInt, setInt).
-- The copy of the old tables made them PostgreSQL booleans, which that code cannot use, so they become smallint again.
SET search_path = legacy;
DO $$
DECLARE r record; def text;
BEGIN
    FOR r IN SELECT table_name, column_name, column_default FROM information_schema.columns
             WHERE table_schema = 'legacy' AND data_type = 'boolean' LOOP
        def := r.column_default;
        IF def IS NOT NULL THEN
            EXECUTE format('ALTER TABLE legacy.%I ALTER COLUMN %I DROP DEFAULT', r.table_name, r.column_name);
        END IF;
        EXECUTE format('ALTER TABLE legacy.%I ALTER COLUMN %I TYPE smallint USING (CASE WHEN %I THEN 1 ELSE 0 END)', r.table_name, r.column_name, r.column_name);
        IF def IS NOT NULL THEN
            EXECUTE format('ALTER TABLE legacy.%I ALTER COLUMN %I SET DEFAULT %s', r.table_name, r.column_name, CASE WHEN def ILIKE 'true%' THEN '1' ELSE '0' END);
        END IF;
    END LOOP;
END $$;
