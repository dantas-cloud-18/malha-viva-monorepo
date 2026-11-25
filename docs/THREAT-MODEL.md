# Modelo de ameaças (STRIDE)

Escopo: Fase 4 do projeto (OWASP MASVS e ASVS L2 como referência). Estado do código verificado no repositório
em 2026-11-24, depois da revisão de segurança do backend (seção 10). Este documento separa o que **já existe no
código** do que **ainda não existe**. Tudo marcado **NAO IMPLEMENTADO** é uma lacuna aberta, não uma proteção.

Legenda STRIDE: **S** falsificação de identidade, **T** adulteração, **R** repúdio, **I** vazamento de
informação, **D** negação de serviço, **E** elevação de privilégio.

## 1. Visão geral

Dois planos separados por desenho:

- **Plano de identidade** (contas, login, recuperação, sessão): tabelas `accounts` e `refresh_tokens`
  (`V3__accounts.sql`), `devices` (`V4__sessions.sql`), `otp_codes`, `contact_changes` e `totp_secrets`
  (`V5__recovery.sql`), papel e `mfa` (`V6__roles_totp.sql`), trava de TOTP (`V7__totp_lockout.sql`) e código de
  enrolamento do admin (`V8__admin_enroll_code.sql`). Nenhuma tabela dele é referenciada pela telemetria.
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
| Distribuição em tempo real (polling `GET` e SSE) | Implementado, sem autenticação (dados públicos), com teto de conexões SSE e cache de 1 s do snapshot |
| IP real do cliente atrás do ALB (`XForwardedHeaders`, último item de `X-Forwarded-For`) e helper único `clientIp()` | Implementado (`platform/Http.kt`, `Application.kt`) |
| Corpo JSON limitado a 8 KiB, lido com no máximo 8 KiB + 1 byte, 413 acima disso, depois dos limites por IP | Implementado (`platform/Http.kt`, `auth/AuthRoutes.kt`) |
| Política de senha (tamanho, NFKC, Pwned Passwords k-anonimato, zxcvbn) | Implementado (`auth/PasswordPolicy.kt`) |
| Hash argon2id com pepper, login (`/auth/token`), refresh rotativo com detecção de reuso, dispositivos (`/me/devices`) | Implementado (`auth/`). Testes de rota usam repositórios em memória |
| Access JWT com `iss`, `aud` e `sid`; rotas sensíveis recusam sessão revogada | Implementado (`auth/AccessTokens.kt`, `auth/Principals.kt`) |
| Recuperação de conta (OTP, anti-SIM swap, troca de contato com espera de 24 h, limites e cotas anti-SMS pumping separados por finalidade) | Implementado no backend (`auth/recovery/`), **sem provedor de SMS/e-mail**: em produção o envio é fail-closed, então nenhum OTP sai até a escolha do provedor. Repositórios JDBC do V5 a V8 não foram exercitados contra Postgres |
| TOTP (RFC 6238) no login, enrolamento, desligamento, papel `admin` e rota `/admin/ping` | Implementado (`auth/Mfa.kt`, `auth/MfaRoutes.kt`, `auth/recovery/Totp.kt`), com trava por conta (10 erros, 15 min) |
| CAPTCHA real (hCaptcha, Turnstile ou similar) | **NAO IMPLEMENTADO.** A montagem de produção **não sobe** sem um `CaptchaVerifier` (ver seção 2) |
| Atestação real (Play Integrity, App Attest) | NAO IMPLEMENTADO |
| Armazenamento persistente e compartilhado de tokens, nonces e estado de limites | NAO IMPLEMENTADO (tudo em memória de um nó, agora com expiração e teto) |
| Widgets/Live Activities (iOS) | NAO IMPLEMENTADO (só arquivo-base `ios/Widgets/MalhaVivaWidgets.swift`) |
| Admin web | NAO IMPLEMENTADO (só a rota `GET /admin/ping`) |

---

## 2. Login

**Ativos:** credenciais (senha, hash), contas (`accounts`), tokens de acesso e refresh (`refresh_tokens`,
`devices`), telefone e e-mail do titular.

**Fronteiras:** F1 (app para API), F2 (API para banco).

Fluxo implementado: `POST /v1/auth/accounts`, `/auth/token`, `/auth/login/mfa`, `/auth/refresh`, `/auth/logout`,
`GET /me/devices`, `DELETE /me/devices/{id}` (`auth/AuthRoutes.kt`, `auth/SessionRoutes.kt`, `AuthService`,
`SessionService`, `LoginGuard`).

**Ordem de defesa nas rotas públicas:** limite por IP (sem corpo) → leitura do corpo limitada a 8 KiB (413) → formato
do handle (`^@[a-zA-Z0-9_]{3,20}$`; inválido responde 401 igual a credencial errada, sem criar estado por chave) →
limite por IP+handle → `LoginGuard` → hash. Corpo gigante nunca chega ao parser e nunca consome o limite por handle.

