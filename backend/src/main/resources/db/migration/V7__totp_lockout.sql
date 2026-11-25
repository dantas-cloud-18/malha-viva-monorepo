-- Fase 4.2 (revisao): trava de TOTP por CONTA, independente de IP e de mfa_token.
-- failed_attempts conta erros desde o ultimo acerto; ao chegar em 10 a conta fica travada ate locked_until (15 min)
-- e o contador zera. Acerto zera os dois. Vale para login, confirmacao do enrolamento e desligamento.
ALTER TABLE totp_secrets ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0);
ALTER TABLE totp_secrets ADD COLUMN locked_until TIMESTAMPTZ;
