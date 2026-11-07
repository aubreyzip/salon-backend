-- Booking (ADR-0005, ADR-0009, ADR-0012 decisions 21-26, ADR-0013): salon clients, appointments with
-- their service snapshot and history, the per-customer lock row and idempotency records.
-- Blocking statuses are PENDING and CONFIRMED; only they take part in the exclusion constraints.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- A salon's client: linked to a customer account (created on the customer's first booking there;
-- name and phone come from the account) or a walk-in / phone-booking record kept by the salon.
CREATE TABLE salon_client
(
    id                 UUID        NOT NULL,
    salon_id           UUID        NOT NULL,
    user_id            UUID,
    display_name       TEXT,
    phone_e164         TEXT,
    created_by_user_id UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL,
    CONSTRAINT salon_client_pk PRIMARY KEY (id),
    CONSTRAINT salon_client_salon_id_uk UNIQUE (salon_id, id),
    CONSTRAINT salon_client_salon_fk FOREIGN KEY (salon_id) REFERENCES salon (id),
    CONSTRAINT salon_client_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT salon_client_created_by_fk FOREIGN KEY (created_by_user_id) REFERENCES user_account (id),
    -- Walk-ins carry their own name and optional phone; linked clients use the account's.
    CONSTRAINT salon_client_details_ck CHECK (
        (user_id IS NULL AND display_name IS NOT NULL)
            OR (user_id IS NOT NULL AND display_name IS NULL AND phone_e164 IS NULL)),
    CONSTRAINT salon_client_name_ck CHECK (display_name IS NULL OR length(display_name) BETWEEN 1 AND 100)
);

