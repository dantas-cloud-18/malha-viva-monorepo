# Modelo de ameaças (STRIDE)

Escopo: Fase 4 do projeto (OWASP MASVS e ASVS L2 como referência). Estado do código verificado no repositório
em 2026-11-24. Este documento separa o que **já existe no código** do que **ainda não existe**. Tudo marcado
**NAO IMPLEMENTADO** é uma lacuna aberta, não uma proteção.

Legenda STRIDE: **S** falsificação de identidade, **T** adulteração, **R** repúdio, **I** vazamento de
informação, **D** negação de serviço, **E** elevação de privilégio.

## 1. Visão geral

Dois planos separados por desenho:

- **Plano de identidade** (contas, login, recuperação, sessão): tabelas `accounts` e `refresh_tokens`
  (`V3__accounts.sql`). Nenhuma tabela dele é referenciada pela telemetria.
- **Plano de telemetria** (posições de passageiros): token anônimo emitido sem conta, aparelho ou IP
  (`TokenIssuer`), posições sem `user_id`, handle, IP ou modelo de aparelho (`V2__telemetry.sql`).

Fronteiras de confiança:

| Fronteira | Quem está de cada lado |
|---|---|
| F1 | Aparelho do usuário (não confiável, pode estar adulterado) e a API (internet) |
| F2 | API e banco/cache (rede privada da infraestrutura, `infra/terraform`) |
| F3 | Backend e leitores públicos de posição (polling, SSE, widgets, Live Activities) |
| F4 | Operadores/administradores e o backend |

Estado de implementação (resumo):

| Área | Estado |
|---|---|
| Ingestão de telemetria (token, assinatura, nonce, validação, rate limit, hub) | Implementado no backend (memória de um nó) |
| Cliente Android de rastreamento (opt-in, check-in, sanitização, lote assinado) | Implementado |
| Distribuição em tempo real (polling `GET` e SSE) | Implementado, sem autenticação (dados públicos) |
| Política de senha (tamanho, NFKC, Pwned Passwords k-anonimato, zxcvbn) | Implementado (`auth/PasswordPolicy.kt`) |
| Hash de senha, login, rotas `/auth/*`, refresh rotativo | NAO IMPLEMENTADO (só contrato em `contracts/openapi.yaml` e tabelas no `V3`) |
| Recuperação de conta (OTP) | NAO IMPLEMENTADO |
| Atestação real (Play Integrity, App Attest) | NAO IMPLEMENTADO |
| Armazenamento persistente de tokens e nonces | NAO IMPLEMENTADO |
| Widgets/Live Activities (iOS) | NAO IMPLEMENTADO (só arquivo-base `ios/Widgets/MalhaVivaWidgets.swift`) |
| Admin web | NAO IMPLEMENTADO |

---

## 2. Login

**Ativos:** credenciais (senha, hash), contas (`accounts`), tokens de acesso e refresh (`refresh_tokens`),
telefone e e-mail do titular.

**Fronteiras:** F1 (app para API), F2 (API para banco).

| STRIDE | Ameaça | Mitigação existente | Residual / aberto |
|---|---|---|---|
| S | Credential stuffing, força bruta, senha vazada reutilizada | Política de senha: mínimo 8, máximo 128 pontos de código após NFKC, consulta Pwned Passwords por k-anonimato (só o prefixo de 5 caracteres do SHA-1 sai da máquina), zxcvbn com score mínimo 3, sem regras de composição | Rate limit por conta/IP no login, atraso progressivo, CAPTCHA e detecção de credential stuffing: NAO IMPLEMENTADO. Mensagem genérica de erro: NAO IMPLEMENTADO (não há rota de login) |
| S | Roubo de refresh token | Esquema do banco guarda só `token_hash` e prevê `family_id`, `replaced_at`, `revoked_at` para revogar família no reuso | Lógica de rotação e detecção de reuso: NAO IMPLEMENTADO. Tela "Dispositivos conectados": NAO IMPLEMENTADO |
| T | Adulteração do hash ou do fluxo de login | Esquema usa hash em coluna dedicada; nada de senha em texto | Hash argon2id (m ≥ 19 MiB, t = 2, p = 1) e pepper em secret manager: NAO IMPLEMENTADO. O Terraform prevê um secret (`aws_secretsmanager_secret.app`), mas o código não o usa para pepper |
| R | Usuário nega ter feito login | Nenhuma | Log de auditoria de autenticação sem dado pessoal: NAO IMPLEMENTADO |
| I | Enumeração de contas (cadastro/login), vazamento de PII em log | Nas rotas de telemetria o backend não registra corpo, token nem coordenadas (comentários e código de `TokenRoutes`/`BatchIngestor`) | Para login: NAO IMPLEMENTADO. Índices únicos em handle/telefone/e-mail existem e exigem resposta uniforme no cadastro |
| D | Hash caro usado como amplificação de DoS | Nenhuma ainda | Limite de taxa antes do hash: NAO IMPLEMENTADO |
| E | Token de sessão usado em área indevida (IDOR) | Telemetria é anônima e não usa conta; nenhuma rota de conta existe ainda | Autorização por recurso no servidor: NAO IMPLEMENTADO (a verificar quando as rotas existirem) |

