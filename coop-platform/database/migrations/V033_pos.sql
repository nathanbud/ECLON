-- Point of sale (guide phase 14): price list, discounts, promotions, sales with payments, and returns approved by a supervisor.
-- The old database's inv_* sales tables are empty, so these are new tables. A sale takes its stock out through the inventory ledger (StockService);
-- ledger entries for revenue and cost of sales are not written yet (they wait for the owner's account decisions), so every sale carries a `journaled` flag.
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_product_price (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  product_id BIGINT UNSIGNED NOT NULL,
  price      DECIMAL(17,2) NOT NULL,
  set_by     VARCHAR(60) NOT NULL,
  set_at     DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ix_price_product (product_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_discount (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code         SMALLINT UNSIGNED NOT NULL,
  code                VARCHAR(20) NOT NULL,
  name                VARCHAR(60) NOT NULL,
  kind                VARCHAR(8) NOT NULL,                 -- PERCENT or AMOUNT
  value               DECIMAL(17,2) NOT NULL,
  requires_supervisor TINYINT(1) NOT NULL DEFAULT 0,       -- only a POS supervisor may apply it
  active              TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY ux_discount (branch_code, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_promotion (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code SMALLINT UNSIGNED NOT NULL,
  name        VARCHAR(80) NOT NULL,
  product_id  BIGINT UNSIGNED NOT NULL,
  percent_off DECIMAL(5,2) NOT NULL,
  start_date  DATE NOT NULL,
  end_date    DATE NOT NULL,
  active      TINYINT(1) NOT NULL DEFAULT 1,
  created_by  VARCHAR(60) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_promo_product (product_id, start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_sale (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code     SMALLINT UNSIGNED NOT NULL,
  sale_no         VARCHAR(20) NOT NULL,                    -- SL-2026-0001, also the receipt number
  sale_date       DATE NOT NULL,
  sale_time       DATETIME NOT NULL,
  warehouse_id    BIGINT UNSIGNED NOT NULL,
  cashier         VARCHAR(60) NOT NULL,
  client_id       INT NULL,                                -- a member, when the sale is to one
  customer_name   VARCHAR(80) NULL,
  discount_id     BIGINT UNSIGNED NULL,
  subtotal        DECIMAL(17,2) NOT NULL,                  -- after promotions
  discount_total  DECIMAL(17,2) NOT NULL DEFAULT 0,
  total           DECIMAL(17,2) NOT NULL,
  paid            DECIMAL(17,2) NOT NULL,
  change_given    DECIMAL(17,2) NOT NULL DEFAULT 0,
  cost_total      DECIMAL(17,2) NOT NULL DEFAULT 0,        -- cost of the goods sold, from the stock ledger
  returned_total  DECIMAL(17,2) NOT NULL DEFAULT 0,
  journaled       TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY ux_sale_no (branch_code, sale_no),
  KEY ix_sale_date (branch_code, sale_date, cashier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_sale_line (
  sale_id      BIGINT UNSIGNED NOT NULL,
  line_no      SMALLINT UNSIGNED NOT NULL,
  product_id   BIGINT UNSIGNED NOT NULL,
  batch_id     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  quantity     DECIMAL(14,3) NOT NULL,
  unit_price   DECIMAL(17,2) NOT NULL,
  promo_id     BIGINT UNSIGNED NULL,
  promo_off    DECIMAL(17,2) NOT NULL DEFAULT 0,           -- promotion discount on this line
  line_total   DECIMAL(17,2) NOT NULL,                     -- quantity x price less the promotion
  unit_cost    DECIMAL(17,4) NOT NULL,
  qty_returned DECIMAL(14,3) NOT NULL DEFAULT 0,
  PRIMARY KEY (sale_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_sale_payment (
  sale_id   BIGINT UNSIGNED NOT NULL,
  line_no   SMALLINT UNSIGNED NOT NULL,
  method    VARCHAR(10) NOT NULL,                          -- CASH, CARD, OTHER
  amount    DECIMAL(17,2) NOT NULL,
  reference VARCHAR(40) NULL,
  PRIMARY KEY (sale_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_sale_return (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  return_no     VARCHAR(20) NOT NULL,                      -- RT-2026-0001
  sale_id       BIGINT UNSIGNED NOT NULL,
  reason        VARCHAR(255) NOT NULL,
  status        VARCHAR(10) NOT NULL DEFAULT 'PENDING',    -- PENDING, APPROVED, REJECTED
  refund        DECIMAL(17,2) NOT NULL,
  requested_by  VARCHAR(60) NOT NULL,
  requested_at  DATETIME NOT NULL,
  decided_by    VARCHAR(60) NULL,
  decided_at    DATETIME NULL,
  decision_note VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_return_no (branch_code, return_no),
  KEY ix_return_sale (sale_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_sale_return_line (
  return_id    BIGINT UNSIGNED NOT NULL,
  line_no      SMALLINT UNSIGNED NOT NULL,
  sale_line_no SMALLINT UNSIGNED NOT NULL,
  quantity     DECIMAL(14,3) NOT NULL,
  amount       DECIMAL(17,2) NOT NULL,
  PRIMARY KEY (return_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT ON ecis_product_price TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_discount TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_promotion TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_sale TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_sale_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_sale_payment TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_sale_return TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_sale_return_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_product_price TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_discount TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_promotion TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_sale TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_sale_line TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_sale_payment TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_sale_return TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_sale_return_line TO 'coop_app'@'%';
