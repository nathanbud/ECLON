-- Debit/credit memos: an accountant's adjustment that moves money between members' savings or share capital accounts and ledger accounts, in one balanced entry. It follows the journal
-- path (DRAFT, FOR_REVIEW, APPROVED, POSTED) and the maker, approver and poster must be three different people. A memo waits here until it is posted; posting writes the old system's
-- Debit/Credit Memo (transsum module 1 type 2, transdt, the member's sldtl rows and gltrans). A posted memo is never edited: it is reversed by a new memo. Run with scripts/migrate.ps1.

CREATE TABLE IF NOT EXISTS ecis_memo (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  reference     VARCHAR(20) NULL,                         -- MEMO-yyyy-nnnn
  memo_date     DATE NOT NULL,
  description   VARCHAR(200) NOT NULL,                    -- why, and the authority for it
  status        VARCHAR(10) NOT NULL DEFAULT 'DRAFT',     -- DRAFT, FOR_REVIEW, APPROVED, POSTED, CANCELLED
  created_by    VARCHAR(60) NOT NULL,
  created_at    DATETIME NOT NULL,
  approved_by   VARCHAR(60) NULL,
  approved_at   DATETIME NULL,
  posted_by     VARCHAR(60) NULL,
  posted_at     DATETIME NULL,
  cancel_reason VARCHAR(200) NULL,
  reverses_id   BIGINT UNSIGNED NULL,                     -- the posted memo this one reverses
  tr_year       SMALLINT UNSIGNED NULL,                   -- the legacy transaction once posted
  tr_ctlno      INT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_memo_ref (branch_code, reference),
  KEY ix_memo_status (branch_code, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_memo_line (
  memo_id      BIGINT UNSIGNED NOT NULL,
  line_no      SMALLINT UNSIGNED NOT NULL,
  kind         VARCHAR(8) NOT NULL,                       -- SAVINGS, SHARE (a member's account) or LEDGER (a general ledger account)
  client_id    INT UNSIGNED NULL,
  type_code    TINYINT UNSIGNED NULL,
  ref_no       INT UNSIGNED NULL,
  account_code VARCHAR(10) NULL,                          -- LEDGER lines only
  debit        DECIMAL(15,2) NOT NULL DEFAULT 0,
  credit       DECIMAL(15,2) NOT NULL DEFAULT 0,
  note         VARCHAR(100) NULL,
  PRIMARY KEY (memo_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- MySQL 5.6 grants on one table at a time. A line is written once with its memo; a reversal is a new memo.
GRANT SELECT, INSERT, UPDATE ON ecis_memo TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_memo TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_memo_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_memo_line TO 'coop_app'@'%';
