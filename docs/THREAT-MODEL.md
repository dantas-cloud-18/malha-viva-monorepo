# Modelo de ameaças (STRIDE)

Escopo: Fase 4 do projeto (OWASP MASVS e ASVS L2 como referência). Estado do código verificado no repositório
em 2026-11-24. Este documento separa o que **já existe no código** do que **ainda não existe**. Tudo marcado
**NAO IMPLEMENTADO** é uma lacuna aberta, não uma proteção.

Legenda STRIDE: **S** falsificação de identidade, **T** adulteração, **R** repúdio, **I** vazamento de
informação, **D** negação de serviço, **E** elevação de privilégio.

## 1. Visão geral

Dois planos separados por desenho:

- **Plano de identidade** (contas, login, recuperação, sessão): tabelas `accounts` e `refresh_tokens`
  (`V3__accounts.sql`), `devices` (`V4__sessions.sql`), `otp_codes`, `contact_changes` e `totp_secrets`
  (`V5__recovery.sql`). Nenhuma tabela dele é referenciada pela telemetria.
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
| Hash argon2id com pepper, login (`/auth/token`), refresh rotativo com detecção de reuso, dispositivos (`/me/devices`) | Implementado (`auth/`). Testes de rota usam repositórios em memória |
| Recuperação de conta (OTP, anti-SIM swap, troca de contato com espera de 24 h, limites anti-SMS pumping) | Implementado no backend (`auth/recovery/`), **sem provedor de SMS/e-mail**: em produção o envio é fail-closed, então nenhum OTP sai até a escolha do provedor. Repositórios JDBC do V5 não foram exercitados contra Postgres |
| TOTP (RFC 6238) | Primitivas e serviço prontos e testados (`auth/recovery/Totp.kt`); **NAO IMPLEMENTADO** no login, em rotas e como exigência do admin |
| Atestação real (Play Integrity, App Attest) | NAO IMPLEMENTADO |
| Armazenamento persistente de tokens e nonces | NAO IMPLEMENTADO |
| Widgets/Live Activities (iOS) | NAO IMPLEMENTADO (só arquivo-base `ios/Widgets/MalhaVivaWidgets.swift`) |
| Admin web | NAO IMPLEMENTADO |

---

## 2. Login

**Ativos:** credenciais (senha, hash), contas (`accounts`), tokens de acesso e refresh (`refresh_tokens`,
`devices`), telefone e e-mail do titular.

**Fronteiras:** F1 (app para API), F2 (API para banco).

Fluxo implementado: `POST /v1/auth/accounts`, `/auth/token`, `/auth/refresh`, `/auth/logout`, `GET /me/devices`,
`DELETE /me/devices/{id}` (`auth/AuthRoutes.kt`, `AuthService`, `SessionService`, `LoginGuard`).

