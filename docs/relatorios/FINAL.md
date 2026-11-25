# Relatório final do Malha Viva (esqueleto)

Status: **esqueleto**. Só entram fatos já verificados no repositório (branch `feat/fase-1`, commit `db40a5d`, 2026-11-25). O que depende de medição, decisão ou ação externa fica como `[PENDENTE: ...]`. Este relatório não declara o sistema seguro: ver "Riscos residuais".

Fonte do roteiro: BRIEF (fases 0 a 7 e "Entrega final"). Documentos de apoio no repositório: `ARCHITECTURE.md`, `SECURITY.md`, `docs/THREAT-MODEL.md`, `docs/lgpd/`, `docs/publicacao/`, `docs/planos/FASE-7.md`, `docs/fase7/`.

## 1. O que foi feito

| Fase | Entrega | Situação |
|---|---|---|
| 0. Engenharia reversa do Apps Script | Diagnóstico e inventário do script (somente leitura); detalhado em `docs/fase7/INVENTARIO-APPS-SCRIPT.md` | Feita (relatório de fase fora do repositório, na pasta do projeto) |
| 1. Fundação técnica | Monorepo `backend/` (Ktor), `android/`, `ios/`, `contracts/`, `infra/`; CI em `.github/workflows` (android, backend, ios, repo, security); lockfiles do Gradle | Feita |
| 2. Identidade visual e interface | Design system Android (catálogo, `TrainMarker`, `CheckInButton`, estados vazios), ícone adaptativo a partir do SVG da marca, verificação de cores contra `brand-tokens.json` na CI | Feita no Android; [PENDENTE: estado equivalente no iOS] |
| 3. Rastreamento colaborativo | Ingestão assinada de lotes (token anônimo, HMAC, nonce, validação de plausibilidade, limites por token e IP), `LiveHub` e `TrainClusterer` (polling e SSE), rede do Ramal Santa Cruz semeada do OpenStreetMap (V11), app Android com consentimento, serviço em primeiro plano, amostragem adaptativa e tela de trens ao vivo | Feita no backend e no Android; ingestão fechada em produção por padrão (ver riscos) |
| 4. Segurança | Modelo de ameaças STRIDE, `SECURITY.md`, auth com argon2id, MFA TOTP com códigos de backup, recuperação de conta, revisão de segurança do backend, CI de segurança (gitleaks, Semgrep, Trivy, osv-scanner) | Feita, com pendências em aberto (seção 3 e 5) |
| 5. LGPD e menores | RIPD, LIA, política de privacidade, termos, retenção, incidentes, operadores, encarregado, política de menores; registro de consentimento, faixa etária com consentimento parental e exportação de dados no backend; k-anonimato de trens (k=3) e de lotação de estação (k=5, com histerese) | Feita em código e rascunhos; [PENDENTE: aprovação do RIPD pelo encarregado, controlador e revisão jurídica] |
| 6. Publicação | Rascunhos de Play (Data Safety, localização em segundo plano, classificação, público) e App Store (ficha, `PrivacyInfo.xcprivacy`), checklist de publicação | Rascunhos prontos; [PENDENTE: submissão, vídeo do fluxo, TestFlight e teste fechado] |
| 7. Desligamento do Apps Script | Plano (`docs/planos/FASE-7.md`), inventário (7.1), harness de paridade e relatório (7.2, veredito parcial) | Etapas 7.1 e 7.2 feitas; 7.3 a 7.7 [PENDENTE: dependem das decisões P1 a P4 do plano] |

## 2. Métricas

Medidas em 2026-11-25 sobre `db40a5d`, com `.\gradlew.bat ktlintCheck detekt test --rerun-tasks` em `backend/`: build verde, **242 testes em 35 classes, 0 falhas, 0 ignorados**, ktlint e detekt sem apontamentos.

| Métrica | Valor |
|---|---|
| Commits na branch | 80 até o inventário da etapa 7.1 (inclusive) |
| Testes do backend | 242 (35 classes), todos verdes; inclui o harness de paridade |
| Arquivos de teste Android (`*Test.kt`) | 35 |
| Resultado dos testes Android e iOS | [PENDENTE: executar `android` e `ios` e registrar contagem] |
| Paridade do motor (sintética, 6 cenários) | parcial; ver `docs/fase7/PARIDADE.md` |
| Latência e disponibilidade em produção | [PENDENTE: backend ainda não está no ar (P1)] |
| Usuários, viagens e posições coletadas | [PENDENTE: não há coleta em produção] |

## 3. Achados de segurança

Detalhe e evidência em `docs/THREAT-MODEL.md` (seções 8 a 10). Resumo da revisão de segurança do backend (13 achados):

- **Corrigidos ou mitigados (12):** IP do cliente atrás do ALB; leitura de corpo sem limite; reautenticação em operações de contato; força bruta de TOTP (trava por conta); CAPTCHA no-op em produção; esgotamento de cota de SMS e e-mail; mapas em memória sem expiração; SSE e snapshot sem limite; access token sem `iss`/`aud`/`sid`; admin sem TOTP; enumeração em `complete` (mitigada, risco residual documentado); perda de TOTP sem recuperação e repositórios JDBC sem teste em Postgres real.
- **Aberto (1):** certificate pinning no Android desligado por padrão (sem domínio real para fixar).
- **Pendências priorizadas** (THREAT-MODEL §9), ainda válidas segundo o documento: CAPTCHA real; atestação (Play Integrity e App Attest); armazenamento compartilhado de tokens, nonces e limites; provedores de SMS e e-mail; pepper e segredos em secret manager com rotação; rate limit nas rotas públicas de leitura; detecção de `mock location`; CORS e cabeçalhos de segurança; trilha de auditoria; staging para ZAP; admin com auditoria.
- **Achados do legado Apps Script** (inventário, seção 1): a URL e o id de aparelho funcionam como credencial compartilhada de envio; o campo "quem" de alertas vem da página, sem autenticação; posição ligada a primeiro nome na planilha. Resolvidos apenas com o desligamento (7.6).
- [PENDENTE: reconciliar o THREAT-MODEL e o RIPD §8 com os commits posteriores (consentimento, exportação, idade, k-anonimato), que podem estar mais adiantados que o texto dos documentos.]
- [PENDENTE: pentest ou revisão independente; nenhuma foi executada.]

