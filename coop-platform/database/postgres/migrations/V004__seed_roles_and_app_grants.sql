-- Roles the app already uses, and what the limited application user may do.

INSERT INTO core.role (code, name, description) VALUES
    ('ADMIN',              'Administrator',        'Manages users, roles and settings'),
    ('MEMBERSHIP_OFFICER', 'Membership Officer',   'Approves member profile changes'),
    ('ACCOUNTANT',         'Accountant',           'Journals, periods, statements and bank reconciliation');

-- The application connects as coop_app (created by hand with its own password, see infrastructure/docker/README).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'coop_app') THEN
        GRANT USAGE ON SCHEMA core, legacy TO coop_app;
        GRANT SELECT ON ALL TABLES IN SCHEMA legacy TO coop_app;
        GRANT SELECT, INSERT, UPDATE ON core.app_user, core.user_credential TO coop_app;
        GRANT SELECT, INSERT, DELETE ON core.user_role TO coop_app;
        GRANT SELECT ON core.role, core.permission, core.role_permission TO coop_app;
        GRANT SELECT, INSERT ON core.audit_log TO coop_app;
        GRANT USAGE ON ALL SEQUENCES IN SCHEMA core TO coop_app;
    END IF;
END
$$;
