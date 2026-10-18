-- Loan application workflow (verify, approve) and the single-person exemption.
--  * ecis_single_hand: people a system administrator has allowed to take every step of a loan (encode, verify, approve the application, approve and
--    post its release voucher) alone, instead of needing different people. One row per person, with the reason and who granted it.
--  * Verify and approve change only four columns of loanapp: STATUSID, APPRV_DATE, APPRVBY and TR_CTLNO (the voucher number).
-- Approving also inserts the release voucher into transsum and transdt, which the grants of V006 already allow.
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_single_hand (
  username    VARCHAR(50)  NOT NULL,
  reason      VARCHAR(500) NOT NULL,
  granted_by  VARCHAR(50)  NOT NULL,
  granted_at  DATETIME     NOT NULL,
  PRIMARY KEY (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, DELETE ON ecis_single_hand TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_single_hand TO 'coop_app'@'%';
GRANT UPDATE (STATUSID, APPRV_DATE, APPRVBY, TR_CTLNO) ON loanapp TO 'coop_app'@'localhost';
GRANT UPDATE (STATUSID, APPRV_DATE, APPRVBY, TR_CTLNO) ON loanapp TO 'coop_app'@'%';
