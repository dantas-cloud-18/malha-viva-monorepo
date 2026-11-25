# Checklist de publicação

> **RASCUNHO.** Legenda: **[A]** automatizável ou preparável no repositório; **[D]** só o dono (conta, dinheiro, assinatura, decisão); **[J]** exige revisão jurídica ou do Encarregado; **[M]** exige macOS; **[NV]** regra externa não verificada.
> Regras das lojas mudam: conferir nos consoles no dia do envio.

## Regra verificada (2026-11-25)

Contas **pessoais** do Google Play criadas depois de 13/11/2023 precisam de um **teste fechado com pelo menos 12 testadores, cada um com opt-in contínuo por pelo menos 14 dias**, antes de pedir acesso à produção. Fonte: Ajuda do Google Play Console (support.google.com/googleplay/android-developer/answer/14151465). Contas de **organização** não têm essa exigência [NV: confirmar o critério atual no Console]. A brief fala em "N testadores": o número vigente é 12.

## Fase 0. Decisões que bloqueiam o resto

1. [D][J] Decisão de público (opção A ou B em `PLAY-CLASSIFICACAO-E-PUBLICO.md`): bloquear < 13 nas lojas ou entrar na Política de Famílias.
2. [D] Razão social, CNPJ, endereço, nome do Encarregado e e-mails (preencher todos os `[...]` dos `docs/lgpd/*` e desta pasta).
3. [D] Tipo de conta de desenvolvedor: **organização** (D-U-N-S, evita o teste de 12 testadores/14 dias) ou **pessoal** (mais rápido, mas com o teste).
4. [D] Escolha dos provedores de SMS, e-mail e CAPTCHA (bloqueiam a recuperação e a subida do backend em produção; ver OPERADORES.md).
5. [D] Linhas e cidade da V1 e autorização para citar operadoras (não usar nome ou logo sem autorização).

## Fase 1. Contas e identidades (só o dono)

| # | Item | Quem |
|---|---|---|
| 1 | Conta Google Play Console (taxa única, verificação de identidade, e-mail e telefone de contato) | [D] |
| 2 | Apple Developer Program (assinatura anual; para organização, D-U-N-S) | [D] |
| 3 | App Store Connect: criar o app, bundle ID, SKU, nome | [D] |
| 4 | Play App Signing: chave de upload (gerar e guardar fora do git) | [D] (comando de geração: [A]) |
| 5 | Certificados e perfis iOS (distribuição), App Group, Push (APNs key `.p8`) | [D][M] |
| 6 | Credenciais de **Play Integrity** (projeto Google Cloud vinculado) e **App Attest** no backend | [D] (código: [A]) |
| 7 | Domínio e hospedagem da política, termos, suporte e exclusão de conta | [D] |
| 8 | Contas de teste e de revisão, e Firebase/FCM se houver push | [D] |

Segredos e chaves: nunca no git, nem `.env` (regra do `CLAUDE.md`).

## Fase 2. Texto legal e privacidade

1. [A] Rascunhos prontos em `docs/lgpd/*` e `docs/publicacao/*`.
2. [J] Revisão jurídica da Política de Privacidade, Termos, Política para Menores, RIPD, LIA, Retenção e Operadores.
3. [D] Publicar a política em URL estável e pública, sem placeholders: `[URL DA POLÍTICA]`, `[URL DOS TERMOS]`, `[URL DE EXCLUSÃO DE CONTA]`, `[URL DE SUPORTE]`.
4. [J] Fechar pendências do RIPD que o próprio documento diz serem bloqueantes antes de abrir ingestão em produção: k mínimo de sinais (R2), k-anonimato da lotação (R3), verificação de idade (R4), registro de consentimento (R8).
5. [D][J] Nomear o Encarregado e publicar o canal.

## Fase 3. Build e qualidade (repositório)