| STRIDE | Ameaça | Mitigação existente (verificada no código) | Residual / aberto |
|---|---|---|---|
| S | Credential stuffing, força bruta, senha vazada reutilizada | Política de senha: mínimo 8, máximo 128 pontos de código após NFKC, Pwned Passwords por k-anonimato (só o prefixo de 5 caracteres do SHA-1 sai da máquina), zxcvbn com score mínimo 3, sem regras de composição. Limite de taxa por IP (capacidade 20, 1 a cada 3 s) e por handle (10, 1 a cada 6 s) antes do hash. `LoginGuard`: atraso progressivo (1 s dobrando até 15 min) por par IP+handle e por IP após 3 falhas; CAPTCHA exigido após 5 falhas por handle ou IP; mais de 20 handles distintos de um IP em 10 min bloqueia o IP. Logs só com contagens, nunca IP nem handle | **CAPTCHA real: NAO IMPLEMENTADO.** O verificador padrão é `NoopCaptchaVerifier` (aceita qualquer token não vazio; log de aviso na subida). Estado do `LoginGuard` e dos limitadores só em memória de um nó, sem limpeza de chaves. IP é `request.local.remoteAddress` (ver seção 8) |
| S | Roubo de refresh token | Refresh opaco de 256 bits, guardado só como SHA-256, rotativo a cada uso; reuso de token já trocado (ou troca concorrente) revoga a família inteira; validade absoluta de 30 dias; "Dispositivos conectados" lista e revoga por dispositivo (família) | O access token é JWT HS256 sem lista de revogação: segue válido até vencer (15 min) mesmo após logout, revogação ou recuperação. Rotação do segredo JWT: NAO IMPLEMENTADO |
| T | Adulteração do hash ou do fluxo de login | argon2id (m = 19 MiB, t = 2, p = 1) sobre HMAC-SHA256(pepper, senha), formato PHC, rehash automático quando os parâmetros mudam; pepper e segredo JWT (≥ 32 bytes) vêm do ambiente e a falta deles derruba a subida; JWT exige exatamente o cabeçalho HS256 (sem `alg: none`) e leva só `sub`, `iat`, `exp` | Pepper em secret manager com rotação: NAO IMPLEMENTADO (o código lê variável de ambiente; o Terraform prevê `aws_secretsmanager_secret.app`, mas a injeção na tarefa não foi verificada) |
| R | Usuário nega ter feito login | Nenhuma além do log operacional | Log de auditoria de autenticação sem dado pessoal: NAO IMPLEMENTADO |
| I | Enumeração de contas (login), vazamento de PII em log | Handle inexistente e senha errada dão o mesmo 401 e exatamente uma verificação de hash (hash descartável); corpo inválido nunca é refletido; o token de acesso não carrega handle, telefone ou e-mail | O cadastro responde 409 quando o handle existe: aceito, o handle é público por desenho. Telefone e e-mail nunca disparam conflito no cadastro (ver seção 3) |
| D | Hash caro usado como amplificação de DoS | Limite de taxa por IP e por handle antes do hash; tamanho máximo da senha e do handle checado antes do hash | Limites por IP dependem do IP real (seção 8) |
| E | Token de sessão usado em área indevida (IDOR) | Rotas de conta exigem access token válido; `DELETE /me/devices/{id}` de dispositivo alheio responde 404, igual ao inexistente; telemetria é anônima e não usa token de conta | Autorização por recurso nas demais rotas de conta a verificar quando existirem |

**Risco residual:** médio. Falta CAPTCHA real, IP real atrás do balanceador, estado compartilhado entre nós e
revogação imediata do access token.

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

