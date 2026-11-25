-- Plano de identidade. Nenhuma tabela daqui é referenciada pela telemetria.
CREATE TABLE accounts (
    account_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handle            TEXT NOT NULL,
    password_hash     TEXT NOT NULL,
    phone_e164        TEXT NOT NULL,
    email             TEXT NOT NULL,
    phone_verified_at TIMESTAMPTZ,
    email_verified_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (handle ~ '^@[a-zA-Z0-9_]{3,20}$')
);
CREATE UNIQUE INDEX accounts_handle_uq ON accounts (lower(handle));
CREATE UNIQUE INDEX accounts_phone_uq ON accounts (phone_e164);
CREATE UNIQUE INDEX accounts_email_uq ON accounts (lower(email));

-- Refresh rotativo: reuso de um token já trocado revoga a família inteira.
CREATE TABLE refresh_tokens (
    token_hash  TEXT PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES accounts ON DELETE CASCADE,
    family_id   UUID NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    replaced_at TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