**Risco residual:** alto até existirem hash, rate limit e rotação de refresh. Hoje não há login ativo, então
a exposição é de projeto, não de produção.

---

## 3. Recuperação de conta

**Ativos:** controle da conta, OTP, telefone e e-mail verificados.

**Fronteiras:** F1, e provedores de SMS/e-mail (terceiros, ainda não escolhidos).

Todo o fluxo é **NAO IMPLEMENTADO**. A tabela abaixo registra as ameaças que o desenho precisa cobrir e o
que a Fase 4.2 exige; nenhum item é controle existente.

| STRIDE | Ameaça | Mitigação existente | Residual / aberto |
|---|---|---|---|
| S | SIM swap: atacante recebe o OTP por SMS | Colunas `phone_verified_at` e `email_verified_at` existem no `V3` | Exigir ambos os canais, notificar o outro canal, espera de 24 h cancelável na troca de contato: NAO IMPLEMENTADO |
| T | Troca de telefone/e-mail para sequestrar conta | Nenhuma | Senha + verificação do novo canal: NAO IMPLEMENTADO |
| R | Titular nega ter pedido a recuperação | Nenhuma | Auditoria e notificação em toda recuperação: NAO IMPLEMENTADO |
| I | Descobrir se um contato tem conta | Nenhuma | Respostas idênticas existindo ou não a conta: NAO IMPLEMENTADO |
| D | SMS pumping (custo) e bloqueio de vítimas por excesso de tentativas | Nenhuma | Limite por DDI, CAPTCHA, teto diário de gasto, OTP de 6 dígitos, ≤ 10 min, uso único, 5 tentativas, guardado em hash: NAO IMPLEMENTADO |
| E | Recuperação vira atalho para contornar 2FA | Nenhuma | TOTP opcional e obrigatório para admin: NAO IMPLEMENTADO |

**Risco residual:** não avaliável enquanto o fluxo não existe. Decisão pendente: provedores de SMS e e-mail.

---

## 4. Ingestão de telemetria

**Ativos:** posições de passageiros (dado de localização sensível, LGPD), integridade da posição dos trens,
chave de assinatura do lote, disponibilidade do endpoint.

**Fronteiras:** F1 (cliente adulterável para API), F2.

Fluxo implementado: `POST /v1/telemetry/tokens` (atestação, emite token anônimo) e
`POST /v1/telemetry/batches` (lote assinado).

