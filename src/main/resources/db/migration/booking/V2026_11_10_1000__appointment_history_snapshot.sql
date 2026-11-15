-- ADR-0014 decision 56: a history entry is the immutable snapshot that notifications describe.
-- It gains the end time and, for reschedules, the slot before the change. Rows written before
-- this migration keep NULLs. Entries are append-only.
ALTER TABLE appointment_history
    ADD COLUMN end_at            TIMESTAMPTZ,
    ADD COLUMN previous_start_at TIMESTAMPTZ,
    ADD COLUMN previous_end_at   TIMESTAMPTZ,
    ADD COLUMN previous_staff_id UUID;

-- Enforce append-only in the database: an UPDATE would rewrite what notifications already said.
CREATE FUNCTION appointment_history_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'appointment_history is append-only' USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER appointment_history_no_update
    BEFORE UPDATE ON appointment_history
    FOR EACH ROW
EXECUTE FUNCTION appointment_history_append_only();
