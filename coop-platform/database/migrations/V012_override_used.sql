-- A supervisor approval in ecis_fo_override can be spent on one posting only: used_ref records which (for example
-- 2/23/113758 for a savings withdrawal). Run by an administrator after V007. Idempotent.
SET @has_col = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecis_fo_override' AND column_name = 'used_ref');
SET @sql = IF(@has_col = 0, 'ALTER TABLE ecis_fo_override ADD COLUMN used_ref VARCHAR(60) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
