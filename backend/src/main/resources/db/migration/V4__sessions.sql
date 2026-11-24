-- Fase 4: telefone e e-mail passam a ser opcionais (recuperacao vem depois); dispositivos por familia de refresh.
ALTER TABLE accounts ALTER COLUMN phone_e164 DROP NOT NULL;
ALTER TABLE accounts ALTER COLUMN email DROP NOT NULL;

-- Um dispositivo = uma familia de refresh (device_id = family_id). Sem posicao nem telemetria aqui.
CREATE TABLE devices (
    device_id    UUID PRIMARY KEY,
    account_id   UUID NOT NULL REFERENCES accounts ON DELETE CASCADE,
    label        TEXT NOT NULL CHECK (char_length(label) <= 64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX devices_account_idx ON devices (account_id) WHERE revoked_at IS NULL;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT refresh_tokens_family_fk FOREIGN KEY (family_id) REFERENCES devices (device_id) ON DELETE CASCADE;