CREATE UNIQUE INDEX salon_client_salon_user_uk ON salon_client (salon_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX salon_client_user_idx ON salon_client (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX salon_client_created_by_idx ON salon_client (created_by_user_id);
CREATE INDEX salon_client_salon_created_idx ON salon_client (salon_id, created_at);

CREATE TABLE appointment
(
    id                    UUID        NOT NULL,
    salon_id              UUID        NOT NULL,
    branch_id             UUID        NOT NULL,
    staff_id              UUID        NOT NULL,
    client_id             UUID        NOT NULL,
    -- The account-linked customer (copied from the client), for the customer-wide rules.
    customer_user_id      UUID,
    status                TEXT        NOT NULL,
    source                TEXT        NOT NULL,
    start_at              TIMESTAMPTZ NOT NULL,
    end_at                TIMESTAMPTZ NOT NULL,
    -- end_at plus the cleanup buffer: the staff member's calendar (ADR-0005).
    block_end_at          TIMESTAMPTZ NOT NULL,
    buffer_minutes        INT         NOT NULL,
    confirmation_deadline TIMESTAMPTZ,
    customer_note         TEXT,
    internal_note         TEXT,
    status_reason         TEXT,
    cancelled_by          TEXT,
    attention_reason      TEXT,
    attention_at          TIMESTAMPTZ,
    attention_by_user_id  UUID,
    created_by_user_id    UUID        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    version               BIGINT      NOT NULL,
    CONSTRAINT appointment_pk PRIMARY KEY (id),
    CONSTRAINT appointment_branch_fk FOREIGN KEY (salon_id, branch_id) REFERENCES branch (salon_id, id),
    CONSTRAINT appointment_staff_fk FOREIGN KEY (salon_id, staff_id) REFERENCES staff_member (salon_id, id),
    CONSTRAINT appointment_client_fk FOREIGN KEY (salon_id, client_id) REFERENCES salon_client (salon_id, id),
    CONSTRAINT appointment_customer_fk FOREIGN KEY (customer_user_id) REFERENCES user_account (id),
    CONSTRAINT appointment_attention_by_fk FOREIGN KEY (attention_by_user_id) REFERENCES user_account (id),
    CONSTRAINT appointment_created_by_fk FOREIGN KEY (created_by_user_id) REFERENCES user_account (id),
    CONSTRAINT appointment_status_ck CHECK (status IN
                                            ('PENDING', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'COMPLETED',
                                             'NO_SHOW')),
    CONSTRAINT appointment_source_ck CHECK (source IN ('ONLINE', 'SALON')),
    CONSTRAINT appointment_times_ck CHECK (start_at < end_at AND end_at <= block_end_at),
    CONSTRAINT appointment_buffer_ck CHECK (buffer_minutes BETWEEN 0 AND 240),
    CONSTRAINT appointment_pending_deadline_ck CHECK (status <> 'PENDING' OR confirmation_deadline IS NOT NULL),
    CONSTRAINT appointment_cancelled_by_ck CHECK ((status = 'CANCELLED') = (cancelled_by IS NOT NULL)),
    CONSTRAINT appointment_cancelled_by_value_ck CHECK (cancelled_by IN ('CUSTOMER', 'SALON')),
    CONSTRAINT appointment_attention_ck CHECK ((attention_reason IS NULL) = (attention_at IS NULL)),
    CONSTRAINT appointment_customer_note_ck CHECK (length(customer_note) <= 500),
    CONSTRAINT appointment_internal_note_ck CHECK (length(internal_note) <= 1000),
    -- ADR-0005: no overlapping blocking appointments per staff member, buffer included. Half-open
    -- ranges allow back-to-back appointments.
    CONSTRAINT appointment_no_staff_overlap EXCLUDE USING gist (
        staff_id WITH =,
        tstzrange(start_at, block_end_at, '[)') WITH &&
        ) WHERE (status IN ('PENDING', 'CONFIRMED')),
    -- ADR-0012 decision 23: no overlapping blocking appointments per account-linked customer,
    -- platform-wide (no salon in the key), without the staff buffer.
    CONSTRAINT appointment_no_customer_overlap EXCLUDE USING gist (
        customer_user_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
        ) WHERE (status IN ('PENDING', 'CONFIRMED') AND customer_user_id IS NOT NULL)
);

CREATE INDEX appointment_salon_branch_start_idx ON appointment (salon_id, branch_id, start_at);
CREATE INDEX appointment_salon_staff_idx ON appointment (salon_id, staff_id, block_end_at);
CREATE INDEX appointment_staff_idx ON appointment (staff_id);
CREATE INDEX appointment_salon_client_idx ON appointment (salon_id, client_id);
CREATE INDEX appointment_customer_start_idx ON appointment (customer_user_id, start_at)
    WHERE customer_user_id IS NOT NULL;
CREATE INDEX appointment_pending_deadline_idx ON appointment (confirmation_deadline) WHERE status = 'PENDING';
CREATE INDEX appointment_attention_by_idx ON appointment (attention_by_user_id) WHERE attention_by_user_id IS NOT NULL;
CREATE INDEX appointment_created_by_idx ON appointment (created_by_user_id);

-- The booked services in order, with price and duration as booked (snapshot, ADR-0012).
CREATE TABLE appointment_item
(
    appointment_id   UUID           NOT NULL,
    position         INT            NOT NULL,
    service_id       UUID           NOT NULL,
    service_name     TEXT           NOT NULL,
    duration_minutes INT            NOT NULL,
    price            NUMERIC(12, 2) NOT NULL,
    currency         CHAR(3)        NOT NULL,
    price_from       BOOLEAN        NOT NULL,
    CONSTRAINT appointment_item_pk PRIMARY KEY (appointment_id, position),
    CONSTRAINT appointment_item_appointment_fk FOREIGN KEY (appointment_id) REFERENCES appointment (id)
        ON DELETE CASCADE,
    CONSTRAINT appointment_item_service_fk FOREIGN KEY (service_id) REFERENCES service_item (id),
    CONSTRAINT appointment_item_duration_ck CHECK (duration_minutes > 0),
    CONSTRAINT appointment_item_price_ck CHECK (price >= 0),
    CONSTRAINT appointment_item_currency_ck CHECK (currency = 'TRY')
);

CREATE INDEX appointment_item_service_idx ON appointment_item (service_id);

-- Every status change, reschedule and attention flag (ADR-0009).
CREATE TABLE appointment_history
(
    id             UUID        NOT NULL,
    appointment_id UUID        NOT NULL,
    salon_id       UUID        NOT NULL,
    action         TEXT        NOT NULL,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    actor_user_id  UUID,
    reason         TEXT,
    start_at       TIMESTAMPTZ,
    staff_id       UUID,
    occurred_at    TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL,
    CONSTRAINT appointment_history_pk PRIMARY KEY (id),
    CONSTRAINT appointment_history_appointment_fk FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    CONSTRAINT appointment_history_actor_fk FOREIGN KEY (actor_user_id) REFERENCES user_account (id)
);

CREATE INDEX appointment_history_appointment_idx ON appointment_history (appointment_id, occurred_at);
CREATE INDEX appointment_history_actor_idx ON appointment_history (actor_user_id) WHERE actor_user_id IS NOT NULL;

-- One row per customer, locked FOR UPDATE by every booking for that customer, so the limit of
-- active appointments (decision 24) is counted by one transaction at a time.
CREATE TABLE booking_customer_lock
(
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT booking_customer_lock_pk PRIMARY KEY (user_id),
    CONSTRAINT booking_customer_lock_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id)
);

-- Idempotency-Key per user (ADR-0013, decision 41). The row is inserted first in the booking
-- transaction; a concurrent retry with the same key waits on the unique index and then replays.
CREATE TABLE idempotency_record
(
    id              UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    scope           TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    appointment_id  UUID,
    response_status INT,
    created_at      TIMESTAMPTZ NOT NULL,
    version         BIGINT      NOT NULL,
    CONSTRAINT idempotency_record_pk PRIMARY KEY (id),
    CONSTRAINT idempotency_record_user_key_uk UNIQUE (user_id, idempotency_key),
    CONSTRAINT idempotency_record_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT idempotency_record_appointment_fk FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    CONSTRAINT idempotency_record_key_ck CHECK (length(idempotency_key) BETWEEN 1 AND 100)
);

CREATE INDEX idempotency_record_created_idx ON idempotency_record (created_at);
CREATE INDEX idempotency_record_appointment_idx ON idempotency_record (appointment_id)
    WHERE appointment_id IS NOT NULL;
