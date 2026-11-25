# Relatório de Impacto à Proteção de Dados Pessoais (RIPD) — Malha Viva

> **RASCUNHO. Exige revisão jurídica antes de qualquer uso.** Não é parecer jurídico. Dados da empresa estão como placeholders. Estado técnico verificado em `docs/THREAT-MODEL.md`, `contracts/openapi.yaml` e `backend/src/main/resources/db/migration` (V1 a V9), em 2026-11-25.

Controlador: [RAZÃO SOCIAL], CNPJ [CNPJ]. Encarregado: [NOME DO ENCARREGADO], [E-MAIL DO ENCARREGADO]. Versão 0.1. Data: [DATA]. Fundamento: Lei 13.709/2018 (LGPD), art. 38.

## 1. Descrição do tratamento

App colaborativo de rastreamento de trens (V1: Trens RJ). Passageiros que optam compartilham a posição para estimar a posição dos trens e a lotação das estações. O público inclui menores de 18 anos. O tratamento envolve **localização** e **dados de crianças e adolescentes**, por isso é tratado como alto risco.

Dois planos separados por desenho:

- **Plano de identidade**: conta, login, recuperação, sessões.
- **Plano de telemetria**: posições com ID efêmero, sem ligação com conta, aparelho ou IP.

## 2. Inventário de dados

| Dado | Plano | Onde (verificado) | Finalidade | Base legal | Retenção |
|---|---|---|---|---|---|
| Handle (`@usuario`) | Identidade | `accounts.handle` (V3). Público por desenho | Login e identificação | Contrato, art. 7º, V | Ver RETENCAO.md |
| Hash da senha (argon2id + pepper) | Identidade | `accounts.password_hash` | Autenticação | Contrato, art. 7º, V | Vida da conta |
| Telefone E.164 (opcional) | Identidade | `accounts.phone_e164` | **Só** segurança e recuperação | Contrato, art. 7º, V | Vida da conta |
| E-mail (opcional) | Identidade | `accounts.email` | **Só** segurança e recuperação | Contrato, art. 7º, V | Vida da conta |
| Verificação de contato | Identidade | `phone_verified_at`, `email_verified_at` | Segurança | Contrato | Vida da conta |
| Tokens de refresh (hash SHA-256) | Identidade | `refresh_tokens` (V3) | Sessão | Contrato | Até 30 dias ou revogação |
| Dispositivos conectados (rótulo, datas) | Identidade | `devices` (V4) | Sessão e segurança | Contrato | Vida da conta |
| OTP (só HMAC) | Identidade | `otp_codes` (V5) | Recuperação e verificação | Contrato | Expira em até 10 min |
| Troca de contato pendente | Identidade | `contact_changes` (V5), guarda o novo valor | Segurança | Contrato | Até aplicação ou cancelamento |
| Segredo TOTP (cifrado) e códigos de backup (HMAC) | Identidade | `totp_secrets`, `totp_backup_codes` (V5, V9) | 2FA opcional | Contrato | Até desligar o 2FA |
| Posições de viagem | Telemetria | `telemetry_points` (V2): ponto, precisão, velocidade, rumo, estado de movimento, hora | Posição do trem e lotação | **Consentimento destacado, art. 8º** | TTL 24 h (partição por hora) |
| ID efêmero `anon_epoch_id` | Telemetria | Chave de `telemetry_points` | Agrupar pontos de uma viagem | Consentimento | Junto com os pontos |
| Eventos consolidados de trem | Agregado | `train_events` (V2): trem, evento, nº de dispositivos, confiança | Histórico operacional | Dado anonimizado se respeitar o k (seção 7) | A definir |
| IP de requisição | Operacional | Log de acesso (ALB/app) | Segurança, limites, Marco Civil | Obrigação legal, art. 7º, II; legítimo interesse nos limites | 6 meses |
| Veredito de atestação (Play Integrity, App Attest) | Telemetria | **NAO IMPLEMENTADO** | Antifraude | Legítimo interesse (LIA.md) | Proposta: não reter |
| Gênero (opcional) | Identidade | **Em stand-by (não coletado nesta versão).** `accounts.gender` (V12), nulo = não informado; opções: masculino, feminino, não-binário, prefiro não dizer | **Só** estatística agregada e anônima de perfil de uso | **Consentimento, art. 7º, I e art. 8º**, dado pelo próprio preenchimento do campo opcional | Vida da conta; apagado na exclusão |
| Idade ou data de nascimento | Identidade | **NAO IMPLEMENTADO** (verificação de idade a propor) | Proteção de menores | Obrigação legal (ECA Digital) | A definir |
| Registro de consentimento versionado | Identidade | **NAO IMPLEMENTADO** | Prova do consentimento | Obrigação legal / exercício de direitos | [PRAZO] |