| STRIDE | Ameaça | Mitigação existente (verificada no código) | Residual / aberto |
|---|---|---|---|
| S | SIM swap: atacante recebe o OTP por SMS | Com os dois canais verificados a conclusão exige os DOIS OTPs (`RecoveryService.allCodesValid`); o canal que não recebe OTP recebe aviso em todo pedido; a conclusão avisa todos os canais; troca de contato exige senha atual, OTP no novo canal e 24 h de espera cancelável, com aviso ao canal antigo no início, no cancelamento e na aplicação; concluir a recuperação cancela trocas pendentes; senha nova revoga todas as sessões e famílias de refresh | Conta com um único canal verificado fica exposta a SIM swap daquele canal (mitigação parcial: aviso ao outro canal, se houver contato gravado). A troca é aplicada de forma preguiçosa, na próxima leitura de contatos ou recuperação da conta: job periódico NAO IMPLEMENTADO. O aviso vai também a contato gravado e ainda não verificado |
| T | Troca de telefone/e-mail para sequestrar conta | Senha atual (argon2id, limite de taxa por conta) + OTP no novo canal + espera de 24 h cancelável (`ContactService`); contato nunca é aplicado sem o OTP do novo canal; contato só vira único depois de verificado (índices parciais do V5) | Roubo do access token junto com a senha ainda permite iniciar a troca; o titular depende de ver o aviso nas 24 h |
| R | Titular nega ter pedido a recuperação | Aviso em todo pedido e na conclusão; log operacional da conclusão, sem dado pessoal | Trilha de auditoria persistente de recuperação e troca de contato: NAO IMPLEMENTADO |
| I | Descobrir se um handle ou contato tem conta | `POST /auth/recovery` responde sempre 202 com o mesmo corpo e cabeçalhos, exista ou não a conta, tenha ou não canal verificado; todo trabalho que depende da conta roda em segundo plano, então o caminho é o mesmo; `complete` responde o mesmo 400 `invalid_or_expired_code` para handle inexistente, OTP errado, vencido ou ausente (conta inexistente gasta um HMAC descartável); CAPTCHA e limites dependem só de IP e do texto do handle; o cadastro nunca acusa telefone ou e-mail já usados; contato já verificado por outra conta falha na confirmação com a mesma resposta genérica; `GET /me/contacts` devolve só flags; telefone e e-mail nunca vão a token, log ou resposta | Igualdade de **tempo** não foi medida: os caminhos fazem trabalho parecido, mas a conclusão de conta existente consulta o banco e a de conta inexistente não (diferença pequena, não testada). Contatos ficam em claro em `accounts` (criptografia em repouso depende do RDS) |
| D | SMS pumping (custo) e bloqueio de vítimas por excesso de tentativas | OTP de 6 dígitos (`SecureRandom`), válido por no máximo 10 min, uso único, 5 tentativas e depois invalidado, guardado só como HMAC-SHA256 (chave `MALHAVIVA_OTP_KEY`; ausente derruba a subida), comparação em tempo constante; limites por DDI, por número ou e-mail (só o SHA-256 vira chave), por conta e por IP/handle; CAPTCHA exigido após 3 pedidos por IP ou handle em 1 h; teto diário de mensagens por canal (`MALHAVIVA_SMS_DAILY_CEILING`, padrão 500; `MALHAVIVA_EMAIL_DAILY_CEILING`, padrão 2000) com parada dura e alerta no log sem dado pessoal | Os limites por número, conta e DDI suprimem o envio em silêncio (a resposta continua 202); o CAPTCHA não depende deles para não vazar existência. **CAPTCHA real: NAO IMPLEMENTADO** (`NoopCaptchaVerifier`). Contadores, limitadores e teto diário só em memória de um nó (reiniciar zera o teto; vários nós multiplicam o limite). O alerta é uma linha de log, sem integração de alarme. Tabela de DDIs parcial (heurística de 1 a 3 dígitos). Rotação da chave do OTP: NAO IMPLEMENTADO. Quem sabe o handle pode gastar tentativas e SMS da vítima dentro dos limites (aceito) |
| E | Recuperação vira atalho para contornar 2FA | Primitivas TOTP (RFC 6238: HMAC-SHA1, 30 s, 6 dígitos, janela de ±1 passo, o mesmo passo não vale duas vezes) validadas com os vetores de teste da RFC; segredo guardado cifrado (AES-GCM, chave derivada de `MALHAVIVA_OTP_KEY` e ligada à conta) e nunca logado | **TOTP não está ligado ao login nem exposto em rotas, e não existe papel `admin`**: obrigatoriedade para admin e flag `totpRequired`: NAO IMPLEMENTADO. A recuperação por OTP de SMS/e-mail não passa por TOTP (decisão aberta para quando o 2FA existir). Códigos de backup do TOTP: NAO IMPLEMENTADO |

**Verificação:** testes de unidade e de rota com repositórios em memória cobrem expiração, limite de tentativas,
uso único, exigência dos dois canais, aviso ao outro canal, ausência de enumeração, revogação de sessões, espera
de 24 h e cancelamento, limites de pumping e teto diário, vetores da RFC 6238 e ausência de OTP em log. Os
repositórios JDBC e a migração `V5` **não foram exercitados contra Postgres** (`DatabaseIntegrationTest` depende
de Docker e não foi executado).

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
| S | Conta de admin comprometida | Primitivas TOTP prontas (seção 3), ainda não ligadas ao login | 2FA (TOTP) obrigatório, restrição por IP/VPN: NAO IMPLEMENTADO |
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
4. CAPTCHA real (login e recuperação), pepper e segredos em secret manager, estado de limites e tetos compartilhado entre nós.
5. Escolha e integração dos provedores de SMS e e-mail (sem eles a recuperação não entrega OTP em produção), teste do V5 contra Postgres e job periódico para aplicar trocas de contato.
6. Limiar mínimo de sinais para publicar trens e análise de reidentificação (RIPD).
7. Detecção de mock location no cliente Android e na plataforma iOS.
8. Lockfiles Gradle para dar efeito ao osv-scanner; ambiente de staging para o ZAP.
9. Implementação de widgets e Live Activities com App Group restrito a dados públicos e teste de regressão.
10. Admin web com 2FA, auditoria e restrição por IP/VPN, se for entregue.

Este documento deve ser revisado a cada fase e sempre que uma mitigação passar de NAO IMPLEMENTADO a implementada.
