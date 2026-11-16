-- Which deposit types a co-maker's agreed amount may be held on, and which one the loan application suggests first.
-- One row per savings (class 22) or share capital (class 31) type of a branch. Seeded so that nothing changes: every enabled type of both
-- classes is holdable, and the default is Paid up Share Cap (class 31, type 1). Administrators change it on the Loan Setup screen.
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_hold_setting (
  branch_code SMALLINT UNSIGNED NOT NULL,
  slc_code    TINYINT UNSIGNED NOT NULL,
  slt_code    TINYINT UNSIGNED NOT NULL,
  holdable    TINYINT(1) NOT NULL DEFAULT 1,
  is_default  TINYINT(1) NOT NULL DEFAULT 0,
  updated_at  DATETIME NULL,
  updated_by  VARCHAR(60) NULL,
  PRIMARY KEY (branch_code, slc_code, slt_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
INSERT IGNORE INTO ecis_hold_setting (branch_code, slc_code, slt_code, holdable, is_default)
  SELECT SLTypeBR_CODE, SLTypeSLC_CODE, SLTypeSLT_CODE, 1, CASE WHEN SLTypeSLC_CODE = 31 AND SLTypeSLT_CODE = 1 THEN 1 ELSE 0 END
  FROM sltype WHERE SLTypeSLC_CODE IN (22, 31) AND COALESCE(SLTypeIsDisabled, 0) = 0;
GRANT SELECT, INSERT, UPDATE ON ecis_hold_setting TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_hold_setting TO 'coop_app'@'%';
