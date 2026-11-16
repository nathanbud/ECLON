-- What the sign-in screen says about the cooperative: its name, a tagline and its logo. One row (id 1), changed only by a SUPERADMIN; read by anyone, including a person who has not signed in yet,
-- because it is shown on the sign-in screen. Run with scripts/migrate.ps1.
CREATE TABLE IF NOT EXISTS ecis_branding (
  id          TINYINT UNSIGNED NOT NULL,
  coop_name   VARCHAR(80) NULL,
  tagline     VARCHAR(140) NULL,
  logo        MEDIUMBLOB NULL,
  logo_type   VARCHAR(40) NULL,                 -- image/png, image/jpeg, image/webp or image/svg+xml
  updated_by  VARCHAR(60) NULL,
  updated_at  DATETIME NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT IGNORE INTO ecis_branding (id) VALUES (1);

GRANT SELECT, INSERT, UPDATE ON ecis_branding TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_branding TO 'coop_app'@'%';