| STRIDE | Ameaça | Mitigação existente (verificada no código) | Residual / aberto |
|---|---|---|---|
| S | Credential stuffing, força bruta, senha vazada reutilizada | Política de senha: mínimo 8, máximo 128 pontos de código após NFKC, Pwned Passwords por k-anonimato (só o prefixo de 5 caracteres do SHA-1 sai da máquina), zxcvbn com score mínimo 3, sem regras de composição. Limite de taxa por IP (capacidade 20, 1 a cada 3 s) e por **IP+handle** (10, 1 a cada 6 s, chave `p:<ip>\|<handle>`) antes do hash: outro IP não consegue esgotar o balde do dono. `LoginGuard`: atraso progressivo (1 s dobrando até 15 min) por par IP+handle e por IP após 3 falhas; CAPTCHA exigido após 5 falhas por handle ou IP (proteção por conta é CAPTCHA, nunca 429 duro); mais de 20 handles distintos de um IP em 10 min bloqueia o IP. Logs só com contagens, nunca IP nem handle. **CAPTCHA fail-closed:** `AuthConfig.fromEnv` aborta a subida sem um `CaptchaVerifier` real; o `NoopCaptchaVerifier` só entra com `MALHAVIVA_DEV_CAPTCHA=true` (log de erro alto). O IP vem de `clientIp()` (último item de `X-Forwarded-For`, ver seção 8) | **CAPTCHA real: NAO IMPLEMENTADO.** Consequência deliberada: até existir uma implementação injetada em `AuthConfig.fromEnv(..., captcha = ...)`, o backend não sobe em produção sem a flag de desenvolvimento (**proposta:** Cloudflare Turnstile ou hCaptcha, `siteverify` server-side com o segredo no Secrets Manager e o IP do cliente). Estado do `LoginGuard` e dos limitadores só em memória de um nó, agora com expiração por inatividade e teto de 200 mil chaves (um atacante com mais chaves distintas que o teto pode expulsar baldes ativos; Valkey com TTL resolve). O segundo passo do login (`/auth/login/mfa`) não pede CAPTCHA (não há campo de token; exigir trancaria o dono): quem contém a força bruta ali é a trava por conta (seção 3, linha E) |
| S | Roubo de refresh token | Refresh opaco de 256 bits, guardado só como SHA-256, rotativo a cada uso; reuso de token já trocado (ou troca concorrente) revoga a família inteira; validade absoluta de 30 dias; "Dispositivos conectados" lista e revoga por dispositivo (família) | Rotação do segredo JWT: NAO IMPLEMENTADO |
| S | Access token vivo depois de logout, revogação ou recuperação | O access (15 min) leva `sid` = id da família de refresh. Rotas **sensíveis** (`/me/contacts*`, `/me/mfa/*`, `/admin/*`, revogar dispositivo) consultam o banco (`devices.revoked_at`) e respondem 401 se a família foi revogada, sem esperar o vencimento; token sem `sid` é recusado nelas | Rotas comuns (`GET /me/devices`) seguem aceitando o access até vencer (15 min, aceito: só leem a própria lista). Uma consulta ao banco por requisição sensível |
| T | Adulteração do hash, do fluxo de login ou do token | argon2id (m = 19 MiB, t = 2, p = 1) sobre HMAC-SHA256(pepper, senha), formato PHC, rehash automático quando os parâmetros mudam; pepper e segredo JWT (≥ 32 bytes) vêm do ambiente e a falta deles derruba a subida; JWT exige exatamente o cabeçalho HS256 (sem `alg: none`), verifica `iss` e `aud` e leva só `iss`, `aud`, `sub`, `iat`, `exp`, `amr`, `scope` (restrito) e `sid`. Token de outro emissor ou público, ou sem esses campos, é recusado | Pepper em secret manager com rotação: NAO IMPLEMENTADO (o código lê variável de ambiente; o Terraform prevê `aws_secretsmanager_secret.app`, mas a injeção na tarefa não foi verificada). Tokens emitidos antes desta versão (sem `iss`/`aud`) deixam de valer: no máximo 15 min de transição |
| R | Usuário nega ter feito login | Nenhuma além do log operacional | Log de auditoria de autenticação sem dado pessoal: NAO IMPLEMENTADO |
| I | Enumeração de contas (login), vazamento de PII em log | Handle inexistente e senha errada dão o mesmo 401 e exatamente uma verificação de hash (hash descartável); corpo inválido nunca é refletido; o token de acesso não carrega handle, telefone ou e-mail | O cadastro responde 409 quando o handle existe: **aceito**, o handle é público por desenho; agora o limite de cadastro é por IP real (antes dividia o IP do ALB). Telefone e e-mail nunca disparam conflito no cadastro (ver seção 3). Handle de formato inválido responde 401 sem chamar o hash (só revela o formato, que é público) |
| D | Hash caro usado como amplificação de DoS; corpo gigante; memória crescendo com chaves da rede | Limite de taxa por IP e por IP+handle antes do hash; tamanho máximo da senha e do handle checado antes do hash; corpo JSON de no máximo 8 KiB lido com no máximo 8 KiB + 1 byte do canal (Content-Length acima do teto nem é lido), 413 sem tocar o parser; limitadores, `LoginGuard`, contadores de recuperação, desafios de MFA, tokens de telemetria e estado por época usam mapas com expiração por inatividade e teto (Caffeine, `platform/ExpiringMap.kt`) | Limites por IP dependem do IP real (seção 8). Estado de um nó só |
| E | Token de sessão usado em área indevida (IDOR) | Rotas de conta exigem access token válido; `DELETE /me/devices/{id}` de dispositivo alheio responde 404, igual ao inexistente; telemetria é anônima e não usa token de conta | Autorização por recurso nas demais rotas de conta a verificar quando existirem |

