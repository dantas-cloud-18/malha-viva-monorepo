# App Store: privacidade, classificação e revisão

> **RASCUNHO. Exige revisão do Encarregado e do jurídico.** Estado do iOS em 2026-11-25: o projeto em `ios/` é um esqueleto (SwiftUI, XcodeGen, módulos `Feature*` vazios; nenhum código de localização, Live Activity ou App Group). **As respostas abaixo descrevem o comportamento projetado e idêntico ao Android**; conferir contra o código real antes de enviar. Declarar algo que o app não faz é tão errado quanto omitir o que faz.

Bundle ID base: `br.malhaviva` (de `ios/project.yml`; app: [BUNDLE ID FINAL]). Política: [URL DA POLÍTICA].

## 1. App Privacy ("nutrition labels")

Tracking (App Tracking Transparency): **Não rastreia.** Nenhum dado é usado para rastreamento nem compartilhado com corretores de dados. `NSUserTrackingUsageDescription` **não** é incluído; `NSPrivacyTracking = false`.

| Tipo de dado (Apple) | Coletado | Vinculado à identidade do usuário | Usado para rastreamento | Finalidades |
|---|---|---|---|---|
| **Contact Info > Email Address** (opcional) | Sim | **Sim** | Não | App Functionality (segurança e recuperação da conta) |
| **Contact Info > Phone Number** (opcional) | Sim | **Sim** | Não | App Functionality (segurança e recuperação da conta) |
| **Identifiers > User ID** (handle e ID da conta) | Sim | **Sim** | Não | App Functionality |
| **Location > Precise Location** (opcional, só durante "Estou no trem/estação") | Sim | **Não.** A posição é enviada com ID efêmero e sem conta, aparelho ou IP; não há como ligar ao usuário (RIPD seção 4) | Não | App Functionality |
| **Other Data > Other Data Types** (faixa etária derivada do cadastro; a data de nascimento é descartada) | Sim | **Sim** | Não | App Functionality (proteção de menores) |

Não coletados: nome, endereço, contatos, fotos, conteúdo gerado pelo usuário, histórico de navegação ou busca, compras, dados de saúde ou financeiros, ID de publicidade, ID de dispositivo (IDFV), diagnósticos e dados de uso/analytics, crash data. [VERIFICAR: se App Attest/Crashlytics/Sentry/APNs token forem adicionados, atualizar: APNs token → Identifiers > Device ID, App Functionality; crash → Diagnostics > Crash Data, Analytics ou App Functionality, "não vinculado".]

Observações:

- A afirmação "Location não vinculado" só vale se o token de telemetria continuar anônimo e sem junção com a conta (RIPD R6). Se qualquer log correlacionar ambos, mudar para "vinculado".
- Operadores (hospedagem, SMS, e-mail, CAPTCHA) são prestadores; a Apple pede para declarar o que **o app e seus parceiros** coletam. A regra segue: se o provedor de CAPTCHA coletar IP ou sinais do aparelho, declarar ("Identifiers", finalidade App Functionality/segurança) [VERIFICAR após escolher o provedor].
- A URL da política vai no App Store Connect (obrigatória) e dentro do app.

## 2. Classificação etária

Questionário da Apple (versão de 2025 com faixas 4+, 9+, 13+, 16+, 18+): responder **Nenhum/Não** para violência, conteúdo sexual, nudez, linguagem, drogas, álcool, tabaco, jogos de azar, horror, medicina, armas, loot boxes. Controle parental: não; chat irrestrito: não; conteúdo gerado pelo usuário: não; publicidade: não; **acesso irrestrito à web: não**.

Resultado esperado: **4+**. Em consistência com a decisão de público (`PLAY-CLASSIFICACAO-E-PUBLICO.md`), **não** usar a categoria Kids e não marcar o app como direcionado a crianças. Ajustar a faixa mínima se a decisão for 13+ (a Apple permite definir idade mínima superior ao resultado do questionário no App Store Connect [VERIFICAR opção disponível]).

Brasil: a Apple exibe a Classificação Indicativa própria; o mesmo questionário alimenta o resultado. Confirmar a tela de "Classificação" no App Store Connect.

## 3. Strings de uso de localização (`Info.plist`)

Adicionadas em `ios/project.yml` (o `Info.plist` é gerado pelo XcodeGen, ver seção 6). Texto, em pt-BR:

| Chave | Texto |
|---|---|
| `NSLocationWhenInUseUsageDescription` | `O Malha Viva usa sua posição somente enquanto você compartilha a viagem, para mostrar onde estão os trens e se a estação está cheia. Sua posição é enviada sem seu nome ou conta e apagada em até 24 horas. Você pode parar a qualquer momento.` |
| `NSLocationAlwaysAndWhenInUseUsageDescription` | **Não incluir** enquanto o app só pedir "Ao usar o app". Incluir somente se o código chamar `requestAlwaysAuthorization()` |

Notas:

- **Pedir só "While In Use"** (a brief orienta "durante o uso"). Para continuar com a tela bloqueada, usar `allowsBackgroundLocationUpdates = true` e `showsBackgroundLocationIndicator = true` (barra azul), que exige `UIBackgroundModes` com `location`, já adicionado no `project.yml`. Isso **não** requer a permissão "Sempre".
- Nunca pedir permissão no primeiro lançamento: só no primeiro toque em "Estou no trem/estação", depois da tela explicativa (brief 2.7).
- Menores de 18: o app **não** deve exibir o pedido de localização.

## 4. Live Activities

- `NSSupportsLiveActivities = YES` (já existe em `ios/project.yml`).
- `NSSupportsLiveActivitiesFrequentUpdates`: **não** adicionar; atualizações por push `liveactivity` do servidor, sem dados pessoais no payload. Se o orçamento de atualização do sistema não bastar, reavaliar.
- A extensão `MalhaVivaWidgets` usa só dados públicos via App Group (brief 4.3). Pendente: App Group `group.[BUNDLE ID]` e capability de Push Notifications (APNs) nos dois targets [IMPLEMENTAR].

## 5. Notas para a revisão (App Review Information)

Texto sugerido (em inglês, para o revisor), com os placeholders preenchidos antes do envio:

```
Malha Viva shows the estimated real-time position of urban trains in Rio de Janeiro, with timetables, station crowding, widgets and a Live Activity.

Account is required only for favorites, alerts and account features; browsing trains does not require an account.
Demo account: user "[USUÁRIO DE TESTE]" / password "[SENHA DE TESTE]" (adult profile, no 2FA).
Backend: [URL DO AMBIENTE DE REVISÃO].

Location: collecting location is optional and off by default. Tap "I'm on the train" (Estou no trem) on the home screen to see the purpose screen and the system prompt (While Using the App). Location is sent only while the user shares a trip, with a temporary trip ID and no account, device ID or IP, and is deleted within 24 hours. A persistent indicator with a Stop button is shown. Users under 18 cannot share location.
To test without riding a train: Xcode > Debug > Simulate Location with the attached GPX route ([ARQUIVO GPX]) along the line "[LINHA]".

Background location: used only while a trip is being shared (UIBackgroundModes: location) so the trip is not interrupted when the screen locks. Stopped by the user, at the end of the corridor, or when idle.

Live Activity / Widgets: start a trip to see the Live Activity with countdown; widgets show the next train and line status.

Privacy: account deletion and data export are in Settings > Privacy. No ads, no tracking, no third-party analytics.
Contact: [E-MAIL DE CONTATO] / [TELEFONE].
```

Preparar: conta de teste com horários e dados visíveis; trens simulados no ambiente de revisão (sem sinal real, o app mostraria só "previsto"); GPX com a rota; vídeo curto (30 a 60 s) do fluxo de localização, opcional mas útil.

## 6. `PrivacyInfo.xcprivacy` (privacy manifest)

Arquivos criados em `ios/App/PrivacyInfo.xcprivacy` e `ios/Widgets/PrivacyInfo.xcprivacy`. Como o `project.yml` aponta `sources: [App]` e `sources: [Widgets]`, o XcodeGen os adiciona como recursos dos respectivos targets (**requer macOS para gerar e conferir**).

Conteúdo (resumo):

- `NSPrivacyTracking = false`; `NSPrivacyTrackingDomains` vazio.
- `NSPrivacyCollectedDataTypes` (app): E-mail, Telefone, User ID, Precise Location (não vinculado), Other Data Types, todos com finalidade App Functionality e sem rastreamento. A extensão não coleta dados.
- `NSPrivacyAccessedAPITypes`: **UserDefaults** (`NSPrivacyAccessedAPICategoryUserDefaults`) com motivos `CA92.1` (leitura/escrita só do próprio app, usada para preferências) e `1C8F.1` (acesso ao App Group, para dados públicos compartilhados com widget e Live Activity). Declarados de forma conservadora porque o App Group está planejado; remover `1C8F.1` se o App Group não existir.
- Não declarados (APIs não usadas hoje): `FileTimestamp`, `SystemBootTime`, `DiskSpace`, `ActiveKeyboards`. **Conferir** com o relatório "Privacy Report" do Xcode (Product > Archive > Generate Privacy Report) e com bibliotecas de terceiros (SDKs com manifesto próprio e assinatura). Hoje o pacote `MalhaVivaKit` não tem dependências externas.

## 7. Informações adicionais

- **Criptografia**: usa só HTTPS/TLS e criptografia do sistema (Keychain, CryptoKit para HMAC); `ITSAppUsesNonExemptEncryption = NO` (adicionado em `project.yml`). O jurídico confirma a isenção; como o app é distribuído no Brasil e o desenvolvedor é brasileiro, avaliar conformidade em outros países de distribuição.
- **Sign in with Apple**: não exigido (login próprio por handle/senha, sem login de terceiros). Revalidar se entrar login social.
- **Exclusão de conta no app**: obrigatória (guideline 5.1.1(v)); existe em Ajustes > Privacidade.
- **Conta/menores**: se a opção A de público (bloquear < 13) for adotada, descrever na revisão.
- **Marca/propriedade**: não usar nomes ou logos de concessionárias sem autorização (guideline 5.2).