Não são coletados: modelo do aparelho, ID de publicidade ou IP junto às posições, nem posição ligada à conta.

## 3. Finalidades e bases legais

| Finalidade | Base | Observações |
|---|---|---|
| Conta, login | Contrato, art. 7º, V | Telefone e e-mail **só** para segurança e recuperação; proibido usar para marketing |
| Localização | Consentimento destacado, art. 8º | Opt-in, desligado por padrão. Revogável a 1 toque ("Parar" persistente). O app funciona sem ele (horário previsto e trens de outros usuários). Registro de consentimento **separado** das posições |
| Antifraude | Legítimo interesse, art. 7º, IX | LIA.md. Dados mínimos, sem perfil por pessoa |
| Marketing | Consentimento separado, art. 8º | Nunca para menores. Hoje não há marketing |
| Perfil de uso (gênero) | Consentimento, art. 7º, I | **Em stand-by (não coletado nesta versão).** Campo opcional, nunca exigido. Gênero não é dado sensível (art. 5º, II), mas tratamos com minimização: só relatórios agregados, bucket com menos de 5 contas é suprimido (`GenderStatsRepository`), nunca por pessoa, nunca cruzado com localização, viagens ou telemetria. Editável ou apagável a qualquer momento em "Privacidade e meus dados" |
| Logs de acesso | Obrigação legal, Marco Civil art. 15 | 6 meses |
| Menores | Art. 14 LGPD e ECA Digital (Lei 15.211/2025) | Melhor interesse da criança; ver POLITICA-MENORES.md |

## 4. Fluxo de dados e operadores

Ver OPERADORES.md. Telemetria e identidade não se juntam: nenhuma tabela de identidade é referenciada pela telemetria e o token anônimo é emitido sem conta.

## 5. Riscos

Probabilidade (B/M/A) e impacto (B/M/A), antes e depois das mitigações. Valores são **proposta**, a validar com o Encarregado.

