-- Identity: user accounts, platform roles, refresh tokens and one-time verification codes
-- (ADR-0003, ADR-0007).

CREATE TABLE user_account
(
    id                    UUID        NOT NULL,
    email                 TEXT        NOT NULL,
    email_verified_at     TIMESTAMPTZ,
    phone_e164            TEXT,
    phone_verified_at     TIMESTAMPTZ,
    password_hash         TEXT        NOT NULL,
    display_name          TEXT        NOT NULL,
    status                TEXT        NOT NULL,
    failed_login_attempts INT         NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    version               BIGINT      NOT NULL,
    CONSTRAINT user_account_pk PRIMARY KEY (id),
    CONSTRAINT user_account_email_uk UNIQUE (email),
    CONSTRAINT user_account_status_ck CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT user_account_phone_format_ck CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT user_account_failed_logins_ck CHECK (failed_login_attempts >= 0)
);

-- A phone number can be verified by one account only.
CREATE UNIQUE INDEX user_account_verified_phone_uk ON user_account (phone_e164) WHERE phone_verified_at IS NOT NULL;

CREATE TABLE user_platform_role
(
    user_id UUID NOT NULL,
    role    TEXT NOT NULL,
    CONSTRAINT user_platform_role_pk PRIMARY KEY (user_id, role),
    CONSTRAINT user_platform_role_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT user_platform_role_role_ck CHECK (role IN ('PLATFORM_ADMIN', 'PLATFORM_SUPPORT'))
);

CREATE TABLE refresh_token
(
    id            UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    family_id     UUID        NOT NULL,
    token_hash    TEXT        NOT NULL,
    issued_at     TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    revoke_reason TEXT,
    version       BIGINT      NOT NULL,
    CONSTRAINT refresh_token_pk PRIMARY KEY (id),
    CONSTRAINT refresh_token_hash_uk UNIQUE (token_hash),
    CONSTRAINT refresh_token_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT refresh_token_expiry_ck CHECK (expires_at > issued_at),
    CONSTRAINT refresh_token_revoke_reason_ck CHECK (
        revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_CHANGED')),
    CONSTRAINT refresh_token_revocation_ck CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);

CREATE INDEX refresh_token_user_idx ON refresh_token (user_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);

CREATE TABLE verification_challenge
(
    id          UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    purpose     TEXT        NOT NULL,
    channel     TEXT        NOT NULL,
    target      TEXT        NOT NULL,
    code_hash   TEXT        NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL,
    CONSTRAINT verification_challenge_pk PRIMARY KEY (id),
    CONSTRAINT verification_challenge_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT verification_challenge_purpose_ck CHECK (
        purpose IN ('EMAIL_VERIFICATION', 'PHONE_VERIFICATION', 'PASSWORD_RESET', 'LOGIN_OTP')),
    CONSTRAINT verification_challenge_channel_ck CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT verification_challenge_attempts_ck CHECK (attempts >= 0)
);

CREATE INDEX verification_challenge_user_purpose_idx ON verification_challenge (user_id, purpose, created_at DESC);
CREATE INDEX verification_challenge_target_idx ON verification_challenge (target, created_at);
