-- Member photos: a user granted the legacy flag AccessCanEditClientImages can take or upload a photo. Insert-only, like the rest: the newest 'img' row of a member is the one shown
-- and earlier photos stay in the table. Run by an administrator. Idempotent.
GRANT INSERT ON image TO 'coop_app'@'localhost';
GRANT INSERT ON image TO 'coop_app'@'%';