| # | Risco | Antes | Mitigação existente ou prevista | Depois |
|---|---|---|---|---|
| R1 | Reidentificação por trajetória (casa, trabalho, rotina) | A/A | ID efêmero por viagem; corte de 300 m no início e no fim (`sanitizeTrip`), que vale também no envio ao vivo: o app envia lotes a cada 15 s e **retém os últimos 300 m** da viagem até o fim (`TripPipeline.drain`), de modo que nenhuma amostra sai a menos de 300 m da posição mais nova nem do ponto de partida; descarte fora do corredor, TTL 24 h, sem vínculo com conta. **Mudança (decisão de produto): o envio passou de "só no fim da viagem" para "durante a viagem"**, então a posição agregada de um trem agora existe em tempo quase real no servidor (janela de 60 s, só memória). Limiar de publicação `MALHAVIVA_TRAIN_MIN_CONTRIBUTORS` agora tem padrão 1 (ver R2) | M/A **[PENDENTE: revisão do Encarregado]** |
| R2 | Trem de 1 sinal expõe o deslocamento de uma pessoa em tempo real | A/A | **Risco aumentado por decisão de produto**: o limiar padrão caiu de k=3 para **k=1** (piso 1; `ClusterPolicy`, configurável por `MALHAVIVA_TRAIN_MIN_CONTRIBUTORS`), para o trem aparecer com um único celular. Um trem visível pode corresponder à posição anônima ao vivo de **uma só pessoa**; as faixas de confiança (1 a 2 baixa, 3 a 6 média, 7 ou mais alta) não escondem isso. O trem também traz situação, estação e ETA derivados da mesma posição. Mitigações que continuam valendo: ID de viagem efêmero (sem conta, sem dispositivo), retenção dos últimos 300 m e do início da viagem no aparelho, atraso natural de 25 a 40 s pela retenção, posição só em memória no servidor (60 s) e brutas com TTL de 24 h, ids de trem efêmeros, nenhum identificador no JSON. Não mitiga: uma pessoa que conheça a rotina de alguém pode reconhecer o trem em que ele está e, com o app aberto, acompanhá-lo; pedestre a até 150 m da linha pode aparecer como trem (o limite lateral subiu de 60 m para 150 m, igual ao Apps Script). Residual: **alto enquanto k=1**; subir `MALHAVIVA_TRAIN_MIN_CONTRIBUTORS` para 3 volta ao residual anterior (B/M), ao custo de não ver trem com 1 ou 2 celulares | A/M **[PENDENTE: revisão do Encarregado]** |
| R3 | Lotação de estação vazia revela presença individual | A/A | Nível de lotação só é publicado com pelo menos k_lot=5 viagens anônimas distintas paradas na estação na janela de 30 s (`StationCrowdingEstimator`, `MALHAVIVA_STATION_MIN_CONTRIBUTORS`, piso 5; abaixo disso `level` é nulo, "sem dados"). Só faixas low/medium/high (5 a 9, 10 a 19, 20 ou mais), nunca a contagem; a mesma viagem conta uma vez. Também conta o "Estou nesta estação" (check-in anônimo): UUID aleatório por check-in, sem conta, sem IP guardado, só em memória por 10 min, cancelável; soma às viagens paradas sem deduplicar (a mesma pessoa pode contar nas duas fontes, pois ligá-las quebraria o anonimato), e o k=5 vale para a soma (`StationCheckins`). **Implementado e testado.** Diferenciação mitigada (seção 7.1): histerese de 2 viagens e espera mínima de 60 s (`StationCrowdingSmoother`, `CrowdingPolicy.HOLD_MS`/`HYSTERESIS_MARGIN`) | B/M |
| R4 | Menor compartilhando localização | M/A | Bloqueio para menores de 18 (`sharingBlocked`), mas depende de perfil de idade **NAO IMPLEMENTADO** | A/A até existir verificação de idade |
| R5 | Vazamento de contatos (telefone e e-mail em claro em `accounts`) | M/A | Criptografia do RDS em repouso, acesso mínimo, logs sem PII. Avaliar cifra por coluna | M/M |
| R6 | Ligação indevida entre conta e posição (operador, log, metadado) | B/A | Planos separados, sem `user_id` na telemetria, logs sem coordenadas, token anônimo. Teste automatizado a confirmar (Fase 3.5) | B/A |
| R7 | Vazamento de posições brutas durante o TTL | B/A | TLS, banco em rede privada, retenção de 24 h com DROP de partição | B/M |
| R8 | Consentimento inválido ou não comprovável | M/M | Tela de divulgação proeminente (Android implementado). Registro versionado **NAO IMPLEMENTADO** | B/M |
| R9 | Uso de SMS para spam ou assédio, custo | M/B | Cotas e limites implementados. CAPTCHA real **NAO IMPLEMENTADO** | M/B |
| R10 | Enxame falso move o trem (integridade) | M/M | Atestação **NAO IMPLEMENTADO**; consenso | M/M |
| R11 | Transferência internacional sem base | M/M | OPERADORES.md; cláusulas-padrão aprovadas pela ANPD | B/M |
| R12 | Incidente sem comunicação no prazo | B/A | INCIDENTES.md | B/M |
| R13 | Estado só em memória: reinício apaga tokens e abre replay de até 60 s | M/B | Armazenamento compartilhado (pendência da Fase 4) | M/B |

Risco residual global: **alto** até R1 a R4 estarem fechados. Recomendação: não abrir a ingestão em produção antes de (a) limiar mínimo de sinais, (b) k-anonimato de lotação, (c) verificação de idade com bloqueio de menores, (d) registro de consentimento. Hoje a ingestão em produção já está fechada por padrão (nenhum token é emitido sem atestação real).

## 6. Análise de reidentificação das trajetórias

1. **Passageiro único**: poucos usuários por trem. A posição de um trem de 1 sinal é a posição de uma pessoa. Estado atual: publicado com confiança 0,3.
2. **Início e fim de viagem**: revelam estação de origem e destino. Embarque e desembarque em estação pouco usada apontam para casa e trabalho. O corte de 300 m ajuda, mas **não** esconde a estação quando ela é o ponto de entrada na linha.
3. **Rotina**: mesmo horário, todo dia. O ID efêmero por viagem impede ligar viagens. Risco restante: ligação por quasi-identificadores (horário e trecho) dentro das 24 h de retenção.
4. **Cruzamento externo**: horário do trem somado a redes sociais. Fora do controle do projeto.
5. **Ligação com a conta por metadados**: horário do login versus horário da emissão do token de telemetria; IP no log. Mitigar: não registrar IP junto de telemetria, não correlacionar logs de login com emissão de token.
6. **Persistência**: só agregados persistem (`train_events`). `consensus_devices` baixo (ex.: 1) não deve ser retido com carimbo fino.

Conclusão provisória: as posições brutas **não** são dado anonimizado (art. 12) durante o TTL. São dado pessoal pseudonimizado e tratadas como tal. Só agregados que passem nos testes da seção 7 podem ser tratados como anonimizados.

## 7. Abordagem de k-anonimato e testes

### 7.1 Regra proposta

