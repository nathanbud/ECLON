-- Products come from each cooperative's own setup, never from another cooperative's list (2026-11-24).
-- 1. Account opening (SL Controls): switches stored for an account type this database does not have were copied from the first cooperative's built-in list;
--    they are removed, so a member gets exactly the accounts switched on for types the cooperative set up in Settings > Products.
-- 2. Product roles: which fund and payable type holds members' vouchers payable is now a setting (product.vouchers_payable_type, Settings > Products >
--    Product roles). It is recorded here only where the database's own type 29/1 is named as vouchers payable; elsewhere an administrator chooses it.
-- The MySQL copy is V080. Applied with scripts/migrate-postgres.ps1, or from the application (Apply them now).
DELETE FROM legacy.ecis_sl_control c
WHERE NOT EXISTS (SELECT 1 FROM legacy.sltype t WHERE t.sltypebr_code = c.branch_code AND t.sltypeslc_code = c.slc_code AND t.sltypeslt_code = c.slt_code);

INSERT INTO legacy.ecis_setting (setting_key, setting_value, updated_by, updated_at)
SELECT 'product.vouchers_payable_type', '1', 'system', CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM legacy.sltype t WHERE t.sltypeslc_code = 29 AND t.sltypeslt_code = 1 AND (t.sltypem_desc ILIKE '%voucher%' OR t.sltypem_desc2 ILIKE '%voucher%'))
ON CONFLICT (setting_key) DO NOTHING;