1. [A] `ktlintCheck`, `detekt`, testes verdes nos módulos alterados; Android Lint sem críticos.
2. [A] Build de release Android: `.\gradlew.bat :app:bundleRelease` (PowerShell); R8, sem logs de debug, `targetSdk` atual (37), App Bundle (.aab).
3. [A] Gitleaks, Semgrep, osv-scanner, MobSF e Trivy sem críticos/altos (brief 4.4).
4. [A] Conferir permissões do APK mesclado: INTERNET, ACCESS_FINE/COARSE_LOCATION, FOREGROUND_SERVICE(_LOCATION), POST_NOTIFICATIONS; **sem** ACCESS_BACKGROUND_LOCATION.
5. [A] Conferir a tela de divulgação proeminente contra `PLAY-LOCALIZACAO-SEGUNDO-PLANO.md`, item 3.
6. [A] Backend de produção sobe (CAPTCHA real, atestação real); ambiente de revisão com trens simulados e conta de teste.
7. [A][M] iOS: `xcodegen generate`, `swift test`, SwiftLint, SwiftFormat; revisar `Info.plist` gerado e o Privacy Report do Xcode; arquivar e enviar ao TestFlight.
8. [A] Medir bateria em viagem simulada de 30 min e cold start (< 2 s), como pede a brief (2.10).

## Fase 4. Assets das lojas

1. [A] Texto: `FICHA-LOJA.md` (validar contra a build).
2. [A] Screenshots, vídeo de prévia (trem deslizando) e feature graphic, segundo o storyboard; ícones 512 e 1024.
3. [A] Vídeo do fluxo de localização (roteiro em `PLAY-LOCALIZACAO-SEGUNDO-PLANO.md`) no YouTube **não listado** [D publica].
4. [A] Arquivo GPX com a rota para o revisor da Apple.

## Fase 5. Google Play (Console)

1. [D] Criar o app, nome, idioma (pt-BR), gratuito.
2. [D] Conteúdo do app: política de privacidade, anúncios (não), acesso ao app (conta de teste), público-alvo (`PLAY-CLASSIFICACAO-E-PUBLICO.md`), classificação IARC, **Data safety** (`PLAY-DATA-SAFETY.md`), **FGS location** com vídeo, ID de publicidade (não usa), apps de governo/saúde/notícias (não).
3. [D] Ficha principal: textos, gráficos e screenshots.
4. [D] Teste interno com a equipe.
5. [D] **Teste fechado** com 12 ou mais testadores, 14 dias contínuos (se conta pessoal nova). Recrutar testadores com antecedência (grupo do Google ou e-mails). Ver regra verificada acima.
6. [D] Solicitar acesso à produção e responder o questionário do Play.
7. [D] Lançamento escalonado (ex.: 10% → 50% → 100%).

## Fase 6. App Store

1. [D][M] Enviar a build ao **TestFlight** (precisa de Mac com Xcode; sem Mac, usar CI macOS, ex. Xcode Cloud ou GitHub Actions `macos-*`: [A] preparar o workflow, [D] assinar).
2. [D] Teste interno e externo (a revisão beta exige descrição e conta de teste).
3. [D] App Store Connect: App Privacy (`APP-STORE.md` seção 1), classificação etária, categoria, URLs, notas de revisão (seção 5), screenshots, prévia.
4. [D] Capabilities: App Group, Push Notifications (APNs), Background Modes (location), Live Activities.
5. [D] Enviar para revisão; responder à Apple sobre localização em segundo plano, se perguntarem.

## Fase 7. Pós-publicação

1. [D] Acompanhar relatórios de falha e feedback; resposta ao titular em até o prazo da política.
2. [D][J] Rever o formulário Data safety e App Privacy a cada release que mude SDKs, permissões ou telemetria.
3. [D] Fase 7 do projeto: desligar o Apps Script (somente após paridade comprovada).

## Perguntas ao dono

1. Conta de organização ou pessoal no Play? (impacta o teste de 12 testadores por 14 dias)
2. Opção A ou B para público (< 13)? O backend aceita `under_12`.
3. Bloquear cadastro de menores de 13 na build das lojas?
4. Quais linhas e cidade na V1 e há autorização para citar operadoras?
5. O app passa a pedir ACCESS_BACKGROUND_LOCATION? (recomendação: não)
6. Haverá analytics ou crash reporting (muda Data safety e App Privacy)?
7. Provedores de SMS, e-mail e CAPTCHA, e região da hospedagem (transferência internacional)?
8. Quem é o Encarregado e qual é o e-mail de contato público?
9. Bundle ID final e nome do desenvolvedor exibido nas lojas?
10. Há Mac, Xcode Cloud ou CI macOS disponível para TestFlight?
