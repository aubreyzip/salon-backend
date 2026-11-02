-- Append-only audit log (see AuditLog). No foreign keys: entries must survive deletion or
-- anonymization of the entities they describe.

CREATE TABLE audit_log
(
    id            UUID        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    actor_user_id UUID,
    salon_id      UUID,
    action        TEXT        NOT NULL,
    entity_type   TEXT,
    entity_id     TEXT,
    details       TEXT,
    client_ip     TEXT,
    user_agent    TEXT,
    CONSTRAINT audit_log_pk PRIMARY KEY (id)
);

CREATE INDEX audit_log_actor_idx ON audit_log (actor_user_id, occurred_at);
CREATE INDEX audit_log_salon_idx ON audit_log (salon_id, occurred_at);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id);
