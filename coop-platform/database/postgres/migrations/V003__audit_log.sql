-- Append-only audit trail: one row per changed field.

CREATE TABLE core.audit_log (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor_user_id   bigint,
    action          text        NOT NULL,
    entity_type     text        NOT NULL,
    entity_id       text        NOT NULL,
    field_name      text,
    old_value       text,
    new_value       text,
    reason          text,
    reference_no    text,
    ip_address      inet,
    session_id      text,
    approved_by_user_id bigint,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_actor_user_id FOREIGN KEY (actor_user_id) REFERENCES core.app_user (id),
    CONSTRAINT fk_audit_log_approved_by_user_id FOREIGN KEY (approved_by_user_id) REFERENCES core.app_user (id),
    CONSTRAINT ck_audit_log_action CHECK (action ~ '^[A-Z][A-Z0-9_]*$')
);
CREATE INDEX ix_audit_log_entity ON core.audit_log (entity_type, entity_id, occurred_at);
CREATE INDEX ix_audit_log_actor_user_id ON core.audit_log (actor_user_id, occurred_at);
CREATE INDEX ix_audit_log_approved_by_user_id ON core.audit_log (approved_by_user_id);
CREATE INDEX ix_audit_log_reference_no ON core.audit_log (reference_no) WHERE reference_no IS NOT NULL;

COMMENT ON TABLE core.audit_log IS 'Who changed what, when and why. Never updated or deleted.';

CREATE FUNCTION core.forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on %.% is not allowed: the table is append-only', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER tr_audit_log_append_only
    BEFORE UPDATE OR DELETE ON core.audit_log
    FOR EACH ROW EXECUTE FUNCTION core.forbid_change();
CREATE TRIGGER tr_audit_log_no_truncate
    BEFORE TRUNCATE ON core.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION core.forbid_change();
