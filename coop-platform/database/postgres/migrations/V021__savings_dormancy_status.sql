-- Dormant savings accounts (Settings > Savings dormancy): an administrator marks a quiet savings account dormant and an administrator or Membership Officer reactivates it.
-- V015 made legacy.sd insert-only for the application: a statement trigger refuses every UPDATE. Marking and reactivating must change the status of the account, so that
-- trigger is replaced on sd ONLY by a row trigger that lets through exactly one kind of change and still refuses the rest:
--   * nothing may differ except the status and the "updated by / at" stamps (the owner, type, number, opening date, minimum balance, rate and remarks stay untouchable);
--   * the status may only go from active (15) to dormant (16) or back; closing an account (17) is not opened up here.
-- Row locks (SELECT ... FOR UPDATE) keep working, as before. The other insert-only tables are not touched. Each change is audited by the application
-- (savings.dormant.mark, savings.dormant.reactivate). The MySQL copy is V058. Apply with scripts/migrate-postgres.ps1.
SET search_path = legacy;
CREATE OR REPLACE FUNCTION legacy.sd_status_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'sdstatus' - 'sdupddatetime' - 'sdupduser') IS DISTINCT FROM (to_jsonb(OLD) - 'sdstatus' - 'sdupddatetime' - 'sdupduser') THEN
        RAISE EXCEPTION 'permission denied: only the status of a savings account may be changed by the application' USING ERRCODE = '42501';
    END IF;
    IF NEW.sdstatus IS DISTINCT FROM OLD.sdstatus AND NOT ((OLD.sdstatus = 15 AND NEW.sdstatus = 16) OR (OLD.sdstatus = 16 AND NEW.sdstatus = 15)) THEN
        RAISE EXCEPTION 'permission denied: a savings account may only go from active to dormant or back' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS deny_update ON legacy.sd;
DROP TRIGGER IF EXISTS status_only ON legacy.sd;
CREATE TRIGGER status_only BEFORE UPDATE ON legacy.sd FOR EACH ROW EXECUTE FUNCTION legacy.sd_status_only();
