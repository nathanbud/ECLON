-- Workflow engine (guide phase 17): configurable approval chains with approval limits. Every module keeps its own usual approval (the "first step", done by the person the
-- module already allows). A workflow adds EXTRA approvals after it, each by a named role and only when the amount reaches that step's limit, for example: a purchase order
-- of 100,000 or more also needs the Accountant, and one of 1,000,000 or more also needs the Manager. With no workflow configured nothing changes.
-- The steps an item needs are copied into the item (steps_json) when its first approval happens, so changing the configuration later never changes an item already under way.
-- Actions are never edited. Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_workflow (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code SMALLINT UNSIGNED NOT NULL,
  entity_type VARCHAR(30) NOT NULL,                     -- PURCHASE_ORDER, PURCHASE_REQUEST, STOCK_ADJUSTMENT ...
  active      TINYINT(1) NOT NULL DEFAULT 0,
  updated_by  VARCHAR(60) NOT NULL,
  updated_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_workflow (branch_code, entity_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_workflow_step (
  workflow_id BIGINT UNSIGNED NOT NULL,
  step_no     SMALLINT UNSIGNED NOT NULL,               -- 1, 2 ... the order the extra approvals happen in
  name        VARCHAR(60) NOT NULL,
  role        VARCHAR(30) NOT NULL,                     -- who may approve this step (without ROLE_)
  min_amount  DECIMAL(17,2) NOT NULL DEFAULT 0,         -- the step applies when the item's amount is at least this
  PRIMARY KEY (workflow_id, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_workflow_instance (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code     SMALLINT UNSIGNED NOT NULL,
  entity_type     VARCHAR(30) NOT NULL,
  entity_id       VARCHAR(40) NOT NULL,
  amount          DECIMAL(17,2) NOT NULL,
  requested_by    VARCHAR(60) NOT NULL,                 -- who made the item
  steps_json      TEXT NOT NULL,                        -- the extra steps this item needs: [{"no":1,"name":"...","role":"..."}]
  current_step    SMALLINT UNSIGNED NOT NULL,           -- index into steps_json of the step waiting to be done
  status          VARCHAR(12) NOT NULL DEFAULT 'WAITING', -- WAITING, APPROVED, REJECTED, CANCELLED
  created_at      DATETIME NOT NULL,
  closed_at       DATETIME NULL,
  PRIMARY KEY (id),
  KEY ix_wf_entity (branch_code, entity_type, entity_id, status),
  KEY ix_wf_status (branch_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_workflow_action (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  instance_id BIGINT UNSIGNED NOT NULL,
  step_no     SMALLINT UNSIGNED NOT NULL,               -- 0 is the module's own usual approval
  step_name   VARCHAR(60) NOT NULL,
  actor       VARCHAR(60) NOT NULL,
  decision    VARCHAR(10) NOT NULL,                     -- APPROVED, REJECTED, CANCELLED
  note        VARCHAR(255) NULL,
  at          DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ix_wfa_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_workflow TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_workflow_step TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_workflow_instance TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_workflow_action TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_workflow TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_workflow_step TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_workflow_instance TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_workflow_action TO 'coop_app'@'%';
