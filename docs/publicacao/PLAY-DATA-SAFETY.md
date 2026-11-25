# Google Play: Formulário de Segurança dos Dados (Data safety)

> **RASCUNHO. Exige revisão do Encarregado e do jurídico antes de enviar.** Respostas baseadas em `contracts/openapi.yaml`, `docs/lgpd/*`, `docs/THREAT-MODEL.md` e no código Android em 2026-11-25. O formulário é uma declaração pública: deve refletir o que o app **faz** na versão enviada, não o que está projetado. Itens com [VERIFICAR] dependem de decisões em aberto.

Política de privacidade (obrigatória no Play Console): [URL DA POLÍTICA].

## 1. Visão geral

| Pergunta do Play | Resposta | Base |
|---|---|---|
| O app coleta ou compartilha algum dos tipos de dados exigidos? | **Sim** | Conta, localização (opcional), identificadores |
| Todos os dados coletados são criptografados em trânsito? | **Sim** | TLS 1.2+; `usesCleartextTraffic="false"` no manifesto |
| Há forma de o usuário pedir a exclusão dos dados? | **Sim** | Exclusão de conta no app (Ajustes > Privacidade, `DELETE /me`) e por [E-MAIL DO ENCARREGADO]. O Play exige também uma URL web para pedido de exclusão: [URL DE EXCLUSÃO DE CONTA] |
| Política de Famílias | Não aplicável: público não infantil (ver `PLAY-CLASSIFICACAO-E-PUBLICO.md`) | |

"Compartilhar", no Play, é transferir a terceiros. Envio a **prestadores que tratam dados em nosso nome (operadores)**, sob contrato, é exceção prevista pelo Play e **não** é declarado como compartilhamento. Confirmar com o jurídico que os contratos de OPERADORES.md atendem a essa exceção [VERIFICAR].

## 2. Tipos de dados

Coletado = sai do aparelho para o servidor. Compartilhado = enviado a terceiros que não sejam operadores.

### 2.1 Localização

| Item | Coletado | Compartilhado | Obrigatório/opcional | Efêmero | Finalidades | Observações |
|---|---|---|---|---|---|---|
| Localização aproximada | Sim | Não | **Opcional** (usuário liga "Estou no trem" ou "Estou na estação") | Não (ver abaixo) | Funcionalidade do app; Prevenção de fraude, segurança e conformidade | O app declara COARSE junto de FINE por exigência do Android 12+; os pontos enviados são precisos |
| Localização precisa | Sim | Não | **Opcional** | Não | Funcionalidade do app; Prevenção de fraude, segurança e conformidade | ID efêmero de viagem, sem conta, aparelho ou IP. Posições brutas com TTL de 24 h (`telemetry_points`). Bloqueada para menores de 18 |

Sobre "efêmero": no Play significa dado só em memória, não retido além da solicitação em tempo real. As posições ficam até 24 h em disco para consenso e antifraude, então a resposta conservadora é **Efêmero = Não**, descrevendo o TTL de 24 h na política. [VERIFICAR com o Encarregado]

### 2.2 Informações pessoais

| Tipo no Play | Coletado | Compartilhado | Obrigatório/opcional | Finalidades | Observações |
|---|---|---|---|---|---|
| Nome | Não | | | | O handle (`@usuario`) entra em "IDs de usuário" |
| Endereço de e-mail | Sim | Não | **Opcional** (só segurança e recuperação) | Gerenciamento de conta; Prevenção de fraude, segurança e conformidade | Nunca para marketing |
| IDs de usuário (handle, ID da conta) | Sim | Não | **Obrigatório** | Funcionalidade do app; Gerenciamento de conta | Handle é público por desenho |
| Número de telefone | Sim | Não | **Opcional** (só segurança e recuperação) | Gerenciamento de conta; Prevenção de fraude, segurança e conformidade | |
| Endereço, raça/etnia, orientação sexual, crenças | Não | | | | |
| Outras informações pessoais (faixa etária) | Sim | Não | **Obrigatório** | Gerenciamento de conta; Conformidade | Ver nota |
| Outras informações pessoais (gênero) | Sim | Não | **Opcional** | Análises | Ver nota de gênero |

Nota (gênero): campo opcional no cadastro (`gender` em `openapi.yaml`; masculino, feminino, não-binário, prefiro não dizer; vazio = não informado), editável e apagável em "Privacidade e meus dados". Usado só em estatística agregada e anônima (buckets com menos de 5 contas suprimidos), nunca por pessoa nem junto de localização ou viagens. Apagado na exclusão da conta. Fica em "Outras informações pessoais" (o Play lista identidade de gênero ali).