**Risco residual:** médio. Falta CAPTCHA real (e, por isso, produção só sobe com decisão explícita), estado
compartilhado entre nós e revogação imediata nas rotas não sensíveis.

---

## 3. Recuperação de conta

**Ativos:** controle da conta, OTP, telefone e e-mail verificados, segredo TOTP.

**Fronteiras:** F1, e provedores de SMS/e-mail (terceiros, **ainda não escolhidos**; propostas: Twilio Verify ou
Zenvia para SMS, SES ou Resend para e-mail).

Fluxo implementado (`auth/recovery/`): `POST /v1/auth/recovery` (pedido), `POST /v1/auth/recovery/complete`
(OTP(s) + nova senha), `GET|POST /v1/me/contacts`, `POST /v1/me/contacts/confirm`,
`DELETE /v1/me/contact-changes/{id}`. Contrato em `contracts/openapi.yaml`, tabelas em `V5__recovery.sql`.

**Provedor de mensagens:** `MessageSender` é uma interface. O padrão de produção é `FailClosedMessageSender`,
que recusa todo envio: o pedido segue respondendo o 202 genérico e o erro é logado sem dado pessoal, mas **nenhum
OTP é entregue**, então a recuperação por canal externo não funciona em produção até existir provedor.
`LoggingMessageSender` (dev) só registra que uma mensagem saiu; imprime destino e texto, que contém o OTP,
somente com `MALHAVIVA_DEV_SHOW_OTP=true`.

**Cotas separadas por finalidade (`SendLimits`, `SendPurpose`):** `RECOVERY` (OTP para canal já verificado) tem
DDI, alvo e teto diário reservados (`MALHAVIVA_SMS_RECOVERY_CEILING`, padrão 200; `MALHAVIVA_EMAIL_RECOVERY_CEILING`,
padrão 1000). `SIGNUP` (OTP de verificação do cadastro) tem teto próprio (`MALHAVIVA_SMS_SIGNUP_CEILING`, padrão
200; `MALHAVIVA_EMAIL_SIGNUP_CEILING`, padrão 1000), limite por IP (6, 1 a cada 10 min) e baldes de DDI e de alvo
próprios; o cadastro com telefone ou e-mail **exige CAPTCHA antes de enviar**. Os demais envios (avisos,
verificação e troca de contato do titular) usam o teto geral (`MALHAVIVA_SMS_DAILY_CEILING`, padrão 500;
`MALHAVIVA_EMAIL_DAILY_CEILING`, padrão 2000) e os mesmos baldes de DDI e alvo do cadastro. Esgotar uma finalidade
não bloqueia a recuperação.

