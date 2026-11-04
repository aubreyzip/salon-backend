-- Service catalog (ADR-0012): a platform-wide category taxonomy, salon-defined sections and
-- services, per-branch offerings with overrides, and staff skills.
-- Same-tenant references use composite foreign keys against UNIQUE (salon_id, id) (ADR-0002).

CREATE TABLE service_category
(
    id         INT  NOT NULL,
    code       TEXT NOT NULL,
    name       TEXT NOT NULL,
    sort_order INT  NOT NULL,
    CONSTRAINT service_category_pk PRIMARY KEY (id),
    CONSTRAINT service_category_code_uk UNIQUE (code),
    CONSTRAINT service_category_code_format_ck CHECK (code ~ '^[A-Z]+(_[A-Z]+)*$')
);

CREATE TABLE service_section
(
    id         UUID        NOT NULL,
    salon_id   UUID        NOT NULL,
    name       TEXT        NOT NULL,
    sort_order INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL,
    CONSTRAINT service_section_pk PRIMARY KEY (id),
    CONSTRAINT service_section_salon_id_uk UNIQUE (salon_id, id),
    CONSTRAINT service_section_salon_fk FOREIGN KEY (salon_id) REFERENCES salon (id)
);

CREATE TABLE service_item
(
    id               UUID          NOT NULL,
    salon_id         UUID          NOT NULL,
    category_id      INT           NOT NULL,
    section_id       UUID,
    name             TEXT          NOT NULL,
    description      TEXT,
    duration_minutes INT           NOT NULL,
    buffer_minutes   INT           NOT NULL,
    price            NUMERIC(12, 2) NOT NULL,
    currency         CHAR(3)       NOT NULL,
    price_from       BOOLEAN       NOT NULL,
    online_bookable  BOOLEAN       NOT NULL,
    status           TEXT          NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL,
    CONSTRAINT service_item_pk PRIMARY KEY (id),
    CONSTRAINT service_item_salon_id_uk UNIQUE (salon_id, id),
    CONSTRAINT service_item_salon_fk FOREIGN KEY (salon_id) REFERENCES salon (id),
    CONSTRAINT service_item_category_fk FOREIGN KEY (category_id) REFERENCES service_category (id),
    -- MATCH SIMPLE: a service without a section (section_id NULL) is not checked.
    CONSTRAINT service_item_section_fk FOREIGN KEY (salon_id, section_id) REFERENCES service_section (salon_id, id),
    CONSTRAINT service_item_duration_ck CHECK (duration_minutes BETWEEN 5 AND 720),
    CONSTRAINT service_item_buffer_ck CHECK (buffer_minutes BETWEEN 0 AND 240),
    CONSTRAINT service_item_price_ck CHECK (price >= 0),
    -- Only Turkish lira for now; relax when another market opens.
    CONSTRAINT service_item_currency_ck CHECK (currency = 'TRY'),
    CONSTRAINT service_item_status_ck CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX service_item_salon_idx ON service_item (salon_id, status);
CREATE INDEX service_item_category_idx ON service_item (category_id);
CREATE INDEX service_item_section_idx ON service_item (salon_id, section_id) WHERE section_id IS NOT NULL;

-- A service is bookable at a branch only through an offering row. NULL overrides fall back to the
-- service defaults.
CREATE TABLE service_offering
(
    id                        UUID           NOT NULL,
    salon_id                  UUID           NOT NULL,
    service_id                UUID           NOT NULL,
    branch_id                 UUID           NOT NULL,
    price_override            NUMERIC(12, 2),
    duration_override_minutes INT,
    buffer_override_minutes   INT,
    created_at                TIMESTAMPTZ    NOT NULL,
    updated_at                TIMESTAMPTZ    NOT NULL,
    version                   BIGINT         NOT NULL,
    CONSTRAINT service_offering_pk PRIMARY KEY (id),
    CONSTRAINT service_offering_service_branch_uk UNIQUE (service_id, branch_id),
    CONSTRAINT service_offering_service_fk FOREIGN KEY (salon_id, service_id) REFERENCES service_item (salon_id, id),
    CONSTRAINT service_offering_branch_fk FOREIGN KEY (salon_id, branch_id) REFERENCES branch (salon_id, id),
    CONSTRAINT service_offering_price_ck CHECK (price_override >= 0),
    CONSTRAINT service_offering_duration_ck CHECK (duration_override_minutes BETWEEN 5 AND 720),
    CONSTRAINT service_offering_buffer_ck CHECK (buffer_override_minutes BETWEEN 0 AND 240)
);

CREATE INDEX service_offering_salon_service_idx ON service_offering (salon_id, service_id);
CREATE INDEX service_offering_branch_idx ON service_offering (salon_id, branch_id);

-- Which staff member performs which service (explicit; no row means "cannot perform").
-- The duration override applies salon-wide and wins over branch and service durations.
CREATE TABLE staff_skill
(
    id                        UUID        NOT NULL,
    salon_id                  UUID        NOT NULL,
    staff_id                  UUID        NOT NULL,
    service_id                UUID        NOT NULL,
    duration_override_minutes INT,
    created_at                TIMESTAMPTZ NOT NULL,
    version                   BIGINT      NOT NULL,
    CONSTRAINT staff_skill_pk PRIMARY KEY (id),
    CONSTRAINT staff_skill_staff_service_uk UNIQUE (staff_id, service_id),
    CONSTRAINT staff_skill_staff_fk FOREIGN KEY (salon_id, staff_id) REFERENCES staff_member (salon_id, id),
    CONSTRAINT staff_skill_service_fk FOREIGN KEY (salon_id, service_id) REFERENCES service_item (salon_id, id),
    CONSTRAINT staff_skill_duration_ck CHECK (duration_override_minutes BETWEEN 5 AND 720)
);

CREATE INDEX staff_skill_salon_staff_idx ON staff_skill (salon_id, staff_id);
CREATE INDEX staff_skill_salon_service_idx ON staff_skill (salon_id, service_id);

-- Platform taxonomy used by public search (Phase 6). Ids are stable; add new rows, never renumber.
INSERT INTO service_category (id, code, name, sort_order)
VALUES (1, 'HAIRCUT', 'Saç Kesimi', 10),
       (2, 'HAIR_COLOR', 'Saç Boyama ve Renklendirme', 20),
       (3, 'HAIR_STYLING', 'Fön ve Şekillendirme', 30),
       (4, 'HAIR_TREATMENT', 'Saç Bakımı', 40),
       (5, 'BEARD_SHAVE', 'Sakal ve Tıraş', 50),
       (6, 'NAILS', 'Manikür ve Pedikür', 60),
       (7, 'SKIN_CARE', 'Cilt Bakımı', 70),
       (8, 'MAKEUP', 'Makyaj', 80),
       (9, 'BROWS_LASHES', 'Kaş ve Kirpik', 90),
       (10, 'HAIR_REMOVAL', 'Ağda ve Epilasyon', 100),
       (11, 'MASSAGE', 'Masaj', 110),
       (12, 'BRIDAL', 'Gelin ve Özel Gün', 120),
       (99, 'OTHER', 'Diğer', 999);
