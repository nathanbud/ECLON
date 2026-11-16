-- A new-member registration saved as a draft before it is sent for approval. Each draft belongs to the person who typed it and only they see it. It holds the typed fields only
-- (no photo, signature or fingerprints) and is deleted when the registration is sent or the draft is discarded. The PostgreSQL copy is V019. Run with scripts/migrate.ps1. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_member_draft (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  owner       VARCHAR(60) NOT NULL,
  branch_code INT NOT NULL,
  client_type INT NOT NULL,
  title       VARCHAR(120) NOT NULL,
  fields      TEXT NOT NULL,
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ecis_member_draft_owner (owner, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_member_draft TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_member_draft TO 'coop_app'@'%';
