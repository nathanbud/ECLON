-- SL Controls: which accounts open by themselves when a member is registered or reactivated, per member type and account type; see the PostgreSQL copy V031.
-- Run with scripts/migrate.ps1. Not idempotent: run once.
CREATE TABLE IF NOT EXISTS ecis_sl_control (
  branch_code INT NOT NULL,
  client_type INT NOT NULL,
  slc_code    INT NOT NULL,
  slt_code    INT NOT NULL,
  auto_open   TINYINT(1) NOT NULL,
  updated_by  VARCHAR(60) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (branch_code, client_type, slc_code, slt_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_sl_control TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_sl_control TO 'coop_app'@'%';

INSERT INTO ecis_sl_control (branch_code, client_type, slc_code, slt_code, auto_open, updated_by, updated_at)
SELECT b.BranchesID, d.client_type, d.slc_code, d.slt_code, 1, 'system', NOW(3)
FROM branches b
CROSS JOIN (SELECT 1 AS client_type, 22 AS slc_code, 1 AS slt_code UNION ALL SELECT 1, 29, 5 UNION ALL SELECT 1, 29, 6 UNION ALL SELECT 1, 31, 1
            UNION ALL SELECT 2, 22, 2 UNION ALL SELECT 2, 31, 3) d
WHERE EXISTS (SELECT 1 FROM sltype t WHERE t.SLTypeBR_CODE = b.BranchesID AND t.SLTypeSLC_CODE = d.slc_code AND t.SLTypeSLT_CODE = d.slt_code);