| STRIDE | Ameaça | Mitigação existente (verificada no código) | Residual / aberto |
|---|---|---|---|
| S | SIM swap: atacante recebe o OTP por SMS | Com os dois canais verificados a conclusão exige os DOIS OTPs (`RecoveryService.allCodesValid`); o canal que não recebe OTP recebe aviso em todo pedido; a conclusão avisa todos os canais; troca de contato exige senha atual, OTP no novo canal e 24 h de espera cancelável, com aviso ao canal antigo e a todo canal verificado no início, ao canal antigo no cancelamento e na aplicação; concluir a recuperação cancela trocas pendentes; senha nova revoga todas as sessões e famílias de refresh | Conta com um único canal verificado fica exposta a SIM swap daquele canal (mitigação parcial: aviso ao outro canal, se houver contato gravado). A troca é aplicada de forma preguiçosa, na próxima leitura de contatos ou recuperação da conta: job periódico NAO IMPLEMENTADO. O aviso vai também a contato gravado e ainda não verificado |
| T | Plantar um canal de recuperação (ou trocar o telefone/e-mail) com um access token roubado para sequestrar a conta | **Toda** operação de contato fora do cadastro exige a senha atual: adicionar o primeiro contato, verificar o contato gravado (`current_password` em `/me/contacts/confirm` sem `change_id`) e trocar um canal verificado (senha + OTP no novo canal + 24 h cancelável). Todo canal **já verificado** é avisado em qualquer inclusão ou troca. As rotas de contato são sensíveis (recusam sessão revogada). Limite por conta (10, 1 por minuto) antes de ler o corpo e antes do hash da senha | Quem tem o access token **e** a senha ainda inicia a troca; o titular depende de ver o aviso nas 24 h. Conta sem nenhum canal verificado não tem aviso para disparar |
| R | Titular nega ter pedido a recuperação | Aviso em todo pedido e na conclusão; log operacional da conclusão, sem dado pessoal | Trilha de auditoria persistente de recuperação e troca de contato: NAO IMPLEMENTADO |
| I | Descobrir se um handle ou contato tem conta | `POST /auth/recovery` responde sempre 202 com o mesmo corpo e cabeçalhos, exista ou não a conta, tenha ou não canal verificado; todo trabalho que depende da conta roda em segundo plano, então o caminho é o mesmo; handle de formato inválido também responde 202, sem criar estado; `complete` responde o mesmo 400 `invalid_or_expired_code` para handle inexistente, OTP errado, vencido ou ausente, e **iguala o trabalho**: sempre uma leitura de contatos e uma consulta de OTP por canal (a conta inexistente usa `OtpService.probe`, mesma consulta e mesmo HMAC contra uma conta descartável) e o caminho de falha espera no mínimo 150 ms; CAPTCHA e limites dependem só de IP e do texto do handle; o cadastro nunca acusa telefone ou e-mail já usados; contato já verificado por outra conta falha na confirmação com a mesma resposta genérica; `GET /me/contacts` devolve só flags | **Risco residual da igualdade:** a conta existente grava tentativas de OTP (escrita no banco) que a inexistente não grava, e a espera mínima cobre a diferença de **tempo**, não de trabalho; um atacante com medição muito fina e muitas amostras pode, em tese, distinguir. A política de senha (zxcvbn, HIBP) roda antes e é igual nos dois casos. Contatos ficam em claro em `accounts` (criptografia em repouso depende do RDS) |
| D | SMS pumping (custo), bloqueio de vítimas e esgotamento da cota global | OTP de 6 dígitos (`SecureRandom`), válido por no máximo 10 min, uso único, 5 tentativas e depois invalidado, guardado só como HMAC-SHA256 (chave `MALHAVIVA_OTP_KEY`; ausente derruba a subida), comparação em tempo constante; limites por DDI, por número ou e-mail (só o SHA-256 vira chave), por conta e por IP, **em baldes separados por finalidade** (ver acima); recuperação com cota reservada; cadastro com CAPTCHA, cota, DDI, alvo e IP próprios; limite do pedido e da conclusão por IP (antes do corpo) e por **IP+handle** (um atacante em outro IP não esgota o balde da vítima); CAPTCHA exigido após 3 pedidos por IP ou handle em 1 h; teto diário por canal e finalidade com parada dura e alerta no log sem dado pessoal | Os limites por número, conta e DDI suprimem o envio em silêncio (a resposta continua 202); o CAPTCHA não depende deles para não vazar existência. O limite **por número** da recuperação ainda pode ser gasto por quem sabe o handle da vítima (3 envios em 10 min), atrasando a recuperação dela: aceito, pior caso é esperar. **CAPTCHA real: NAO IMPLEMENTADO**. Contadores, limitadores e tetos diários só em memória de um nó (reiniciar zera o teto; vários nós multiplicam o limite). O alerta é uma linha de log, sem integração de alarme. Tabela de DDIs parcial (heurística de 1 a 3 dígitos). Rotação da chave do OTP: NAO IMPLEMENTADO |
| E | Recuperação vira atalho para contornar 2FA; força bruta do TOTP | Recuperar senha **não remove o TOTP**: depois do reset o login segue exigindo o código (obrigatório para admin). Primitivas TOTP (RFC 6238: HMAC-SHA1, 30 s, 6 dígitos, janela de ±1 passo, o mesmo passo não vale duas vezes) validadas com os vetores da RFC; segredo cifrado (AES-GCM, chave derivada de `MALHAVIVA_OTP_KEY` e ligada à conta) e nunca logado. **Trava por conta:** contador persistente em `totp_secrets` (`failed_attempts`, `locked_until`, V7): 10 erros em login, confirmação do enrolamento ou desligamento travam o TOTP da conta por 15 min **qualquer que seja o IP ou o `mfa_token`** (cada senha certa gera token novo, mas o contador é da conta); travada, nem o código certo passa (429 com `Retry-After`); acerto zera. Cada `mfa_token` ainda queima em 5 erros | A recuperação por OTP de SMS/e-mail não passa por TOTP (decisão aberta). Códigos de backup do TOTP: NAO IMPLEMENTADO. Quem sabe a **senha** consegue travar o TOTP do titular por 15 min repetindo erros (negação de serviço, aceita: exige a senha). Com 10 chutes por 15 min a chance de acerto por dia é da ordem de 0,3 % em ataque contínuo (3 códigos válidos por janela em 1 milhão); a defesa de fundo é a senha, o limite por IP+handle e o `LoginGuard` |