| STRIDE | Ameaça | Mitigação existente (verificada no código) | Residual / aberto |
|---|---|---|---|
| S | Cliente falso ou script obtém token | `IntegrityVerifier`: o padrão de produção é `RejectAllIntegrityVerifier`, que recusa tudo (fail-closed). O verificador de desenvolvimento só liga com `MALHAVIVA_DEV_INTEGRITY=true`. No Android release, `UnavailableIntegrityTokenProvider` não fornece token, então nenhum lote sai | **Atestação real (Play Integrity, App Attest): NAO IMPLEMENTADO.** Hoje a produção não emite token algum; ao implementar, a verificação precisa de credenciais Google/Apple que ainda não existem |
| S | Falsificar lote de outro dispositivo | Cada token tem chave HMAC-SHA256 própria (32 bytes de `SecureRandom`); o lote exige `X-Timestamp`, `X-Nonce`, `X-Signature` sobre `timestamp\nnonce\nsha256(corpo)`; comparação em tempo constante; `anon_epoch_id` do corpo tem de bater com o do token | A chave é entregue ao cliente na emissão; um aparelho comprometido assina o que quiser (limite inerente, depende da atestação) |
| T | Adulterar corpo em trânsito | Assinatura cobre o hash do corpo; tamanho máximo 128 KiB, leitura limitada a `MAX+1` bytes; JSON estrito; no máximo 120 amostras; campos validados | TLS é responsabilidade da borda: o listener HTTPS do Terraform usa `ELBSecurityPolicy-TLS13-1-2-2021-06`; pinning no app: NAO IMPLEMENTADO. No Android, HTTP puro só é permitido no build debug para `10.0.2.2` e `localhost` |
| T | Posição falsa ou implausível (spoofing, teleporte) | `validateSamples`: rejeita por MOCK, DUPLICATE (seq), ACCURACY (> 50 m), STALE (> 10 min ou > 60 s no futuro), IMPLAUSIBLE_SPEED (> 40 m/s, inclusive velocidade implícita entre pontos), OFF_CORRIDOR (geometria do corredor). Cliente: check-in com raio de 150 m, precisão ≤ 50 m, ≥ 2 leituras em 30 s, ≤ 40 m do traçado, > 15 km/h | **Detecção de mock location no cliente: NAO IMPLEMENTADO** (`HttpBatchUploader` envia `mock_location` sempre `false`; o servidor só recusa se o cliente declarar). Map-matching completo: NAO IMPLEMENTADO |
| T | Poucos sinais falsos movem o trem | `TrainClusterer`: agrupa por proximidade (150 m), usa mediana e descarta outliers; 1 sinal = confiança 0,3; 2 sinais = 0,5; consenso de ≥ 3 sinais confiáveis (`ConsensusPolicy.MIN_TRUSTED`) | Enxame de tokens atestados ainda pode superar o consenso; detecção de enxame e reputação por aparelho: NAO IMPLEMENTADO |
| R | Dispositivo nega ter enviado | Fora de escopo por desenho: telemetria é anônima, sem ligação com conta | Aceito. Não há trilha de autoria por privacidade |
| I | Reidentificação por posição | Id efêmero (`anon_epoch_id` aleatório, nada deriva de conta/aparelho/IP); o cliente descarta pontos fora do corredor e corta 300 m no início e no fim da viagem (`sanitizeTrip`); `LiveHub` só expõe `TrainState` agregado, sem id de passageiro; tabela sem `user_id`; partições por hora com retenção de 24 h e DROP automático (`PartitionMaintenance`); sem log de corpo, token ou coordenadas; menores de 18: compartilhamento bloqueado (`sharingBlocked`, recebido do perfil) | O marcador `sharingBlocked` depende de um perfil de idade que **NAO IMPLEMENTADO** (verificação de idade é Fase 5). Teste automatizado de "nenhum registro contém id de usuário" a confirmar na suíte de Fase 3.5. Tamanho pequeno do conjunto (poucos passageiros) pode permitir reidentificação por trajeto: RIPD pendente (Fase 5) |
| D | Enchente de requisições ou de emissões de token | Rate limit por token (capacidade 10, 0,2/s), por IP (120, 2/s) e, na emissão, 5 por IP (1 a cada 60 s); respostas 429 com `Retry-After`; limite de corpo | **IP de origem:** o código usa `request.local.remoteAddress`, que atrás de balanceador é o IP do balanceador, não o do cliente (há comentário no código). Sem tratamento de `X-Forwarded-For`, todos os clientes dividem o mesmo balde: risco de DoS por esgotamento e de rate limit ineficaz. Chaves de balde nunca expiram (vazamento de memória lento) |
| D | Replay para esgotar nonces ou reaproveitar lote | Janela de ±60 s; nonce de 16 a 64 caracteres; `InMemoryNonceStore` (TTL = 2× a tolerância); assinatura inválida não consome nonce; `seq` duplicado rejeitado | **Nonces e tokens apenas em memória de um nó** (`InMemoryNonceStore`, `InMemoryTelemetryTokenStore`). Reinício apaga tokens (clientes reemitem) e nonces (abre janela de replay de até 60 s). A tabela `telemetry_nonces` existe no `V2` mas não é usada pelo `NonceStore`. Vários nós quebram a proteção: NAO IMPLEMENTADO (armazenamento persistente/compartilhado, ex. Valkey) |
| E | Chegar a dados ou funções além da ingestão | A rota só aceita o lote e devolve contagens; erros usam `Problem` sem detalhes; `StatusPages` registra a exceção no servidor e responde 500 genérico | CORS restrito e cabeçalhos de segurança: NAO IMPLEMENTADO (nenhum plugin instalado no `Application.kt`) |

**Risco residual:** médio. Em produção a ingestão está fechada por padrão (nenhum token é emitido). O risco
real aparece quando a atestação for ligada: depende da robustez dela, de IP real por trás do proxy e de
armazenamento persistente de nonces e tokens.