Nota (idade): `POST /auth/accounts` exige `birth_date`; o servidor deriva a faixa (`under_12`, `teen_12_17`, `adult`), **descarta a data** e grava só a faixa. O Play não tem categoria própria de idade; declarar em "Outras informações pessoais" evita subdeclarar. Para menores de 12 anos, o contato do responsável (telefone ou e-mail) também é coletado (`/auth/parental-consent/*`), com as mesmas categorias acima e finalidade "Conformidade". [VERIFICAR contra o fluxo final]

### 2.3 Demais categorias

| Categoria | Resposta |
|---|---|
| Informações financeiras | Não coleta |
| Saúde e condicionamento físico | Não coleta |
| Mensagens (e-mails, SMS, chat) | Não coleta. O backend **envia** SMS/e-mail de verificação por operador, mas não lê mensagens do usuário |
| Fotos e vídeos, áudio, arquivos, calendário, contatos | Não coleta |
| Atividade no app, navegação na web | Não coleta hoje (a telemetria é só posição). Revisar se entrar analytics de uso |
| Registros de falhas e diagnósticos | Não coleta enquanto não houver SDK de crash/analytics [VERIFICAR: se entrar Crashlytics/Sentry, declarar] |

### 2.4 Identificadores de dispositivo ou outros

| Item | Coletado | Compartilhado | Obrigatório | Finalidades | Observações |
|---|---|---|---|---|---|
| IDs de dispositivo ou outros | Sim | Não | Obrigatório para compartilhar posição | Prevenção de fraude, segurança e conformidade | O token do **Play Integrity** (`integrity_token`) vai ao backend para emitir o token de telemetria e **nunca é gravado**. Rótulo do aparelho na lista "Dispositivos conectados" (sessão). Não coletamos ID de publicidade, IMEI nem Android ID [VERIFICAR no código final] |
| Token de push (FCM) | **Não implementado ainda** | | | | Quando existir, declarar em "IDs de dispositivo ou outros", finalidade Funcionalidade |

### 2.5 SDKs

Rever `./gradlew dependencies` antes do envio. Play Integrity e Play Services Location são do Google e entram como parte do serviço. Se o provedor de CAPTCHA tiver SDK no app ([PROVEDOR, A DEFINIR]), incluir o que ele coleta.

## 3. Práticas de segurança

| Pergunta | Resposta |
|---|---|
| Criptografia em trânsito | Sim |
| Pedido de exclusão | Sim |
| Política de Famílias | Não se aplica |
| Validação independente de segurança (MASA) | Não por ora. Não declarar sem laudo |

## 4. Coerência com o código

| Afirmação | Evidência |
|---|---|
| App tem só INTERNET; localização, FGS e notificações vêm do módulo `feature/tracking` e mesclam no APK | `android/app/src/main/AndroidManifest.xml`, `android/feature/tracking/src/main/AndroidManifest.xml` |
| Sem `ACCESS_BACKGROUND_LOCATION` | Ausente nos dois manifestos |
| Sem backup automático | `allowBackup="false"` |
| Telemetria sem conta | `POST /telemetry/tokens` com `security: []`; "não aceita nem grava token de conta" |
| Lote sem identificador de passageiro | `POST /telemetry/batches`, schema com campos fechados |
| TTL de 24 h | `docs/lgpd/RETENCAO.md` |
| Data de nascimento descartada | `birth_date` e `AgeBracket` em `openapi.yaml` |
| Exportação e exclusão | `GET /me/export`, `DELETE /me` em `openapi.yaml` |

## 5. Decisões e riscos

1. **Compartilhamento:** nenhuma transferência a terceiros como controlador. Se o jurídico entender que algum operador não cumpre a exceção, declarar "Compartilhado" para os tipos envolvidos.
2. **Menores:** o bloqueio de localização para menores de 18 depende da faixa autodeclarada (RIPD R4). O formulário pode dizer "coleta opcional" sem afirmar verificação de idade.
3. Posições não são exportáveis nem apagáveis por conta (não ligam à conta). Informar isso na política e no pedido de exclusão.
4. Rever o formulário a cada release que mude SDKs, permissões ou telemetria. Divergência entre o formulário e o comportamento gera reprovação ou remoção.