**Verificação:** testes de unidade e de rota com repositórios em memória cobrem expiração, limite de tentativas,
uso único, exigência dos dois canais, aviso ao outro canal, ausência de enumeração, forma igual do trabalho de
`complete`, revogação de sessões, espera de 24 h e cancelamento, senha em toda operação de contato, aviso aos
canais verificados, cotas separadas por finalidade, limites de pumping e teto diário, vetores da RFC 6238, trava de
TOTP entre vários `mfa_token` e IPs e ausência de OTP em log. Os repositórios JDBC e as migrações `V5` a `V8`
**não foram exercitados contra Postgres** (`DatabaseIntegrationTest` depende de Docker e não foi executado).

**Risco residual:** alto até existir provedor de mensagens (hoje a recuperação não entrega OTP em produção),
CAPTCHA real e estado compartilhado entre nós. Decisão pendente: provedores de SMS e e-mail.

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
| T | Adulterar corpo em trânsito | Assinatura cobre o hash do corpo; tamanho máximo 128 KiB, leitura limitada a `MAX+1` bytes (emissão de token: 4 KiB); JSON estrito; no máximo 120 amostras; campos validados | TLS é responsabilidade da borda: o listener HTTPS do Terraform usa `ELBSecurityPolicy-TLS13-1-2-2021-06`; pinning no app: NAO IMPLEMENTADO. No Android, HTTP puro só é permitido no build debug para `10.0.2.2` e `localhost` |
| T | Posição falsa ou implausível (spoofing, teleporte) | `validateSamples`: rejeita por MOCK, DUPLICATE (seq), ACCURACY (> 50 m), STALE (> 10 min ou > 60 s no futuro), IMPLAUSIBLE_SPEED (> 40 m/s, inclusive velocidade implícita entre pontos), OFF_CORRIDOR (geometria do corredor). Cliente: check-in com raio de 150 m, precisão ≤ 50 m, ≥ 2 leituras em 30 s, ≤ 40 m do traçado, > 15 km/h | **Detecção de mock location no cliente: NAO IMPLEMENTADO** (`HttpBatchUploader` envia `mock_location` sempre `false`; o servidor só recusa se o cliente declarar). Map-matching completo: NAO IMPLEMENTADO |
| T | Poucos sinais falsos movem o trem | `TrainClusterer`: agrupa por proximidade (150 m), usa mediana e descarta outliers; 1 sinal = confiança 0,3; 2 sinais = 0,5; consenso de ≥ 3 sinais confiáveis (`ConsensusPolicy.MIN_TRUSTED`) | Enxame de tokens atestados ainda pode superar o consenso; detecção de enxame e reputação por aparelho: NAO IMPLEMENTADO |
| R | Dispositivo nega ter enviado | Fora de escopo por desenho: telemetria é anônima, sem ligação com conta | Aceito. Não há trilha de autoria por privacidade |
| I | Reidentificação por posição | Id efêmero (`anon_epoch_id` aleatório, nada deriva de conta/aparelho/IP); o cliente descarta pontos fora do corredor e corta 300 m no início e no fim da viagem (`sanitizeTrip`); `LiveHub` só expõe `TrainState` agregado, sem id de passageiro; tabela sem `user_id`; partições por hora com retenção de 24 h e DROP automático (`PartitionMaintenance`); sem log de corpo, token ou coordenadas; menores de 18: compartilhamento bloqueado (`sharingBlocked`, recebido do perfil) | O marcador `sharingBlocked` depende de um perfil de idade que **NAO IMPLEMENTADO** (verificação de idade é Fase 5). Teste automatizado de "nenhum registro contém id de usuário" a confirmar na suíte de Fase 3.5. Tamanho pequeno do conjunto (poucos passageiros) pode permitir reidentificação por trajeto: RIPD pendente (Fase 5) |
| D | Enchente de requisições ou de emissões de token | Rate limit por token (capacidade 10, 0,2/s), por IP (120, 2/s) e, na emissão, 5 por IP (1 a cada 60 s); o limite por IP é cobrado **antes** de ler o corpo; respostas 429 com `Retry-After`; limite de corpo; o IP é o do cliente real (`clientIp()`, seção 8), não o do ALB; chaves de balde, tokens e estado por época expiram por inatividade e a tabela tem teto | Estado em memória de um nó. Teto de 200 mil chaves: acima disso baldes ativos podem ser expulsos |
| D | Replay para esgotar nonces ou reaproveitar lote | Janela de ±60 s; nonce de 16 a 64 caracteres; `InMemoryNonceStore` (TTL = 2× a tolerância); assinatura inválida não consome nonce; `seq` duplicado rejeitado | **Nonces e tokens apenas em memória de um nó** (`InMemoryNonceStore`, `InMemoryTelemetryTokenStore`). Reinício apaga tokens (clientes reemitem) e nonces (abre janela de replay de até 60 s). A tabela `telemetry_nonces` existe no `V2` mas não é usada pelo `NonceStore`. Vários nós quebram a proteção: NAO IMPLEMENTADO (armazenamento persistente/compartilhado, ex. Valkey) |
| E | Chegar a dados ou funções além da ingestão | A rota só aceita o lote e devolve contagens; erros usam `Problem` sem detalhes; `StatusPages` registra a exceção no servidor e responde 500 genérico | CORS restrito e cabeçalhos de segurança: NAO IMPLEMENTADO |

