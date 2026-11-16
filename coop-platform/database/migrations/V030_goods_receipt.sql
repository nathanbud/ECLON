-- Procurement stage 2: goods receipts against approved purchase orders. A receipt is never edited; a mistake is reversed (status REVERSED, reason,
-- the quantities come back off the order) and a new receipt made. Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_goods_receipt (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  receipt_no    VARCHAR(20) NOT NULL,                    -- GR-2026-0001
  order_id      BIGINT UNSIGNED NOT NULL,
  receipt_date  DATE NOT NULL,
  delivery_note VARCHAR(40) NULL,                        -- the supplier's delivery receipt number
  remarks       VARCHAR(255) NULL,
  status        VARCHAR(10) NOT NULL DEFAULT 'RECEIVED', -- RECEIVED, REVERSED
  received_by   VARCHAR(60) NOT NULL,
  created_at    DATETIME NOT NULL,
  reversed_by   VARCHAR(60) NULL,
  reversed_at   DATETIME NULL,
  reverse_note  VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_gr_no (branch_code, receipt_no),
  KEY ix_gr_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_goods_receipt_line (
  receipt_id    BIGINT UNSIGNED NOT NULL,
  line_no       SMALLINT UNSIGNED NOT NULL,
  order_line_no SMALLINT UNSIGNED NOT NULL,
  quantity      DECIMAL(14,3) NOT NULL,
  PRIMARY KEY (receipt_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_goods_receipt TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_goods_receipt_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_goods_receipt TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_goods_receipt_line TO 'coop_app'@'%';
