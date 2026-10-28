-- Inventory (guide phase 13): products, warehouses, batches, balances, an insert-only transaction ledger, stock adjustments and stock counts.
-- The old database's inv_* tables are empty, so these are new tables. The ledger (ecis_inventory_txn) is never updated or deleted: every change of stock is
-- a row there, and ecis_inventory_balance is only ever changed together with a ledger row. Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_unit (
  code VARCHAR(10) NOT NULL,
  name VARCHAR(40) NOT NULL,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT IGNORE INTO ecis_unit (code, name) VALUES ('pc', 'Piece'), ('box', 'Box'), ('pack', 'Pack'), ('ream', 'Ream'), ('kg', 'Kilogram'), ('g', 'Gram'),
  ('l', 'Liter'), ('ml', 'Milliliter'), ('m', 'Meter'), ('set', 'Set'), ('doz', 'Dozen'), ('sack', 'Sack'), ('can', 'Can'), ('btl', 'Bottle');

CREATE TABLE IF NOT EXISTS ecis_product_category (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code SMALLINT UNSIGNED NOT NULL,
  name        VARCHAR(60) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_pcat (branch_code, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_warehouse (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code SMALLINT UNSIGNED NOT NULL,
  code        VARCHAR(20) NOT NULL,
  name        VARCHAR(80) NOT NULL,
  active      TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY ux_wh (branch_code, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_product (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  sku           VARCHAR(30) NOT NULL,
  name          VARCHAR(120) NOT NULL,
  category_id   BIGINT UNSIGNED NULL,
  unit          VARCHAR(10) NOT NULL,
  cost          DECIMAL(17,4) NOT NULL DEFAULT 0,        -- moving average cost per unit, kept up to date by receipts
  reorder_level DECIMAL(14,3) NOT NULL DEFAULT 0,
  track_batch   TINYINT(1) NOT NULL DEFAULT 0,
  track_expiry  TINYINT(1) NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_by    VARCHAR(60) NOT NULL,
  created_at    DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_product_sku (branch_code, sku),
  KEY ix_product_cat (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_inventory_batch (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  product_id  BIGINT UNSIGNED NOT NULL,
  batch_no    VARCHAR(30) NOT NULL,
  expiry_date DATE NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_batch (product_id, batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- batch_id 0 means "no batch"
CREATE TABLE IF NOT EXISTS ecis_inventory_balance (
  product_id   BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  batch_id     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  quantity     DECIMAL(14,3) NOT NULL,
  PRIMARY KEY (product_id, warehouse_id, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- the ledger: quantity is signed (plus in, minus out); rows are never changed
CREATE TABLE IF NOT EXISTS ecis_inventory_txn (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code  SMALLINT UNSIGNED NOT NULL,
  txn_no       VARCHAR(20) NOT NULL,                     -- INV-2026-000001
  txn_date     DATE NOT NULL,
  txn_type     VARCHAR(12) NOT NULL,                     -- RECEIVE, ISSUE, SALE, RETURN, TRANSFER, ADJUSTMENT, DAMAGE, EXPIRY
  product_id   BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  batch_id     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  quantity     DECIMAL(14,3) NOT NULL,
  unit_cost    DECIMAL(17,4) NOT NULL,
  ref_type     VARCHAR(20) NULL,                         -- ADJUSTMENT, COUNT, TRANSFER, RECEIPT ...
  ref_no       VARCHAR(30) NULL,
  remarks      VARCHAR(255) NULL,
  created_by   VARCHAR(60) NOT NULL,
  created_at   DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_txn_no (branch_code, txn_no),
  KEY ix_txn_product (product_id, warehouse_id, txn_date),
  KEY ix_txn_ref (ref_type, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_stock_adjustment (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  adjust_no     VARCHAR(20) NOT NULL,                    -- SA-2026-0001
  warehouse_id  BIGINT UNSIGNED NOT NULL,
  adj_type      VARCHAR(12) NOT NULL,                    -- ADJUSTMENT, DAMAGE, EXPIRY
  reason        VARCHAR(255) NOT NULL,
  status        VARCHAR(10) NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED, APPROVED, REJECTED
  requested_by  VARCHAR(60) NOT NULL,
  decided_by    VARCHAR(60) NULL,
  decided_at    DATETIME NULL,
  decision_note VARCHAR(255) NULL,
  created_at    DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_sa_no (branch_code, adjust_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_stock_adjustment_line (
  adjustment_id BIGINT UNSIGNED NOT NULL,
  line_no       SMALLINT UNSIGNED NOT NULL,
  product_id    BIGINT UNSIGNED NOT NULL,
  batch_id      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  quantity      DECIMAL(14,3) NOT NULL,                  -- signed effect on stock
  PRIMARY KEY (adjustment_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_stock_count (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  count_no      VARCHAR(20) NOT NULL,                    -- SC-2026-0001
  warehouse_id  BIGINT UNSIGNED NOT NULL,
  count_date    DATE NOT NULL,
  status        VARCHAR(10) NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED, APPROVED, REJECTED
  counted_by    VARCHAR(60) NOT NULL,
  decided_by    VARCHAR(60) NULL,
  decided_at    DATETIME NULL,
  decision_note VARCHAR(255) NULL,
  created_at    DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_sc_no (branch_code, count_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_stock_count_line (
  count_id    BIGINT UNSIGNED NOT NULL,
  line_no     SMALLINT UNSIGNED NOT NULL,
  product_id  BIGINT UNSIGNED NOT NULL,
  batch_id    BIGINT UNSIGNED NOT NULL DEFAULT 0,
  system_qty  DECIMAL(14,3) NOT NULL,                    -- what the books showed when the count was entered
  counted_qty DECIMAL(14,3) NOT NULL,
  PRIMARY KEY (count_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT ON ecis_unit TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_product_category TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_warehouse TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_product TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_inventory_batch TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_inventory_balance TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_inventory_txn TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_stock_adjustment TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_stock_adjustment_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_stock_count TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_stock_count_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_unit TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_product_category TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_warehouse TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_product TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_inventory_batch TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_inventory_balance TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_inventory_txn TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_stock_adjustment TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_stock_adjustment_line TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_stock_count TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_stock_count_line TO 'coop_app'@'%';