**Risco residual:** médio. Em produção a ingestão está fechada por padrão (nenhum token é emitido). O risco
real aparece quando a atestação for ligada: depende da robustez dela e de armazenamento persistente de nonces e
tokens.

---

## 5. Distribuição em tempo real (polling e SSE)

**Ativos:** posição agregada dos trens (dado público), disponibilidade, privacidade indireta dos
contribuintes.

**Fronteiras:** F3 (público anônimo para API).

Rotas: `GET /v1/corridors/{corridor}/trains?direction=` e SSE `.../trains/stream`.

| STRIDE | Ameaça | Mitigação existente | Residual / aberto |
|---|---|---|---|
| S | Servidor falso entregando posições erradas | Transporte TLS pela borda; release Android sem cleartext | Pinning com plano de rotação: NAO IMPLEMENTADO (ver seção 8) |
| T | Dado de trem manipulado para enganar usuários | Posição vem do consenso do `TrainClusterer` (mediana, outliers descartados, confiança); posições velhas (> 30 s) saem do agrupamento | Ver ingestão: enxame atestado pode influenciar |
| R | Não aplicável | Dados públicos e anônimos | Aceito |
| I | Vazar identidade ou trajetória de passageiro | `TrainState` contém só `id` do trem, posição linear, velocidade, nº de sinais e confiança; ids efêmeros nunca saem do `LiveHub`; sem persistência (reinício zera) | Reidentificação com poucos sinais (um único passageiro = trem de 1 sinal) pode expor o deslocamento de uma pessoa em tempo real. Limiar mínimo de sinais para publicar ou k-anonimato de lotação: NAO IMPLEMENTADO (Fase 5) |
| D | Muitas conexões SSE; polling que recalcula o agrupamento sob lock global | Heartbeat de 15 s; `SharedFlow` com buffer de 256 e descarte do mais antigo (cliente lento não trava o servidor). **Teto de conexões SSE abertas: global (2000) e por IP (8)**; acima dele a conexão não abre (503 com `Retry-After`) e a vaga volta ao fechar o stream. **Snapshot com cache de 1 s** (`LiveHub.snapshot`): o polling não roda o clustering sob o lock a cada requisição; toda atualização recalcula e renova o instante, então dado novo aparece na hora. Escopo da rota restrito a um corredor e dois sentidos fixos (parâmetros inválidos retornam 400) | Rate limit nas rotas públicas de leitura (`GET`): NAO IMPLEMENTADO (o cache de 1 s tira o custo do clustering, não o de serialização). Tetos por instância (vários nós multiplicam). A liberação da vaga quando o cliente desconecta depende do cancelamento do Netty; o motor de teste não propaga o fechamento do cliente, então só o fechamento normal do stream foi provado em teste |
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

O admin web é **NAO IMPLEMENTADO**; a única rota de admin é `GET /v1/admin/ping` (prova do controle de acesso).
Papel `admin` (`accounts.role`) exige TOTP: o acesso a `/admin/*` pede papel admin **e** access com `amr` `otp` **e**
sessão viva (rota sensível). Requisitos de projeto restantes (Fase 4.4):

| STRIDE | Ameaça | Mitigação existente | Residual / aberto |
|---|---|---|---|
| S | Conta de admin comprometida (inclusive só a senha) | TOTP obrigatório (login em dois passos; admin não desliga o TOTP); sem TOTP o login só devolve token restrito `mfa_enroll_only`. **Primeiro enrolamento exige código fora de banda** (`admin_enroll_hash`, V8): só a senha nunca basta para registrar o próprio segundo fator. Trava de TOTP por conta (seção 3) | Restrição por IP/VPN: NAO IMPLEMENTADO. A promoção a admin é operação manual no banco (procedimento abaixo) |
| T | Alteração indevida de rede/horários (afeta a validação `OFF_CORRIDOR` e o consenso) | Dados do corredor vêm de migrações versionadas (`V1__network.sql`) | Edição via admin com auditoria e revisão: NAO IMPLEMENTADO |
| R | Admin nega uma alteração | Nenhuma | Trilha de auditoria imutável, sem dado pessoal desnecessário: NAO IMPLEMENTADO |
| I | Admin enxerga dados pessoais além do necessário | Telemetria já é anônima; PII só no plano de identidade | Papéis com privilégio mínimo e mascaramento: NAO IMPLEMENTADO |
| D | Ações administrativas em massa derrubam o serviço | Nenhuma | Confirmação e limites em operações em lote: NAO IMPLEMENTADO |
| E | Usuário comum alcança funções de admin; senha de admin basta para virar admin com 2FA do atacante | Autorização no servidor por papel e `amr`; teste de que usuário comum com TOTP leva 403; enrolamento do admin só com o código de uso único | Autorização por recurso e testes de IDOR nas rotas de admin futuras: NAO IMPLEMENTADO |

