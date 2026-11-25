-- Fase 4.2 (revisao): codigo de enrolamento do admin, entregue fora de banda.
-- O primeiro enrolamento de TOTP de um admin exige este codigo de uso unico; so o SHA-256 (hex) e guardado e a
-- linha e zerada ao usar. Quem promove a conta a admin (operacao manual no banco, ver docs/THREAT-MODEL.md)
-- grava o hash junto com o papel. Admin sem hash nao consegue enrolar (falha fechada).
ALTER TABLE accounts ADD COLUMN admin_enroll_hash TEXT;
