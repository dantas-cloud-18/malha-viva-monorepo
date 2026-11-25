# Política de Retenção e Eliminação — Malha Viva

> **RASCUNHO. Exige revisão jurídica antes de qualquer uso.** Prazos legais marcados [A CONFIRMAR] dependem de parecer. Estado técnico conforme `docs/THREAT-MODEL.md` e migrações V1 a V9 (2026-11-25).

Controlador: [RAZÃO SOCIAL]. Encarregado: [E-MAIL DO ENCARREGADO]. Versão 0.1.

## 1. Tabela de retenção

| Dado | Onde | Prazo | Mecanismo | Estado |
|---|---|---|---|---|
| Conta (handle, hash de senha, telefone, e-mail, verificações) | `accounts` | Enquanto a conta estiver ativa. Após exclusão pelo titular: eliminação imediata dos dados de conta, exceto o que a lei obrigar a guardar [A CONFIRMAR] | `DELETE` em `accounts`; as FKs `ON DELETE CASCADE` apagam `refresh_tokens`, `devices`, `otp_codes`, `contact_changes`, `totp_secrets`, `totp_backup_codes` | Cascata existe no esquema. **Rota de exclusão e teste automatizado: NAO IMPLEMENTADO** |
| Gênero (opcional) | `accounts.gender` (V12) | **Em stand-by (não coletado nesta versão).** Se reativado: enquanto a conta estiver ativa, ou até o titular apagar ou mudar o campo | Apagado (`NULL`) na exclusão da conta, na mesma transação que anonimiza `accounts` | Implementado e testado (`LgpdRoutesTest`) |
| Conta inativa | `accounts` | Proposta: eliminar após [N] meses sem login, com aviso prévio por contato verificado | Job periódico | NAO IMPLEMENTADO. Prazo a decidir |
| Posições brutas | `telemetry_points` | **24 h** (nenhum ponto vive mais que retenção + 1 h) | Partição por hora, `telemetry_maintain_partitions` faz DROP, chamada pelo backend a cada hora | Implementado (`PartitionMaintenance`). Teste ponta a ponta do TTL: a fazer |
| Posições em memória (`LiveHub`) | Memória do servidor | Sem persistência; reinício zera | — | Implementado |
| Nonces anti-replay | Memória (`InMemoryNonceStore`) | TTL de 2x a tolerância (cerca de 120 s) | Expiração por inatividade | Implementado. A tabela `telemetry_nonces` (V2) existe e não é usada |
| Tokens de telemetria anônimos | Memória | Vida curta, por viagem, com teto | Expiração | Implementado |
| OTP | `otp_codes` (só HMAC) | Expira em até **10 min**; uso único; 5 tentativas | Validade no código. Linhas vencidas ficam na tabela | Expiração lógica implementada. **Limpeza física das linhas vencidas: NAO IMPLEMENTADO** (propor: apagar após 24 h) |
| Troca de contato | `contact_changes` | Até aplicar ou cancelar; depois, proposta: apagar após 30 dias | Job | Apagamento NAO IMPLEMENTADO |
| Refresh tokens | `refresh_tokens` | 30 dias de validade absoluta; revogados até a exclusão da conta | Validade e revogação | Implementado. Limpeza de vencidos NAO IMPLEMENTADA |
| Dispositivos | `devices` | Enquanto a conta existir; revogados ficam por [PRAZO] | — | Definir prazo para revogados |
| TOTP e códigos de backup | `totp_secrets`, `totp_backup_codes` | Até desligar o 2FA (apaga as linhas) ou excluir a conta | Implementado | Implementado |
| Registro de consentimento | A criar | Enquanto durar o tratamento, mais o prazo prescricional [A CONFIRMAR], em tabela **separada** da telemetria | — | NAO IMPLEMENTADO |
| **Logs de acesso** (IP, data/hora, rota) | Log do ALB e da aplicação | **6 meses**, Marco Civil da Internet, art. 15 | Expurgo automático (ciclo de vida do bucket/log group) | A configurar na infra (`infra/terraform`) |
| Logs de aplicação | CloudWatch ou equivalente | Proposta: 30 dias, sem dado pessoal (nunca coordenadas, OTP, token, handle, IP) | Retenção do log group | Verificar configuração |
| Eventos consolidados de trem | `train_events` | Proposta: [N] meses, apenas se passar no teste de k-anonimato (RIPD seção 7). Sem ID de passageiro | Job | Prazo a decidir |
| Backups do banco | RDS | Proposta: [N] dias. Dado excluído some dos backups com o ciclo de retenção; informar ao titular | Retenção do RDS | A configurar |
| Registro de incidentes | Interno | Prazo da norma da ANPD [A CONFIRMAR: 5 anos] | — | Processo manual |

## 2. Regras gerais

- Eliminar quando a finalidade acabar, o titular pedir (art. 18, VI) ou revogar o consentimento, ressalvadas as hipóteses do art. 16 (cumprimento de obrigação legal, estudo anonimizado, etc.).
- Posições nunca são exportadas nem restauradas de backup: o TTL de 24 h vale também para cópias (excluir `telemetry_points` dos backups ou garantir retenção de backup compatível).
- Dado de menor: eliminar a pedido do responsável ou do próprio adolescente; sem retenção para marketing (não há marketing para menores).
- Exclusão de conta: encerra sessões, revoga refresh, apaga contatos e segredos. As posições anteriores **não** são ligáveis à conta, então não há o que apagar por conta; informar isso ao titular.

## 3. Pendências

1. Rota e teste automatizado de exclusão de conta.
2. Jobs de limpeza (OTP vencido, refresh vencido, trocas de contato encerradas).
3. Retenção dos logs de acesso em 6 meses e de aplicação em prazo curto, na infraestrutura.
4. Prazos de conta inativa, backups e `train_events`.
5. Parecer sobre prazos legais que obriguem a guardar dados após a exclusão da conta.