### Procedimento de promoção a admin (código de enrolamento fora de banda)

Escolhida a opção "código de enrolamento de uso único" (e não "honrar o papel só após o TOTP"), porque a segunda
deixa quem tem só a senha enrolar o próprio TOTP primeiro e depois ser honrado como admin.

1. O operador gera um código com 128 bits de entropia **fora do banco e fora do canal da senha**, por exemplo
   `openssl rand -hex 16`, e o entrega ao futuro admin por um canal separado (em pessoa ou mensagem direta
   verificada). O código não é guardado em lugar nenhum.
2. Em sessão `psql` auditada, uma única operação grava o papel e o hash (SHA-256 em hex minúsculo do código em UTF-8):

   ```sql
   UPDATE accounts
      SET role = 'admin',
          admin_enroll_hash = encode(sha256(convert_to('<codigo>', 'UTF8')), 'hex')
    WHERE lower(handle) = lower('@handle_do_admin');
   ```

3. O admin entra com a senha, recebe `mfa_enrollment_required` com o token restrito, chama
   `POST /v1/me/mfa/totp` com `{"enrollment_code": "<codigo>"}` e confirma em `POST /v1/me/mfa/totp/confirm`. O
   hash é zerado quando o enrolamento **começa** (uso único). Se o admin perder o segredo antes de confirmar, o
   operador repete o passo 2 com um código novo.
4. Admin promovido **sem** `admin_enroll_hash` não consegue enrolar (falha fechada, 403 `enrollment_code_required`).
   Para despromover, `UPDATE accounts SET role = 'user'`; o TOTP já enrolado permanece.

**Risco residual:** baixo hoje (superfície mínima); torna-se alto se o admin for entregue sem auditoria e restrição
por IP/VPN. O código só protege o enrolamento: quem tem senha **e** o código, ou o aparelho com o TOTP, é admin.

---

## 8. Controles transversais (estado verificado)

| Controle | Estado |
|---|---|
| **IP do cliente atrás do ALB** | Implementado. `XForwardedHeaders` com `useLastProxy()` (`Application.installForwardedHeaders`): o ALB **acrescenta** o IP de quem conectou ao fim de `X-Forwarded-For`, então o último item é o cliente real e o que veio antes pode ser forjado e é ignorado. Todos os usos passam por **um** helper, `ApplicationCall.clientIp()` (`request.origin.remoteAddress`). Só é seguro porque o grupo de segurança da aplicação (`infra/terraform/network.tf`, `aws_security_group.app`) aceita a porta 8080 **apenas do grupo do ALB** (`security_groups = [aws_security_group.alb.id]`, sem CIDR), então ninguém alcança a app sem passar pelo ALB; o ALB usa `drop_invalid_header_fields = true` e o modo padrão de `X-Forwarded-For` (append). Se um CloudFront ou outro proxy entrar na frente, rever `skipLastProxies` |
| **Corpo JSON limitado** | Implementado: 8 KiB nas rotas de conta e recuperação (`platform/Http.kt`), 128 KiB no lote de telemetria, 4 KiB na emissão de token; o limite por IP (que não depende do corpo) é cobrado antes da leitura |
| Android: backup desligado (`allowBackup=false`) e `data_extraction_rules` excluindo cloud-backup e device-transfer | Implementado |
| Android: foreground service do tipo `location`, tela de divulgação proeminente antes de rastrear, opt-in desligado por padrão | Implementado (`TrackingScreen`, `TrackingViewModel`) |
| Android: encerramento automático (3 leituras fora do traçado, parado fora de estação 10 min, duração máxima 3 h) | Implementado (`AutoStopPolicy`) |
| Android: intervalo adaptativo 5/10/15 s, envio em lote | Implementado (`SamplingPolicy`) |
| Android: token anônimo e chave só em memória; nunca em log | Implementado (`HttpBatchUploader`) |
| Android: R8, remoção de logs de debug, FLAG_SECURE, Keystore/Tink para segredos de longa vida, sinal de root | NAO IMPLEMENTADO (a verificar na configuração de release) |
| **Android: certificate pinning desligado por padrão** | **ABERTO, fora do escopo desta revisão** (ainda não há domínio real para fixar). Fica listado até existir domínio, plano de rotação e pinning ligado no release |
| Backend: CORS restrito e cabeçalhos de segurança | NAO IMPLEMENTADO |
| Backend: erros genéricos ao cliente (`Problem`), detalhes só no log do servidor | Implementado (`StatusPages`) |
| Infra: RDS criptografado com proteção contra exclusão; listener HTTPS com política TLS 1.2+ | Implementado (`infra/terraform`); revisão contínua pelo Trivy config |
| CI: gitleaks (histórico), Semgrep, Trivy fs e config, osv-scanner, ZAP baseline (manual) | `.github/workflows/security.yml`. MobSF e ZAP em staging: NAO IMPLEMENTADO (não há staging) |
| Dependências: osv-scanner lê os lockfiles do Gradle (`gradle.lockfile`, inclui Caffeine e `ktor-server-forwarded-header`) | Implementado |

