# Avaliação de Legítimo Interesse (LIA) — Antifraude

> **RASCUNHO. Exige revisão jurídica antes de qualquer uso.** Dados da empresa estão como placeholders. Estado técnico conforme `docs/THREAT-MODEL.md` (2026-11-25).

Controlador: [RAZÃO SOCIAL]. Encarregado: [E-MAIL DO ENCARREGADO]. Base: LGPD, art. 7º, IX e art. 10. Versão 0.1.

## 1. Finalidade

Impedir que posições falsas, replays e enxames de clientes adulterados degradem a posição dos trens e a lotação mostradas a todos, e proteger contas contra força bruta e abuso de envio de SMS. Interesse concreto e atual: integridade do serviço e segurança dos usuários.

## 2. Tratamentos cobertos

| Tratamento | Dado | Estado |
|---|---|---|
| Atestação do aparelho (Play Integrity, App Attest) na emissão do token | Veredito de integridade, sem ID de aparelho gravado junto à posição | NAO IMPLEMENTADO (produção recusa tudo, fail-closed) |
| Plausibilidade (precisão, velocidade, duplicidade, fora do corredor, mock) | Pontos da própria viagem | Implementado (`validateSamples`) |
| Assinatura do lote, timestamp e nonce | Chave por token, nonce | Implementado, em memória de um nó |
| Limite de taxa por token, IP e conta | IP em memória, com expiração | Implementado |
| Consenso robusto e descarte de outliers | Pontos agregados | Implementado (`TrainClusterer`) |
| Segurança de login (`LoginGuard`, CAPTCHA, atraso progressivo) | IP e handle em memória | Implementado; CAPTCHA real NAO IMPLEMENTADO |
| Reputação anônima por aparelho | Identificador persistente de aparelho | **Proposta, não coberta por esta LIA** (seção 6) |

## 3. Necessidade

- Sem validação, qualquer pessoa publica trens falsos e o produto perde o sentido.
- Alternativas menos intrusivas: (a) só validação no servidor, insuficiente contra clientes modificados; (b) confiar no consentimento de localização, que não cobre fraude. A atestação é o meio proporcional.
- Minimização: nenhum identificador de aparelho, modelo ou ID de publicidade fica junto da posição. O veredito de atestação é usado na emissão do token e **não** é guardado ligado à conta nem à posição.

## 4. Balanceamento

| Fator | Avaliação |
|---|---|
| Expectativa do titular | Razoável: apps de localização verificam integridade. Informar na política e na divulgação proeminente |
| Natureza do dado | Metadado técnico de integridade, não localização |
| Impacto | Baixo se o veredito não for retido. Risco: falso positivo bloqueia usuário legítimo (root, ROM alternativa). Mitigação: o app segue funcional sem compartilhar |
| Menores | Sem antifraude que perfile menores: o compartilhamento é bloqueado para menores de 18. Limites e CAPTCHA de login valem para todos |
| Salvaguardas | Minimização, retenção curta, sem perfil persistente, sem decisão com efeito jurídico, sem compartilhamento além do operador de atestação |
| Oposição | Via [E-MAIL DO ENCARREGADO]. Opor-se ao antifraude de telemetria equivale a não compartilhar localização; para segurança de login, caso a caso |

## 5. Transparência

Descrever na POLITICA-PRIVACIDADE.md a finalidade, o dado verificado, a retenção e como se opor.

## 6. Decisões em aberto

1. Reputação por aparelho exigiria identificador persistente, o que enfraquece a separação entre planos. Recomendação: **não adotar** sem nova LIA e revisão do RIPD; preferir limites por token e consenso.
2. Retenção do veredito de atestação: proposta zero (usar e descartar). Se for preciso guardar para investigação, definir prazo curto [PRAZO].
3. Logs de IP: cobertos pelos 6 meses do Marco Civil; uso adicional só para investigação de abuso, sem cruzar com telemetria.

## 7. Conclusão provisória

O legítimo interesse é **aplicável** à atestação, validação e limites desde que as salvaguardas da seção 4 estejam implementadas. Reavaliar quando a atestação real for ligada.

Aprovação: Encarregado [NOME] — [DATA]. Revisão jurídica: [RESPONSÁVEL] — [DATA].
