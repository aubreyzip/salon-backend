-- Persistent in-app notifications (ADR-0014 decision 45: the source of truth for every message).
-- A row records the facts of the change it announces, copied from the immutable source (for
-- appointments: the appointment_history entry, decision 56). Text is rendered from templates when
-- read (decision 51), so a later appointment change never rewrites an earlier notification.
CREATE TABLE notification
(
    id                UUID        NOT NULL,
    user_id           UUID        NOT NULL,
    kind              TEXT        NOT NULL,
    -- The change that caused it, e.g. an appointment_history id. Unique with recipient and kind,
    -- so a redelivered event creates nothing twice.
    source_id         UUID        NOT NULL,
    salon_id          UUID,
    branch_id         UUID,
    appointment_id    UUID,
    start_at          TIMESTAMPTZ,
    previous_start_at TIMESTAMPTZ,
    reason            TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    read_at           TIMESTAMPTZ,
    version           BIGINT      NOT NULL,
    CONSTRAINT notification_pk PRIMARY KEY (id),
    CONSTRAINT notification_source_uq UNIQUE (source_id, user_id, kind),
    CONSTRAINT notification_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT notification_salon_fk FOREIGN KEY (salon_id) REFERENCES salon (id),
    CONSTRAINT notification_branch_fk FOREIGN KEY (salon_id, branch_id) REFERENCES branch (salon_id, id),
    CONSTRAINT notification_appointment_fk FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    CONSTRAINT notification_kind_ck CHECK (kind IN (
        'BOOKING_REQUESTED', 'BOOKING_CONFIRMED', 'BOOKED_BY_SALON', 'BOOKING_REJECTED', 'BOOKING_EXPIRED',
        'CANCELLED_BY_YOU', 'CANCELLED_BY_SALON', 'RESCHEDULED_BY_YOU', 'RESCHEDULED_BY_SALON',
        'STAFF_NEW_APPOINTMENT', 'STAFF_APPOINTMENT_CANCELLED', 'STAFF_APPOINTMENT_RESCHEDULED',
        'STAFF_APPOINTMENT_REASSIGNED', 'DESK_CONFIRMATION_NEEDED', 'DESK_NEEDS_ATTENTION')),
    CONSTRAINT notification_branch_ck CHECK (branch_id IS NULL OR salon_id IS NOT NULL),
    CONSTRAINT notification_reason_ck CHECK (reason IS NULL OR length(reason) <= 500)
);

-- The user's list, newest first, and the unread count.
CREATE INDEX notification_user_idx ON notification (user_id, created_at DESC);
CREATE INDEX notification_unread_idx ON notification (user_id) WHERE read_at IS NULL;
CREATE INDEX notification_salon_idx ON notification (salon_id, branch_id) WHERE salon_id IS NOT NULL;
CREATE INDEX notification_appointment_idx ON notification (appointment_id) WHERE appointment_id IS NOT NULL;
-- Retention (decision 65: 12 months).
CREATE INDEX notification_created_idx ON notification (created_at);
