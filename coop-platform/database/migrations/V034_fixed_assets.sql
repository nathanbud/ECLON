-- Fixed assets (guide phase 15): categories (with their ledger accounts), the asset register, monthly straight-line depreciation, transfers, maintenance and disposals.
-- The old database has no asset register or schedule: depreciation was booked by hand each month as a General Journal (module 1 type 1) debiting 50328
-- "Depreciation & amortization" against the accumulated depreciation accounts, so these are new tables. Depreciation and disposals are written as DRAFT journals
-- (ecis_journal) and go through the existing review, approval and posting. Depreciation and transfer rows are never changed once written. Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_asset_category (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code      SMALLINT UNSIGNED NOT NULL,
  name             VARCHAR(60) NOT NULL,
  asset_account    VARCHAR(20) NOT NULL,                  -- cost of the asset, e.g. 1160111 Office equipment
  accum_account    VARCHAR(20) NOT NULL,                  -- accumulated depreciation, e.g. 1160112
  expense_account  VARCHAR(20) NOT NULL,                  -- depreciation expense, 50328 in the old chart
  life_months      SMALLINT UNSIGNED NOT NULL,            -- default useful life
  salvage_percent  DECIMAL(5,2) NOT NULL DEFAULT 0,       -- default salvage value as a percent of cost
  active           TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY ux_asset_cat (branch_code, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_asset (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code      SMALLINT UNSIGNED NOT NULL,
  asset_no         VARCHAR(20) NOT NULL,                  -- FA-2026-0001
  tag              VARCHAR(30) NOT NULL,                  -- the number on the sticker
  name             VARCHAR(120) NOT NULL,
  category_id      BIGINT UNSIGNED NOT NULL,
  serial_no        VARCHAR(40) NULL,
  location         VARCHAR(80) NOT NULL,
  acquisition_date DATE NOT NULL,
  in_service_date  DATE NOT NULL,                         -- depreciation starts the month after
  cost             DECIMAL(17,2) NOT NULL,
  salvage_value    DECIMAL(17,2) NOT NULL DEFAULT 0,
  life_months      SMALLINT UNSIGNED NOT NULL,
  supplier_id      BIGINT UNSIGNED NULL,
  reference        VARCHAR(40) NULL,                      -- purchase order, invoice or deed number
  status           VARCHAR(10) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, DISPOSED
  disposed_on      DATE NULL,
  created_by       VARCHAR(60) NOT NULL,
  created_at       DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_asset_no (branch_code, asset_no),
  UNIQUE KEY ux_asset_tag (branch_code, tag),
  KEY ix_asset_cat (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_asset_depreciation (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  asset_id    BIGINT UNSIGNED NOT NULL,
  period_end  DATE NOT NULL,                              -- last day of the month depreciated
  amount      DECIMAL(17,2) NOT NULL,
  journal_id  BIGINT UNSIGNED NULL,                       -- the draft journal that books it
  created_by  VARCHAR(60) NOT NULL,
  created_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_asset_dep (asset_id, period_end),
  KEY ix_dep_journal (journal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_asset_transfer (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  transfer_no   VARCHAR(20) NOT NULL,                     -- AT-2026-0001
  asset_id      BIGINT UNSIGNED NOT NULL,
  from_location VARCHAR(80) NOT NULL,
  to_location   VARCHAR(80) NOT NULL,
  reason        VARCHAR(255) NOT NULL,
  status        VARCHAR(10) NOT NULL DEFAULT 'PENDING',   -- PENDING, APPROVED, REJECTED
  requested_by  VARCHAR(60) NOT NULL,
  requested_at  DATETIME NOT NULL,
  decided_by    VARCHAR(60) NULL,
  decided_at    DATETIME NULL,
  decision_note VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_transfer_no (branch_code, transfer_no),
  KEY ix_transfer_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_asset_maintenance (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  asset_id     BIGINT UNSIGNED NOT NULL,
  maint_date   DATE NOT NULL,
  description  VARCHAR(255) NOT NULL,
  cost         DECIMAL(17,2) NOT NULL DEFAULT 0,
  vendor       VARCHAR(80) NULL,
  next_due     DATE NULL,
  recorded_by  VARCHAR(60) NOT NULL,
  recorded_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ix_maint_asset (asset_id),
  KEY ix_maint_due (next_due)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_asset_disposal (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code      SMALLINT UNSIGNED NOT NULL,
  disposal_no      VARCHAR(20) NOT NULL,                  -- AD-2026-0001
  asset_id         BIGINT UNSIGNED NOT NULL,
  disposal_date    DATE NOT NULL,
  kind             VARCHAR(10) NOT NULL,                  -- SALE, SCRAP, DONATION, LOSS
  proceeds         DECIMAL(17,2) NOT NULL DEFAULT 0,
  proceeds_account VARCHAR(20) NULL,                      -- where the money or receivable goes
  gain_account     VARCHAR(20) NULL,                      -- needed when the disposal makes a gain
  book_value       DECIMAL(17,2) NOT NULL,                -- cost less depreciation booked when it was asked for
  gain_loss        DECIMAL(17,2) NOT NULL,                -- proceeds less book value
  reason           VARCHAR(255) NOT NULL,
  status           VARCHAR(10) NOT NULL DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
  requested_by     VARCHAR(60) NOT NULL,
  requested_at     DATETIME NOT NULL,
  decided_by       VARCHAR(60) NULL,
  decided_at       DATETIME NULL,
  decision_note    VARCHAR(255) NULL,
  journal_id       BIGINT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_disposal_no (branch_code, disposal_no),
  KEY ix_disposal_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_asset_category TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_asset TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_asset_depreciation TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_asset_transfer TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_asset_maintenance TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_asset_disposal TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_asset_category TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_asset TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_asset_depreciation TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_asset_transfer TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_asset_maintenance TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_asset_disposal TO 'coop_app'@'%';