## 9. Pendências priorizadas

1. **CAPTCHA real** (Turnstile ou hCaptcha) injetado em `AuthConfig.fromEnv`; sem ele produção só sobe com `MALHAVIVA_DEV_CAPTCHA=true`, que não deve ser usado em produção.
2. Atestação real (Play Integrity e App Attest) e credenciais correspondentes. Sem isso a ingestão em produção permanece fechada.
3. Armazenamento persistente e compartilhado de tokens, nonces e estado de limites/tetos (ex. Valkey); usar ou remover a tabela `telemetry_nonces`.
4. Escolha e integração dos provedores de SMS e e-mail (sem eles a recuperação não entrega OTP em produção), teste das migrações V5 a V8 contra Postgres e job periódico para aplicar trocas de contato.
5. Pepper e segredos em secret manager, com rotação (pepper, segredo JWT, chave de OTP).
6. Limiar mínimo de sinais para publicar trens e análise de reidentificação (RIPD); rate limit nas rotas públicas de leitura.
7. Detecção de mock location no cliente Android e na plataforma iOS.
8. **Certificate pinning no Android** (aberto): ligar quando houver domínio real e plano de rotação.
9. CORS restrito e cabeçalhos de segurança; trilha de auditoria de autenticação, recuperação e troca de contato.
10. Ambiente de staging para o ZAP; implementação de widgets e Live Activities com App Group restrito a dados públicos e teste de regressão.
11. Admin web com auditoria e restrição por IP/VPN, se for entregue (o 2FA e o código de enrolamento já existem).

## 10. Revisão de segurança do backend (resumo do que mudou)

| # | Achado | Situação |
|---|---|---|
| 1 | IP do ALB usado como IP do cliente (limites, guardas) | Corrigido: `XForwardedHeaders` (último item) e `clientIp()` único; SG da app só aceita o ALB (verificado no Terraform) |
| 2 | Corpo JSON lido inteiro, antes do limite, sem teto | Corrigido: leitor limitado (8 KiB + 1 byte), 413, limites por IP antes do corpo; chave dependente do corpo (handle) vem depois |
| 3 | Primeiro contato de recuperação sem reautenticação | Corrigido: senha em toda operação de contato fora do cadastro, aviso aos canais verificados |
| 4 | Força bruta do TOTP sem limite por conta | Corrigido: trava persistente por conta (V7), 10 erros, 15 min, em login, confirmação e desligamento |
| 5 | CAPTCHA no-op em produção e trava de vítima por handle | Corrigido: subida falha sem verificador real (`MALHAVIVA_DEV_CAPTCHA=true` só em desenvolvimento); limites por IP+handle; **CAPTCHA real continua pendente** |
| 6 | Teto global de SMS/e-mail esgotável por qualquer um | Corrigido: cotas por finalidade, reserva da recuperação, cadastro com CAPTCHA e limites por IP, DDI e alvo próprios |
| 7 | Mapas em memória sem expiração | Corrigido: `ExpiringMap` (Caffeine) com expiração por inatividade e teto; formato do handle checado antes de criar estado |
| 8 | SSE e snapshot sem limite | Corrigido: teto de conexões (global e por IP) e cache de 1 s do snapshot; rate limit de leitura segue aberto |
| 9 | Access token sobrevive à revogação e sem `iss`/`aud` | Corrigido: `iss`, `aud` e `sid` verificados; rotas sensíveis consultam a família no banco |
| 10 | Admin sem TOTP enrola só com a senha | Corrigido: código de enrolamento fora de banda de uso único (V8) e procedimento documentado |
| 11 | Enumeração em `complete` | Mitigado: trabalho de repositório igualado e espera mínima; risco residual documentado (seção 3, linha I). O 409 do cadastro é inerente e aceito |
| 12 | Pinning do Android desligado por padrão | **Aberto, fora do escopo** (sem domínio real); listado em 8 e 9 |

Este documento deve ser revisado a cada fase e sempre que uma mitigação passar de NAO IMPLEMENTADO a implementada.
