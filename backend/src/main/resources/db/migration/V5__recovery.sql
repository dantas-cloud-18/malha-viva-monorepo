-- Fase 4.2: recuperacao de conta. V3 e V4 ficam intactos.

-- Contato nao verificado pode repetir (evita revelar existencia no cadastro); unico so depois de verificado.
DROP INDEX accounts_phone_uq;
DROP INDEX accounts_email_uq;
CREATE UNIQUE INDEX accounts_phone_verified_uq ON accounts (phone_e164) WHERE phone_verified_at IS NOT NULL;
CREATE UNIQUE INDEX accounts_email_verified_uq ON accounts (lower(email)) WHERE email_verified_at IS NOT NULL;

-- OTP: so o HMAC-SHA256 do codigo (chave MALHAVIVA_OTP_KEY), nunca o codigo.
CREATE TABLE otp_codes (
    otp_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES accounts ON DELETE CASCADE,
    purpose     TEXT NOT NULL CHECK (purpose IN ('VERIFY_CONTACT', 'RECOVERY', 'CONTACT_CHANGE')),
    channel     TEXT NOT NULL CHECK (channel IN ('PHONE', 'EMAIL')),
    code_hmac   TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    attempts    INT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX otp_codes_lookup_idx ON otp_codes (account_id, purpose, channel, created_at DESC);

-- Fila de troca de contato: aplicada so apos effective_at (24 h) e com o OTP do novo canal confirmado.
CREATE TABLE contact_changes (
    change_id    UUID PRIMARY KEY,
    account_id   UUID NOT NULL REFERENCES accounts ON DELETE CASCADE,
    channel      TEXT NOT NULL CHECK (channel IN ('PHONE', 'EMAIL')),
    new_value    TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    applied_at   TIMESTAMPTZ
);
CREATE INDEX contact_changes_open_idx ON contact_changes (account_id)
    WHERE cancelled_at IS NULL AND applied_at IS NULL;

-- Segredo TOTP cifrado (AES-GCM, chave derivada de MALHAVIVA_OTP_KEY). last_step impede reuso do mesmo passo.
CREATE TABLE totp_secrets (
    account_id UUID PRIMARY KEY REFERENCES accounts ON DELETE CASCADE,
    secret_enc BYTEA NOT NULL,
    enabled_at TIMESTAMPTZ,
    last_step  BIGINT NOT NULL DEFAULT 0
);
