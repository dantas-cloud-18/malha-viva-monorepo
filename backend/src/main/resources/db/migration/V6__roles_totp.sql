-- Fase 4.2: papeis e 2FA TOTP no login (opcional para user, obrigatorio para admin). V1 a V5 ficam intactos.
-- O estado do TOTP ja existe em totp_secrets (V5): segredo cifrado, enabled_at (ligado e confirmado em) e
-- last_step (bloqueia reuso do mesmo passo de 30 s). Desligar apaga a linha.

-- Papel da conta. Promover a admin e operacao manual no banco; nao ha rota para isso.
ALTER TABLE accounts ADD COLUMN role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin'));

-- A familia de refresh lembra se o login passou pelo TOTP, para o access renovado manter amr otp.
ALTER TABLE refresh_tokens ADD COLUMN mfa BOOLEAN NOT NULL DEFAULT false;