---

## 5. Distribuição em tempo real (polling e SSE)

**Ativos:** posição agregada dos trens (dado público), disponibilidade, privacidade indireta dos
contribuintes.

**Fronteiras:** F3 (público anônimo para API).

Rotas: `GET /v1/corridors/{corridor}/trains?direction=` e SSE `.../trains/stream`.

| STRIDE | Ameaça | Mitigação existente | Residual / aberto |
|---|---|---|---|
| S | Servidor falso entregando posições erradas | Transporte TLS pela borda; release Android sem cleartext | Pinning com plano de rotação: NAO IMPLEMENTADO |
| T | Dado de trem manipulado para enganar usuários | Posição vem do consenso do `TrainClusterer` (mediana, outliers descartados, confiança); posições velhas (> 30 s) saem do agrupamento | Ver ingestão: enxame atestado pode influenciar |
| R | Não aplicável | Dados públicos e anônimos | Aceito |
| I | Vazar identidade ou trajetória de passageiro | `TrainState` contém só `id` do trem, posição linear, velocidade, nº de sinais e confiança; ids efêmeros nunca saem do `LiveHub`; sem persistência (reinício zera) | Reidentificação com poucos sinais (um único passageiro = trem de 1 sinal) pode expor o deslocamento de uma pessoa em tempo real. Limiar mínimo de sinais para publicar ou k-anonimato de lotação: NAO IMPLEMENTADO (Fase 5) |
| D | Muitas conexões SSE ou consultas | Heartbeat de 15 s; `SharedFlow` com buffer de 256 e descarte do mais antigo (cliente lento não trava o servidor) | Limite de conexões SSE e rate limit nas rotas públicas de leitura: NAO IMPLEMENTADO. Escopo da rota restrito a um corredor e dois sentidos fixos (parâmetros inválidos retornam 400) |
| E | Parâmetros usados para acessar outros recursos | `corridor` e `direction` são validados contra valores conhecidos; sem consulta ao banco por entrada do usuário nessas rotas | CORS não configurado: NAO IMPLEMENTADO (relevante só se houver cliente web) |

**Risco residual:** baixo a médio. Os dados são públicos por natureza; o ponto sensível é a publicação de
trens com 1 sinal.

---

## 6. Widgets e Live Activities (somente dados públicos via App Group)

**Ativos:** posição e ETA de trens (públicos), preferências de linha/estação, tokens de push (futuro).

**Fronteiras:** app, extensão de widget e Live Activity (mesmo App Group), APNs/FCM.

O diretório `ios/` contém só arquivos-base (`App/MalhaVivaApp.swift`, `Widgets/MalhaVivaWidgets.swift`,
pacote `MalhaVivaKit`). **Nenhuma implementação de App Group, Live Activity ou push existe ainda.** A tabela
é requisito de projeto.

| STRIDE | Ameaça | Mitigação existente | Residual / aberto |
|---|---|---|---|
| S | App ou extensão de terceiros lê o contêiner compartilhado | Nenhuma ainda | App Group exclusivo do time de desenvolvimento: NAO IMPLEMENTADO |
| T | Dado adulterado no contêiner compartilhado | Nenhuma ainda | Tratar o contêiner como não confiável e validar na leitura: NAO IMPLEMENTADO |
| R | Não aplicável | Dado público | Aceito |
| I | Segredo ou dado de conta no App Group ou na Live Activity (visível na tela de bloqueio) | Regra de projeto: só dados públicos. Tokens de sessão ficam no Keychain (`AfterFirstUnlockThisDeviceOnly`), nunca no App Group | Regra ainda sem código nem teste: NAO IMPLEMENTADO. Incluir teste que falha se o contêiner receber token ou id de conta. Nunca colocar localização do próprio usuário na Live Activity sem consentimento |
| D | Uso excessivo de atualização consome bateria e orçamento de push | Nenhuma ainda | Orçamento de atualização e pushes coalescidos: NAO IMPLEMENTADO |
| E | Push token usado para enviar alerta falso | Nenhuma ainda | Envio de FCM/APNs só pelo backend, com credenciais em secret manager: NAO IMPLEMENTADO |

**Risco residual:** baixo enquanto só houver dados públicos; **a ser reavaliado** quando a implementação existir.

---

## 7. Admin

**Ativos:** cadastro de estações, linhas e horários, moderação, dados de contas, auditoria.

**Fronteiras:** F4 (operadores para API), console de infraestrutura.

