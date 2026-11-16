-- Temporary passwords. An administrator issues one; the person must replace it at first sign-in and it stops working after expires_at.
-- must_change = 1 marks a temporary password. A password the person chose has must_change = 0 and no expiry.
-- Run by an administrator. Not repeatable (ADD COLUMN); run once.
ALTER TABLE ecis_user_credential
  ADD COLUMN must_change TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN expires_at  DATETIME NULL;
