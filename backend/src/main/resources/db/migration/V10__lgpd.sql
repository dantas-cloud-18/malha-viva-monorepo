-- Fase 5 (LGPD e menores). V1 a V9 ficam intactos.
-- Plano de identidade apenas: nenhuma tabela daqui tem FK, coluna ou indice ligando a telemetry_points
-- (anon_epoch_id) e a ingestao nunca consulta estas tabelas.

-- Faixa etaria autodeclarada (a data de nascimento NAO e guardada) e situacao da conta.
-- Contas anteriores a esta fase entram como 'adult' (nunca declararam idade).
ALTER TABLE accounts ADD COLUMN age_bracket TEXT NOT NULL DEFAULT 'adult'
    CHECK (age_bracket IN ('under_12', 'teen_12_17', 'adult'));
ALTER TABLE accounts ADD COLUMN status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'pending_parental_consent', 'deleted'));
ALTER TABLE accounts ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX accounts_status_idx ON accounts (status, created_at) WHERE status <> 'active';

-- Consentimento por finalidade: estado vigente + historico de eventos (somente apendice).
CREATE TABLE consents (
    account_id     UUID NOT NULL REFERENCES accounts ON DELETE CASCADE,
    purpose        TEXT NOT NULL CHECK (purpose IN ('location', 'marketing')),
    policy_version TEXT NOT NULL,
    granted_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    PRIMARY KEY (account_id, purpose)
);

CREATE TABLE consent_events (
    event_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id     UUID NOT NULL REFERENCES accounts ON DELETE CASCADE,
    purpose        TEXT NOT NULL CHECK (purpose IN ('location', 'marketing')),
    action         TEXT NOT NULL CHECK (action IN ('granted', 'revoked')),
    policy_version TEXT NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX consent_events_account_idx ON consent_events (account_id, occurred_at DESC);

-- Consentimento parental (menor de 12): contato do responsavel NAO e contato de recuperacao da conta.
CREATE TABLE parental_consents (
    account_id       UUID PRIMARY KEY REFERENCES accounts ON DELETE CASCADE,
    guardian_channel TEXT NOT NULL CHECK (guardian_channel IN ('PHONE', 'EMAIL')),
    guardian_value   TEXT NOT NULL,
    policy_version   TEXT NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL,
    verified_at      TIMESTAMPTZ
);

ALTER TABLE otp_codes DROP CONSTRAINT otp_codes_purpose_check;
ALTER TABLE otp_codes ADD CONSTRAINT otp_codes_purpose_check
    CHECK (purpose IN ('VERIFY_CONTACT', 'RECOVERY', 'CONTACT_CHANGE', 'PARENTAL_CONSENT'));

-- Registros de acesso (Marco Civil art. 15: 6 meses). Sem FK de proposito: sobrevivem a exclusao da conta ate o
-- fim do prazo legal (LGPD art. 16, I) e a rotina de retencao os apaga.
CREATE TABLE access_logs (
    log_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  UUID NOT NULL,
    event       TEXT NOT NULL CHECK (event IN ('signup', 'login')),
    ip          TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX access_logs_time_idx ON access_logs (occurred_at);
CREATE INDEX access_logs_account_idx ON access_logs (account_id);
