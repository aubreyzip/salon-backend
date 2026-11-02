-- Salons (tenants), branches, staff memberships and invitations (ADR-0002, ADR-0008).
-- Tenant-owned child tables carry salon_id, and same-tenant references use composite foreign keys
-- against UNIQUE (salon_id, id), so the database itself rejects cross-salon references.

CREATE TABLE province
(
    id   INT  NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT province_pk PRIMARY KEY (id),
    CONSTRAINT province_name_uk UNIQUE (name),
    CONSTRAINT province_plate_code_ck CHECK (id BETWEEN 1 AND 81)
);

CREATE TABLE salon
(
    id                 UUID        NOT NULL,
    name               TEXT        NOT NULL,
    slug               TEXT        NOT NULL,
    description        TEXT,
    status             TEXT        NOT NULL,
    review_note        TEXT,
    submitted_at       TIMESTAMPTZ,
    approved_at        TIMESTAMPTZ,
    created_by_user_id UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL,
    CONSTRAINT salon_pk PRIMARY KEY (id),
    CONSTRAINT salon_slug_uk UNIQUE (slug),
    CONSTRAINT salon_created_by_fk FOREIGN KEY (created_by_user_id) REFERENCES user_account (id),
    CONSTRAINT salon_status_ck CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT salon_slug_format_ck CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE INDEX salon_status_idx ON salon (status, submitted_at);
CREATE INDEX salon_created_by_idx ON salon (created_by_user_id);

CREATE TABLE branch
(
    id                                   UUID             NOT NULL,
    salon_id                             UUID             NOT NULL,
    name                                 TEXT             NOT NULL,
    slug                                 TEXT             NOT NULL,
    phone                                TEXT,
    province_id                          INT              NOT NULL,
    district                             TEXT             NOT NULL,
    neighbourhood                        TEXT,
    address_line                         TEXT             NOT NULL,
    postal_code                          TEXT,
    latitude                             DOUBLE PRECISION,
    longitude                            DOUBLE PRECISION,
    time_zone                            TEXT             NOT NULL,
    status                               TEXT             NOT NULL,
    auto_confirm                         BOOLEAN          NOT NULL,
    slot_interval_minutes                INT              NOT NULL,
    min_notice_minutes                   INT              NOT NULL,
    max_advance_days                     INT              NOT NULL,
    cancellation_cutoff_minutes          INT              NOT NULL,
    pending_confirmation_timeout_minutes INT              NOT NULL,
    created_at                           TIMESTAMPTZ      NOT NULL,
    updated_at                           TIMESTAMPTZ      NOT NULL,
    version                              BIGINT           NOT NULL,
    CONSTRAINT branch_pk PRIMARY KEY (id),
    CONSTRAINT branch_salon_id_uk UNIQUE (salon_id, id),
    CONSTRAINT branch_salon_slug_uk UNIQUE (salon_id, slug),
    CONSTRAINT branch_salon_fk FOREIGN KEY (salon_id) REFERENCES salon (id),
    CONSTRAINT branch_province_fk FOREIGN KEY (province_id) REFERENCES province (id),
    CONSTRAINT branch_status_ck CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT branch_coordinates_ck CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT branch_latitude_ck CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT branch_longitude_ck CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT branch_slot_interval_ck CHECK (slot_interval_minutes IN (5, 10, 15, 20, 30, 60)),
    CONSTRAINT branch_min_notice_ck CHECK (min_notice_minutes BETWEEN 0 AND 10080),
    CONSTRAINT branch_max_advance_ck CHECK (max_advance_days BETWEEN 1 AND 365),
    CONSTRAINT branch_cancellation_cutoff_ck CHECK (cancellation_cutoff_minutes BETWEEN 0 AND 10080),
    CONSTRAINT branch_pending_timeout_ck CHECK (pending_confirmation_timeout_minutes BETWEEN 15 AND 2880)
);

CREATE INDEX branch_location_idx ON branch (province_id, district);

CREATE TABLE staff_member
(
    id           UUID        NOT NULL,
    salon_id     UUID        NOT NULL,
    user_id      UUID,
    display_name TEXT        NOT NULL,
    title        TEXT,
    bio          TEXT,
    role         TEXT        NOT NULL,
    status       TEXT        NOT NULL,
    bookable     BOOLEAN     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    version      BIGINT      NOT NULL,
    CONSTRAINT staff_member_pk PRIMARY KEY (id),
    CONSTRAINT staff_member_salon_id_uk UNIQUE (salon_id, id),
    CONSTRAINT staff_member_salon_fk FOREIGN KEY (salon_id) REFERENCES salon (id),
    CONSTRAINT staff_member_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT staff_member_role_ck CHECK (role IN ('OWNER', 'MANAGER', 'RECEPTIONIST', 'STAFF')),
    CONSTRAINT staff_member_status_ck CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- A user has at most one membership per salon.
CREATE UNIQUE INDEX staff_member_salon_user_uk ON staff_member (salon_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX staff_member_user_idx ON staff_member (user_id) WHERE user_id IS NOT NULL;

CREATE TABLE staff_branch
(
    staff_id  UUID NOT NULL,
    salon_id  UUID NOT NULL,
    branch_id UUID NOT NULL,
    CONSTRAINT staff_branch_pk PRIMARY KEY (staff_id, branch_id),
    CONSTRAINT staff_branch_staff_fk FOREIGN KEY (salon_id, staff_id) REFERENCES staff_member (salon_id, id)
        ON DELETE CASCADE,
    CONSTRAINT staff_branch_branch_fk FOREIGN KEY (salon_id, branch_id) REFERENCES branch (salon_id, id)
);

CREATE INDEX staff_branch_branch_idx ON staff_branch (branch_id);

CREATE TABLE staff_invitation
(
    id                  UUID        NOT NULL,
    salon_id            UUID        NOT NULL,
    staff_id            UUID        NOT NULL,
    email               TEXT        NOT NULL,
    token_hash          TEXT        NOT NULL,
    invited_by_user_id  UUID        NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    accepted_at         TIMESTAMPTZ,
    accepted_by_user_id UUID,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    version             BIGINT      NOT NULL,
    CONSTRAINT staff_invitation_pk PRIMARY KEY (id),
    CONSTRAINT staff_invitation_token_uk UNIQUE (token_hash),
    CONSTRAINT staff_invitation_staff_fk FOREIGN KEY (salon_id, staff_id) REFERENCES staff_member (salon_id, id)
        ON DELETE CASCADE,
    CONSTRAINT staff_invitation_invited_by_fk FOREIGN KEY (invited_by_user_id) REFERENCES user_account (id),
    CONSTRAINT staff_invitation_accepted_by_fk FOREIGN KEY (accepted_by_user_id) REFERENCES user_account (id),
    CONSTRAINT staff_invitation_acceptance_ck CHECK ((accepted_at IS NULL) = (accepted_by_user_id IS NULL))
);

CREATE INDEX staff_invitation_staff_idx ON staff_invitation (salon_id, staff_id);