## 4. Cobertura

- **Cobertura de linhas e ramos:** [PENDENTE: nenhuma ferramenta de cobertura (Jacoco, Kover) está configurada no repositório].
- **Áreas com teste dedicado no backend:** autenticação, sessões, MFA e TOTP, recuperação, ingestão (assinatura, plausibilidade, limites, estado expirável), geometria do corredor, `TrainClusterer`, limites do tempo real, lotação de estação, LGPD (rotas, consentimentos, retenção, estatística por gênero), semente da rede e integração com PostGIS (Testcontainers).
- **Android:** 35 arquivos de teste, incluindo regras de elegibilidade, filtro de trajeto, consenso robusto e simulador de passageiros com ataques, política de amostragem; [PENDENTE: contagem e resultado].
- **iOS:** [PENDENTE: cobertura de testes e build em macOS].
- **Contratos:** validação OpenAPI e AsyncAPI na CI (`.github/workflows`).
- **Paridade do motor:** 6 cenários sintéticos (viagem v-08d8e02a, dois trens, sentidos opostos, celular isolado, dois celulares, lateral de 100 m); falta repetir sobre posições reais.

## 5. Riscos residuais

Não há afirmação de "100% seguro". Riscos conhecidos hoje:

1. **Ingestão fechada em produção**: sem atestação real nem CAPTCHA real, a produção só abre por decisão explícita; abrir antes é risco alto (THREAT-MODEL §9, RIPD R10).
2. **Estado só em memória** (tokens, nonces, limites): reinício apaga tokens e abre janela de replay de até 60 s; um nó só (RIPD R13).
3. **Reidentificação por trajetória** e **trem ou estação com poucas pessoas**: mitigados por k-anonimato (trem k=3, estação k=5), ID efêmero e corte de 300 m; risco residual global do RIPD é "alto" até a aprovação formal e os testes de reidentificação previstos (RIPD §6 e §7). [PENDENTE: aprovação do RIPD.]
4. **Menores**: bloqueio por faixa etária no app; [PENDENTE: revisão jurídica da política de menores e do consentimento parental].
5. **Recuperação de conta** sem provedor de SMS e e-mail em produção (não entrega OTP) e **sem certificate pinning** até existir domínio.
6. **Paridade parcial do motor**: o backend novo esconde trens de 1 e 2 celulares por desenho, depende do intervalo de envio do app (sem sentido com 1 amostra por lote, posição atrasada e ids instáveis com envio lento) e rejeita amostras a mais de 60 m da linha; ETA, situação textual e alerta de parado não têm equivalente (`docs/fase7/PARIDADE.md`).
7. **Legado Apps Script no ar** até a etapa 7.6: credencial de envio compartilhada na URL, sem autenticação nas funções da página, posições ligadas a nome na planilha.
8. **Revogação de credenciais do legado é irreversível**: só com confirmação explícita.
9. **Dependência de terceiros** (OpenStreetMap ODbL: manter atribuição; provedor de nuvem; transferência internacional, RIPD R11).
10. **Janela de observação de 7 dias** do corte (7.5) não prova ausência de clientes esquecidos: [PENDENTE: leitura do painel "Execuções" do Apps Script].

## 6. Pendências

Decisões do responsável pelo produto (plano da Fase 7):

- [PENDENTE: P1 hospedagem do backend e do PostGIS em produção (provedor, região, custo) e domínio real]
- [PENDENTE: P2 destino do PWA e do Supabase atual (desligar, migrar ou manter)]
- [PENDENTE: P3 lista de clientes que ainda usam a URL do Apps Script]
- [PENDENTE: P4 retenção dos dados históricos da planilha (migrar, anonimizar ou descartar)]

Fase 7:

- [PENDENTE: 7.3 implantação em paralelo (depende de P1)]
- [PENDENTE: 7.4 migração final (depende de P4): CSV, descarte de "Quem" e id do aparelho, contagem por aba e checksum de amostra]
- [PENDENTE: 7.5 corte e 7 dias de observação (depende de P3)]
- [PENDENTE: 7.6 remover gatilhos, desfazer implantações, revogar credenciais e arquivar script e planilha em somente leitura, com registro em `docs/relatorios`]
- [PENDENTE: repetir a paridade com posições reais e definir intervalo de envio e requisitos de ETA e alertas]
- [PENDENTE: checklist do painel do Apps Script (inventário, seção 7)]

Segurança, privacidade e publicação:

- [PENDENTE: itens priorizados do THREAT-MODEL §9]
- [PENDENTE: aprovação do RIPD e revisão jurídica]
- [PENDENTE: submissão às lojas e testes fechados]
- [PENDENTE: medição de cobertura e execução dos testes de Android e iOS]
