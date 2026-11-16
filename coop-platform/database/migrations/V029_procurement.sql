-- Procurement (guide phase 12), stage 1: suppliers, purchase requests with approval, purchase orders with approval.
-- The old database has no purchasing data (inv_vendor holds one row, inv_posum and inv_podtl are empty), so these are new tables.
-- Receiving, supplier invoices, accounts payable and payments follow in later migrations. Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_supplier (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code  SMALLINT UNSIGNED NOT NULL,
  code         VARCHAR(20) NOT NULL,
  name         VARCHAR(120) NOT NULL,
  tin          VARCHAR(20) NULL,
  address      VARCHAR(200) NULL,
  phone        VARCHAR(40) NULL,
  email        VARCHAR(100) NULL,
  contact      VARCHAR(100) NULL,
  terms_days   SMALLINT UNSIGNED NOT NULL DEFAULT 0,   -- days to pay after the invoice
  taxable      TINYINT(1) NOT NULL DEFAULT 1,
  active       TINYINT(1) NOT NULL DEFAULT 1,
  created_by   VARCHAR(60) NOT NULL,
  created_at   DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_supplier_code (branch_code, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_purchase_request (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code  SMALLINT UNSIGNED NOT NULL,
  request_no   VARCHAR(20) NOT NULL,                    -- PR-2026-0001
  request_date DATE NOT NULL,
  needed_by    DATE NULL,
  purpose      VARCHAR(255) NOT NULL,
  status       VARCHAR(12) NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED, APPROVED, REJECTED, ORDERED, CANCELLED
  requested_by VARCHAR(60) NOT NULL,
  decided_by   VARCHAR(60) NULL,
  decided_at   DATETIME NULL,
  decision_note VARCHAR(255) NULL,
  created_at   DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_pr_no (branch_code, request_no),
  KEY ix_pr_status (branch_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_purchase_request_line (
  request_id  BIGINT UNSIGNED NOT NULL,
  line_no     SMALLINT UNSIGNED NOT NULL,
  description VARCHAR(200) NOT NULL,
  unit        VARCHAR(20) NOT NULL,
  quantity    DECIMAL(14,3) NOT NULL,
  est_cost    DECIMAL(17,2) NOT NULL,                   -- estimated cost per unit
  PRIMARY KEY (request_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_purchase_order (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  order_no      VARCHAR(20) NOT NULL,                   -- PO-2026-0001
  supplier_id   BIGINT UNSIGNED NOT NULL,
  request_id    BIGINT UNSIGNED NULL,                   -- the approved request it answers
  order_date    DATE NOT NULL,
  expected_date DATE NULL,
  terms_days    SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  remarks       VARCHAR(255) NULL,
  status        VARCHAR(12) NOT NULL DEFAULT 'DRAFT',   -- DRAFT, APPROVED, CANCELLED (RECEIVED, CLOSED come with receiving)
  total         DECIMAL(17,2) NOT NULL,
  created_by    VARCHAR(60) NOT NULL,
  approved_by   VARCHAR(60) NULL,
  approved_at   DATETIME NULL,
  created_at    DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_po_no (branch_code, order_no),
  KEY ix_po_supplier (supplier_id),
  KEY ix_po_request (request_id),
  KEY ix_po_status (branch_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_purchase_order_line (
  order_id    BIGINT UNSIGNED NOT NULL,
  line_no     SMALLINT UNSIGNED NOT NULL,
  description VARCHAR(200) NOT NULL,
  unit        VARCHAR(20) NOT NULL,
  quantity    DECIMAL(14,3) NOT NULL,
  unit_cost   DECIMAL(17,2) NOT NULL,
  qty_received DECIMAL(14,3) NOT NULL DEFAULT 0,
  PRIMARY KEY (order_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_supplier TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_purchase_request TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_purchase_request_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_purchase_order TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_purchase_order_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_supplier TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_purchase_request TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_purchase_request_line TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_purchase_order TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_purchase_order_line TO 'coop_app'@'%';
