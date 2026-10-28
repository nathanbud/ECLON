-- Photo, consent and fingerprints taken while a new member is being registered, before the member exists. They are held against the registration request (the fingerprint templates
-- encrypted, bound to the request) and moved into the member's photo and fingerprint records when a Membership Officer approves; a rejected or withdrawn request has them wiped.
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_member_request_item (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  request_id  BIGINT UNSIGNED NOT NULL,
  kind        VARCHAR(8) NOT NULL,                   -- PHOTO, CONSENT, FINGER
  finger      TINYINT UNSIGNED NULL,
  format      VARCHAR(12) NULL,
  quality     TINYINT UNSIGNED NULL,
  device      VARCHAR(60) NULL,
  data        MEDIUMBLOB NULL,                       -- the photo, or the sealed template; NULL once released or discarded
  status      VARCHAR(10) NOT NULL DEFAULT 'HELD',   -- HELD, RELEASED, DISCARDED
  created_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ix_mri_request (request_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_member_request_item TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_member_request_item TO 'coop_app'@'%';
