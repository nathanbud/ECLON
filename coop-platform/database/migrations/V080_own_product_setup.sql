-- Products come from each cooperative's own setup; see the PostgreSQL copy V043. Run with scripts/migrate.ps1.
DELETE c FROM ecis_sl_control c
WHERE NOT EXISTS (SELECT 1 FROM sltype t WHERE t.SLTypeBR_CODE = c.branch_code AND t.SLTypeSLC_CODE = c.slc_code AND t.SLTypeSLT_CODE = c.slt_code);
INSERT IGNORE INTO ecis_setting (setting_key, setting_value, updated_by, updated_at)
SELECT 'product.vouchers_payable_type', '1', 'system', NOW() FROM DUAL
WHERE EXISTS (SELECT 1 FROM sltype t WHERE t.SLTypeSLC_CODE = 29 AND t.SLTypeSLT_CODE = 1 AND (t.SLTypeM_DESC LIKE '%VOUCHER%' OR t.SLTypeM_DESC2 LIKE '%VOUCHER%'));
