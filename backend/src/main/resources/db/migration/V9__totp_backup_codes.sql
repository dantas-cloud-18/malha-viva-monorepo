-- Fase 4.2 (backup): codigos de recuperacao do TOTP. Uso unico; so o HMAC-SHA256 (hex, chave derivada de
-- MALHAVIVA_OTP_KEY e ligada a conta) fica guardado, nunca o codigo. Regenerar apaga o lote antigo.
CREATE TABLE totp_backup_codes (
    code_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES accounts ON DELETE CASCADE,
    code_hmac  TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX totp_backup_codes_hmac_uq ON totp_backup_codes (account_id, code_hmac);
