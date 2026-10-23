-- What each co-maker agreed to cover on a loan application, and the hold placed for it.
--  * ecis_loan_comaker: one row per co-maker of an application: the amount, the co-maker's own account the hold goes on (savings class 22 or
--    share capital class 31), and when the hold was placed (NULL until the release is posted). The legacy comaker table has no amount column
--    and is not changed.
--  * The hold itself is a row of the legacy hold table, written like the old system: reason 1 (Collateral), description "CM to <borrower>",
--    reference "CV#<voucher number>", dated the day the release is posted (see documentation/loan-posting-map.md). INSERT only: releasing a
--    hold when the loan is paid is not built.
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_loan_comaker (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  branch_code    SMALLINT     NOT NULL,
  application_id INT UNSIGNED NOT NULL,
  comaker_client MEDIUMINT UNSIGNED NOT NULL,
  amount         DECIMAL(15,2) NOT NULL,
  hold_slc       TINYINT UNSIGNED NOT NULL,
  hold_slt       TINYINT UNSIGNED NOT NULL,
  hold_ref_no    MEDIUMINT UNSIGNED NOT NULL,
  placed_at      DATETIME     NULL,
  placed_by      SMALLINT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_comaker_app (branch_code, application_id, comaker_client)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE ON ecis_loan_comaker TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_loan_comaker TO 'coop_app'@'%';
GRANT INSERT ON hold TO 'coop_app'@'localhost';
GRANT INSERT ON hold TO 'coop_app'@'%';
