# Operadores e Transferência Internacional — Malha Viva

> **RASCUNHO. Exige revisão jurídica antes de qualquer uso.** Escolhas de provedor ainda não foram feitas; linhas marcadas TBD são propostas, não decisões. Dados da empresa estão como placeholders.

Controlador: [RAZÃO SOCIAL]. Encarregado: [E-MAIL DO ENCARREGADO]. Versão 0.1. Estado conforme `docs/THREAT-MODEL.md` (2026-11-25).

## 1. Lista de operadores e suboperadores

| Operador | Função | Dados tratados | Local do tratamento | Estado | Contrato (DPA) |
|---|---|---|---|---|---|
| AWS (RDS PostgreSQL/PostGIS, ALB, compute, Secrets Manager, logs) | Hospedagem e infraestrutura (`infra/terraform`) | Todos os dados da plataforma, em repouso criptografado no RDS | Região [REGIÃO AWS, ex.: sa-east-1 — a confirmar no Terraform] | Em uso (infra definida) | [DPA AWS: ANEXAR] |
| Provedor de SMS | Envio de OTP e avisos de segurança | Telefone E.164 e texto da mensagem (contém o OTP) | [PAÍS] | **TBD.** Propostas: Twilio Verify, Zenvia. Produção hoje fail-closed (não envia) | [A CONTRATAR] |
| Provedor de e-mail | Envio de OTP e avisos de segurança | E-mail e texto da mensagem | [PAÍS] | **TBD.** Propostas: Amazon SES, Resend | [A CONTRATAR] |
| Google (Play Integrity API) | Atestação do aplicativo e do aparelho Android | Token de integridade do aparelho, sem conta | EUA e outros países | NAO IMPLEMENTADO | Termos do Google Play / Google Cloud [VERIFICAR] |
| Apple (App Attest) | Atestação no iOS | Chave e asserção do aparelho, sem conta | EUA e outros países | NAO IMPLEMENTADO | Termos do Apple Developer [VERIFICAR] |
| CAPTCHA (Cloudflare Turnstile ou hCaptcha) | Antibot no cadastro, login e recuperação | Token do desafio, IP e sinais do navegador/app, tratados pelo provedor | EUA e outros países | **TBD, NAO IMPLEMENTADO.** Backend não sobe em produção sem verificador real | [A CONTRATAR] |
| Pwned Passwords (HIBP) | Checar senha vazada | Apenas o prefixo de 5 caracteres do SHA-1 da senha (k-anonimato). Não identifica o titular | EUA (Cloudflare) [VERIFICAR] | Implementado | Serviço público; considerar dado não pessoal, registrar a análise |
| FCM / APNs | Push de alertas e Live Activities | Token de push. Payload sem dado pessoal | EUA | NAO IMPLEMENTADO | Termos Google / Apple |
| Serviços de mapa (tiles do estilo MapLibre) | Mapa no app | Possível IP do aparelho e região visualizada, no servidor de tiles | [PROVEDOR/LOCAL] | A definir. Preferir tiles próprios ou provedor sem rastreio | [A DEFINIR] |
| Lojas (Google Play, App Store) | Distribuição | Dados próprios das lojas, fora do nosso controle | EUA | Em uso na Fase 6 | Termos das lojas |

Operadores devem ter contrato com: finalidade, instruções do controlador, confidencialidade, medidas de segurança, **notificação de incidente em até 24 h** (INCIDENTES.md), auxílio nos direitos do titular, devolução ou eliminação ao fim, auditoria, e lista de suboperadores.

## 2. Transferência internacional (arts. 33 a 36 LGPD; Resolução CD/ANPD nº 19/2024)

Há transferência internacional quando dado pessoal for tratado fora do Brasil por operador ou controlador estrangeiro. Casos prováveis: provedor de SMS ou e-mail estrangeiro, CAPTCHA, atestação do Google e Apple, FCM/APNs, tiles de mapa e, se a região AWS não for o Brasil, a hospedagem.

| Fluxo | Dado pessoal? | Base de transferência proposta |
|---|---|---|
| AWS fora do Brasil | Sim, todos | Evitar: usar região no Brasil. Se não for possível, cláusulas-padrão contratuais da ANPD (art. 33, II, "b") |
| SMS e e-mail | Sim (telefone, e-mail) | Preferir provedor com operação no Brasil. Do contrário, cláusulas-padrão contratuais da ANPD |
| CAPTCHA | IP e sinais do cliente | Cláusulas-padrão contratuais; avaliar provedor com opção de dados no Brasil ou sem retenção |
| Play Integrity e App Attest | Sem conta, mas dado do aparelho | Execução de contrato com o titular na relação com a loja e necessidade técnica; registrar a análise com o Encarregado e o jurídico |
| FCM e APNs | Token de push | Idem; payload sem dado pessoal |
| Pwned Passwords | Prefixo de hash | Registrar que não é dado pessoal ou que o risco é remoto |

**Passos:**

1. Fechar a escolha dos provedores (decisão do dono do produto).
2. Mapear país e suboperadores de cada um.
3. Para cada fluxo, definir o mecanismo do art. 33 e anexar o contrato.
4. Informar o titular na POLITICA-PRIVACIDADE.md (países e mecanismo).
5. Revisar quando houver novo operador.

## 3. Pendências

1. Escolher e contratar SMS e e-mail (bloqueia a recuperação em produção).
2. Escolher o CAPTCHA (bloqueia a subida do backend em produção).
3. Credenciais de Play Integrity e App Attest.
4. Confirmar a região AWS no Terraform e anexar o DPA.
5. Definir a fonte dos tiles do mapa.