O admin web é **NAO IMPLEMENTADO**; nenhuma rota `/admin` existe no backend. Requisitos de projeto (Fase 4.4):

| STRIDE | Ameaça | Mitigação existente | Residual / aberto |
|---|---|---|---|
| S | Conta de admin comprometida | Nenhuma | 2FA (TOTP) obrigatório, restrição por IP/VPN: NAO IMPLEMENTADO |
| T | Alteração indevida de rede/horários (afeta a validação `OFF_CORRIDOR` e o consenso) | Dados do corredor vêm de migrações versionadas (`V1__network.sql`) | Edição via admin com auditoria e revisão: NAO IMPLEMENTADO |
| R | Admin nega uma alteração | Nenhuma | Trilha de auditoria imutável, sem dado pessoal desnecessário: NAO IMPLEMENTADO |
| I | Admin enxerga dados pessoais além do necessário | Telemetria já é anônima; PII só no plano de identidade | Papéis com privilégio mínimo e mascaramento: NAO IMPLEMENTADO |
| D | Ações administrativas em massa derrubam o serviço | Nenhuma | Confirmação e limites em operações em lote: NAO IMPLEMENTADO |
| E | Usuário comum alcança funções de admin | Nenhuma rota de admin existe (superfície zero hoje) | Autorização no servidor por papel, testes de IDOR: NAO IMPLEMENTADO |

**Risco residual:** nulo hoje por ausência de superfície; torna-se alto se o admin for entregue sem 2FA e auditoria.

---

## 8. Controles transversais (estado verificado)

| Controle | Estado |
|---|---|
| Android: backup desligado (`allowBackup=false`) e `data_extraction_rules` excluindo cloud-backup e device-transfer | Implementado |
| Android: foreground service do tipo `location`, tela de divulgação proeminente antes de rastrear, opt-in desligado por padrão | Implementado (`TrackingScreen`, `TrackingViewModel`) |
| Android: encerramento automático (3 leituras fora do traçado, parado fora de estação 10 min, duração máxima 3 h) | Implementado (`AutoStopPolicy`) |
| Android: intervalo adaptativo 5/10/15 s, envio em lote | Implementado (`SamplingPolicy`) |
| Android: token anônimo e chave só em memória; nunca em log | Implementado (`HttpBatchUploader`) |
| Android: R8, remoção de logs de debug, FLAG_SECURE, Keystore/Tink para segredos de longa vida, sinal de root | NAO IMPLEMENTADO (a verificar na configuração de release) |
| Backend: CORS restrito, cabeçalhos de segurança, tratamento de `X-Forwarded-For` | NAO IMPLEMENTADO |
| Backend: erros genéricos ao cliente (`Problem`), detalhes só no log do servidor | Implementado (`StatusPages`) |
| Infra: RDS criptografado com proteção contra exclusão; listener HTTPS com política TLS 1.2+ | Implementado (`infra/terraform`); revisão contínua pelo Trivy config |
| CI: gitleaks (histórico), Semgrep, Trivy fs e config, osv-scanner, ZAP baseline (manual) | `.github/workflows/security.yml`. MobSF e ZAP em staging: NAO IMPLEMENTADO (não há staging) |
| Dependências: osv-scanner só tem efeito com lockfile Gradle | NAO IMPLEMENTADO (nenhum `gradle.lockfile` no repositório) |

## 9. Pendências priorizadas

1. Atestação real (Play Integrity e App Attest) e credenciais correspondentes. Sem isso a ingestão em produção permanece fechada.
2. IP real do cliente atrás do balanceador (X-Forwarded-For confiável) antes de depender do rate limit por IP.
3. Armazenamento persistente e compartilhado de tokens e nonces (ex. Valkey); usar ou remover a tabela `telemetry_nonces`.
4. Hash argon2id com pepper, login com rate limit, refresh rotativo com detecção de reuso.
5. Fluxo de recuperação (OTP, anti-SIM swap, anti-SMS pumping) e escolha de provedores.
6. Limiar mínimo de sinais para publicar trens e análise de reidentificação (RIPD).
7. Detecção de mock location no cliente Android e na plataforma iOS.
8. Lockfiles Gradle para dar efeito ao osv-scanner; ambiente de staging para o ZAP.
9. Implementação de widgets e Live Activities com App Group restrito a dados públicos e teste de regressão.
10. Admin web com 2FA, auditoria e restrição por IP/VPN, se for entregue.

Este documento deve ser revisado a cada fase e sempre que uma mitigação passar de NAO IMPLEMENTADO a implementada.
