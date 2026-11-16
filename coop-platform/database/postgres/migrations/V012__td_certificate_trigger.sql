-- MySQL trigger update_td_certno (BEFORE INSERT on td): the certificate number is the reference number.
SET search_path = legacy;
CREATE OR REPLACE FUNCTION legacy.td_certno() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.tdcert_num := NEW.tdref_no;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS update_td_certno ON legacy.td;
CREATE TRIGGER update_td_certno BEFORE INSERT ON legacy.td FOR EACH ROW EXECUTE FUNCTION legacy.td_certno();
