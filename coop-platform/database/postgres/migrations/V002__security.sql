-- People, credentials, roles and permissions.

CREATE TABLE core.app_user (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    username          text        NOT NULL,
    display_name      text        NOT NULL,
    is_enabled        boolean     NOT NULL DEFAULT true,
    legacy_username   text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username)
);
COMMENT ON TABLE core.app_user IS 'A person who signs in. Created from the legacy access table (enabled users only).';
COMMENT ON COLUMN core.app_user.legacy_username IS 'Username in the legacy access table, for tracing.';

CREATE TABLE core.user_credential (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    app_user_id    bigint      NOT NULL,
    password_hash  text        NOT NULL,
    changed_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_credential PRIMARY KEY (id),
    CONSTRAINT uq_user_credential_app_user_id UNIQUE (app_user_id),
    CONSTRAINT fk_user_credential_app_user_id FOREIGN KEY (app_user_id) REFERENCES core.app_user (id)
);
COMMENT ON TABLE core.user_credential IS 'bcrypt password hash, one per user. Replaced by Keycloak later.';

CREATE TABLE core.role (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    code         text        NOT NULL,
    name         text        NOT NULL,
    description  text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uq_role_code UNIQUE (code),
    CONSTRAINT ck_role_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE core.permission (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    code         text        NOT NULL,
    description  text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_permission PRIMARY KEY (id),
    CONSTRAINT uq_permission_code UNIQUE (code),
    CONSTRAINT ck_permission_code_format CHECK (code ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$')
);
COMMENT ON COLUMN core.permission.code IS 'Dotted string, e.g. loan.application.approve.';

CREATE TABLE core.role_permission (
    role_id        bigint NOT NULL,
    permission_id  bigint NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role_id FOREIGN KEY (role_id) REFERENCES core.role (id),
    CONSTRAINT fk_role_permission_permission_id FOREIGN KEY (permission_id) REFERENCES core.permission (id)
);
CREATE INDEX ix_role_permission_permission_id ON core.role_permission (permission_id);

CREATE TABLE core.user_role (
    app_user_id  bigint      NOT NULL,
    role_id      bigint      NOT NULL,
    granted_at   timestamptz NOT NULL DEFAULT now(),
    granted_by_user_id bigint,
    CONSTRAINT pk_user_role PRIMARY KEY (app_user_id, role_id),
    CONSTRAINT fk_user_role_app_user_id FOREIGN KEY (app_user_id) REFERENCES core.app_user (id),
    CONSTRAINT fk_user_role_role_id FOREIGN KEY (role_id) REFERENCES core.role (id),
    CONSTRAINT fk_user_role_granted_by_user_id FOREIGN KEY (granted_by_user_id) REFERENCES core.app_user (id)
);
CREATE INDEX ix_user_role_role_id ON core.user_role (role_id);
CREATE INDEX ix_user_role_granted_by_user_id ON core.user_role (granted_by_user_id);
