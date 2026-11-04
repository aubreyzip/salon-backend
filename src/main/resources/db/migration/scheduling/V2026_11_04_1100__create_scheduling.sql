-- Scheduling (ADR-0012): branch opening hours, special days and closures; staff working hours and
-- time off. Weekly patterns are ISO day of week (1 = Monday) + local time in the branch's time zone;
-- one-off absences are instants (ADR-0006). No overnight intervals: start_time < end_time.

CREATE TABLE branch_hours
(
    id         UUID        NOT NULL,
    salon_id   UUID        NOT NULL,
    branch_id  UUID        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL,
    CONSTRAINT branch_hours_pk PRIMARY KEY (id),
    CONSTRAINT branch_hours_branch_uk UNIQUE (branch_id),
    CONSTRAINT branch_hours_branch_fk FOREIGN KEY (salon_id, branch_id) REFERENCES branch (salon_id, id)
);

CREATE INDEX branch_hours_salon_branch_idx ON branch_hours (salon_id, branch_id);

CREATE TABLE branch_hours_interval
(
    branch_hours_id UUID     NOT NULL,
    day_of_week     SMALLINT NOT NULL,
    start_time      TIME     NOT NULL,
    end_time        TIME     NOT NULL,
    CONSTRAINT branch_hours_interval_hours_fk FOREIGN KEY (branch_hours_id) REFERENCES branch_hours (id)
        ON DELETE CASCADE,
    CONSTRAINT branch_hours_interval_day_ck CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT branch_hours_interval_order_ck CHECK (start_time < end_time)
);

CREATE INDEX branch_hours_interval_hours_idx ON branch_hours_interval (branch_hours_id);

-- Hours for one date that replace the weekly opening hours (no intervals = closed that day).
CREATE TABLE branch_special_day
(
    id         UUID        NOT NULL,
    salon_id   UUID        NOT NULL,
    branch_id  UUID        NOT NULL,
    on_date    DATE        NOT NULL,
    note       TEXT,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL,
    CONSTRAINT branch_special_day_pk PRIMARY KEY (id),
    CONSTRAINT branch_special_day_branch_date_uk UNIQUE (branch_id, on_date),
    CONSTRAINT branch_special_day_branch_fk FOREIGN KEY (salon_id, branch_id) REFERENCES branch (salon_id, id)
);

CREATE INDEX branch_special_day_salon_branch_idx ON branch_special_day (salon_id, branch_id, on_date);

CREATE TABLE branch_special_day_interval
(
    special_day_id UUID NOT NULL,
    start_time     TIME NOT NULL,
    end_time       TIME NOT NULL,
    CONSTRAINT branch_special_day_interval_day_fk FOREIGN KEY (special_day_id) REFERENCES branch_special_day (id)
        ON DELETE CASCADE,
    CONSTRAINT branch_special_day_interval_order_ck CHECK (start_time < end_time)
);

CREATE INDEX branch_special_day_interval_day_idx ON branch_special_day_interval (special_day_id);

-- Whole days the branch is closed (renovation, holidays), inclusive on both ends.
CREATE TABLE branch_closure
(
    id         UUID        NOT NULL,
    salon_id   UUID        NOT NULL,
    branch_id  UUID        NOT NULL,
    start_date DATE        NOT NULL,
    end_date   DATE        NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL,
    CONSTRAINT branch_closure_pk PRIMARY KEY (id),
    CONSTRAINT branch_closure_branch_fk FOREIGN KEY (salon_id, branch_id) REFERENCES branch (salon_id, id),
    CONSTRAINT branch_closure_order_ck CHECK (start_date <= end_date)
);

CREATE INDEX branch_closure_salon_branch_idx ON branch_closure (salon_id, branch_id, end_date);

-- A staff member's weekly working hours at one branch, valid from effective_from to effective_to
-- (inclusive; NULL = open-ended). Periods of the same staff member and branch must not overlap,
-- and intervals in different branches must not overlap in time. Both are checked by the
-- application under the salon row lock.
CREATE TABLE staff_working_period
(
    id             UUID        NOT NULL,
    salon_id       UUID        NOT NULL,
    staff_id       UUID        NOT NULL,
    branch_id      UUID        NOT NULL,
    effective_from DATE        NOT NULL,
    effective_to   DATE,
    created_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL,
    CONSTRAINT staff_working_period_pk PRIMARY KEY (id),
    CONSTRAINT staff_working_period_staff_fk FOREIGN KEY (salon_id, staff_id) REFERENCES staff_member (salon_id, id),
    CONSTRAINT staff_working_period_branch_fk FOREIGN KEY (salon_id, branch_id) REFERENCES branch (salon_id, id),
    CONSTRAINT staff_working_period_order_ck CHECK (effective_to IS NULL OR effective_from <= effective_to)
);

CREATE INDEX staff_working_period_salon_staff_idx ON staff_working_period (salon_id, staff_id);
CREATE INDEX staff_working_period_salon_branch_idx ON staff_working_period (salon_id, branch_id);

CREATE TABLE staff_working_interval
(
    period_id   UUID     NOT NULL,
    day_of_week SMALLINT NOT NULL,
    start_time  TIME     NOT NULL,
    end_time    TIME     NOT NULL,
    CONSTRAINT staff_working_interval_period_fk FOREIGN KEY (period_id) REFERENCES staff_working_period (id)
        ON DELETE CASCADE,
    CONSTRAINT staff_working_interval_day_ck CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT staff_working_interval_order_ck CHECK (start_time < end_time)
);

CREATE INDEX staff_working_interval_period_idx ON staff_working_interval (period_id);

-- A staff member is away (full or partial days). Takes effect immediately; no approval workflow.
CREATE TABLE staff_time_off
(
    id                 UUID        NOT NULL,
    salon_id           UUID        NOT NULL,
    staff_id           UUID        NOT NULL,
    start_at           TIMESTAMPTZ NOT NULL,
    end_at             TIMESTAMPTZ NOT NULL,
    reason             TEXT,
    created_by_user_id UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL,
    CONSTRAINT staff_time_off_pk PRIMARY KEY (id),
    CONSTRAINT staff_time_off_staff_fk FOREIGN KEY (salon_id, staff_id) REFERENCES staff_member (salon_id, id),
    CONSTRAINT staff_time_off_created_by_fk FOREIGN KEY (created_by_user_id) REFERENCES user_account (id),
    CONSTRAINT staff_time_off_order_ck CHECK (start_at < end_at)
);

CREATE INDEX staff_time_off_salon_staff_idx ON staff_time_off (salon_id, staff_id, end_at);
CREATE INDEX staff_time_off_created_by_idx ON staff_time_off (created_by_user_id);