- **Publicação de trem**: decisão de produto vigente: **k_trem = 1** (publica com 1 ID efêmero), com piso 1 no servidor; a proposta original era k_trem = 3 (alinha com `ConsensusPolicy.MIN_TRUSTED`). O desvio está registrado em R2 e depende da revisão do Encarregado **[PENDENTE: revisão do Encarregado]**. Para k > 1, abaixo do limiar o trem não é publicado.
- **Lotação de estação**: 3 níveis (baixa, média, alta). Publicar só com pelo menos **k_lot** check-ins "Estou na estação" distintos na janela. Proposta: k_lot = 5. Abaixo: "sem dados de lotação".
  - **Estado atual**: não há check-in explícito. O sinal é a viagem anônima parada (velocidade até 1 m/s, até 100 m da estação ao longo da via) na janela de 30 s do LiveHub, contada por ID efêmero distinto. k_lot = 5 é piso no servidor. Viagens sem sentido inferido ainda não entram.
- **Degraus**: os limites entre níveis devem ser tais que mesmo o nível mais baixo exija k_lot.
- **Diferenciação**: k=5 numa leitura e k=6 na seguinte revelam a entrada de 1 pessoa. Mitigar com arredondamento para o degrau, histerese e atraso mínimo entre mudanças de degrau. Janela deslizante curta (proposta 5 min).
  - **Implementado (lotação de estação)**: estado publicado por (corredor, sentido, estação), só em memória e só para estações semeadas. Mudança entre degraus visíveis, ou de nulo para nível, só após 60 s (`HOLD_MS`) com o novo nível bruto estável, e só se a contagem sair da faixa atual por mais de 2 viagens (`HYSTERESIS_MARGIN`; ex.: baixa segue até 11, média fica entre 8 e 21, alta a partir de 18). **Cair abaixo de k publica nulo na hora**: nunca se exibe nível sob k, então o atraso vale só para transições entre níveis visíveis e de nulo para visível (escolha que privilegia a privacidade). Limite conhecido: a estabilidade é avaliada a cada consulta, sem tarefa de fundo.
- **Persistência**: agregados históricos com carimbo grosso (ex.: faixa de 15 min) e sem contagem exata abaixo de k.
- Os valores de k são **propostas**: calibrar com simulação e decisão do Encarregado.

### 7.2 Testes (a implementar na fase de código)

1. **Limiar**: com 1 a k-1 sinais, a API pública **não** devolve posição de trem nem nível de lotação; com k, devolve.
2. **Diferenciação**: simular entrada e saída de 1 passageiro e provar que a saída pública não muda de degrau de forma imediata.
3. **Ausência de identificador**: varrer `telemetry_points` e o JSON público; nenhum campo é `user_id`, handle, conta, IP ou id de aparelho (também exigido pela Fase 3.5).
4. **Separação de planos**: nenhuma chave estrangeira de `telemetry_*` para tabelas de identidade (consulta ao catálogo do Postgres).
5. **TTL**: com relógio simulado, nenhum ponto vive mais que retenção + 1 h (a função `telemetry_maintain_partitions` documenta isso; falta teste de integração ponta a ponta).
6. **Simulador de reidentificação**: gerar N passageiros sintéticos com rotinas fixas; tentar ligar viagens de dias diferentes e inferir origem e destino a partir de pontos e agregados; medir a taxa de acerto. Meta proposta: não superior ao acaso para viagens com IDs efêmeros distintos.
7. **Menores**: conta de menor de 18 nunca obtém token de telemetria nem envia lote.

## 8. Direitos do titular (art. 18): estado

| Direito | Estado |
|---|---|
| Acesso e exportação em JSON | NAO IMPLEMENTADO (sem rota em `contracts/openapi.yaml`) |
| Correção | Parcial: troca de contato com espera de 24 h. Handle sem rota |
| Exclusão | NAO IMPLEMENTADO. As FKs `ON DELETE CASCADE` de `accounts` já apagam sessões, OTP e TOTP; faltam a rota e o teste automatizado |
| Revogação do consentimento | Cliente: "Parar" implementado. Registro da revogação NAO IMPLEMENTADO |
| Histórico de consentimentos | NAO IMPLEMENTADO |
| Posições | Não ligáveis ao titular por desenho: informar isso em vez de prometer exportação |

## 9. Parecer e aprovação

Encarregado: [NOME] — [DATA] — [ASSINATURA]
Controlador: [NOME DO REPRESENTANTE] — [DATA] — [ASSINATURA]
Revisão jurídica: [RESPONSÁVEL] — [DATA]

Reavaliar a cada mudança material (novo operador, novo uso de localização, novo sistema além de Trens RJ, ativação da atestação) e ao menos uma vez por ano.
