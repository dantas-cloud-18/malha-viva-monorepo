# Google Play: Localização em segundo plano e serviço em primeiro plano

> **RASCUNHO.** Estado verificado em 2026-11-25. O vídeo e os textos precisam ser produzidos e revistos pelo dono do produto.

## 0. Achado importante: o que o app realmente pede

- Manifesto de `android/feature/tracking`: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`; `TrackingService` com `foregroundServiceType="location"`.
- **Não existe `ACCESS_BACKGROUND_LOCATION`** em nenhum manifesto. A coleta é por **serviço em primeiro plano** iniciado pelo usuário, com notificação persistente e botão **Parar**.
- Consequência nas políticas do Google Play:
  1. **Declaração de localização em segundo plano: não se aplica e a permissão não deve ser pedida.** Pedir sem necessidade é causa comum de rejeição (a brief também diz "prefira durante o uso se bastar").
  2. **Declaração de permissões de serviço em primeiro plano (tipo `location`): aplica-se** (Android 14+; `targetSdk` 37). Exige descrição do caso de uso e **vídeo**.
  3. **Divulgação proeminente** no app antes do prompt de permissão: já existe (RIPD R8). Conferir o texto contra o item 3.
- Os textos do item 2 são contingência: só usar se `ACCESS_BACKGROUND_LOCATION` for adicionado, o que obriga a rever RIPD e Data safety.

## 1. Declaração de FGS tipo `location` (Play Console > Conteúdo do app > Permissões de serviço em primeiro plano)

**Tipo:** Localização (`location`).

**Descrição da funcionalidade** (confirmar o limite de caracteres do campo):

> O Malha Viva estima a posição dos trens e a lotação das estações a partir de passageiros que escolhem colaborar. Quando o usuário toca em "Estou no trem" ou "Estou na estação" e confirma a tela de aviso, o app inicia um serviço em primeiro plano que lê a posição do aparelho durante a viagem e envia lotes ao servidor. O serviço só roda por ação explícita do usuário, mostra notificação persistente com o botão "Parar" e termina ao toque, ao sair da linha, ao ficar parado fora de estação ou no tempo máximo de viagem. Sem ele, a coleta pararia assim que a tela fosse bloqueada ou o app saísse da tela.

**Por que é funcionalidade principal e não há alternativa:**

- A viagem dura dezenas de minutos e o passageiro guarda o celular ou bloqueia a tela. As leituras a cada 5 a 15 s precisam continuar.
- WorkManager e alarmes não garantem a frequência nem a visibilidade que o usuário precisa para parar.
- É a forma de entregar o valor central do app (posição colaborativa) com transparência: notificação visível e controle imediato.

**Impacto se o serviço não rodar:** a viagem é interrompida quando a tela apaga e as estimativas ficam sem dados.

## 2. Contingência: declaração de localização em segundo plano

> Use **só** se `ACCESS_BACKGROUND_LOCATION` for adicionado.

**Funcionalidade principal:**

> "Estou no trem" compartilha, de forma opcional e anônima, a posição do passageiro durante a viagem para estimar onde estão os trens. Precisa continuar quando o usuário usa outros apps ou bloqueia a tela, pois a viagem dura de 20 a 90 minutos. Começa só depois que o usuário lê o aviso e toca em "Estou no trem", é indicada por notificação persistente e termina com um toque em "Parar".

**Por que "durante o uso" não basta:** [PREENCHER com resultado de testes em aparelhos reais; hoje o FGS cobre o caso, então a justificativa não existe e a declaração seria negada].

**Dados:** posição com identificador temporário de viagem, sem conta, aparelho ou IP; pontos fora da linha e início e fim da viagem descartados no aparelho; posições apagadas em até 24 h; menores de 18 não usam.

## 3. Divulgação proeminente (referência para conferir a tela existente)

Requisitos do Google: dentro do app (não só na política), antes do prompt de sistema; dizer quais dados, para quê e se são compartilhados; botões claros de aceitar e recusar.

Modelo que a tela deve equivaler, sem prometer o que não existe:

> **Compartilhar sua posição durante a viagem?**
> O Malha Viva coleta a posição do seu aparelho **enquanto você compartilha a viagem, inclusive com a tela bloqueada**, para mostrar onde os trens estão e se a estação está cheia. Sua posição é enviada sem seu nome, conta ou telefone, o começo e o fim da viagem são descartados e tudo é apagado em até 24 horas. Não vendemos nem compartilhamos com anunciantes. Você pode parar a qualquer momento pelo botão "Parar" na notificação.
> [Agora não] [Continuar]

Conferir em `android/feature/tracking` (TrackingScreen) e nas strings de `feature/auth`. Se o texto atual não disser "com a tela bloqueada" e "serviço em primeiro plano", ajustar (tarefa Android, fora desta pasta).

## 4. Roteiro do vídeo (shot a shot)

Requisitos do Google: aparelho real ou emulador com a versão enviada; mostrar a divulgação proeminente, o prompt de permissão e a funcionalidade que usa o serviço; legendas ou narração (pt-BR, com legendas em inglês recomendadas); YouTube **não listado** (não privado, sem restrição de idade); 30 a 60 s.

| # | Tempo | Tela | Ação | Legenda |
|---|---|---|---|---|
| 1 | 0:00 | Home com mapa e trem deslizando | Mostrar que funciona sem permissão | "O app mostra trens e horários sem pedir localização." |
| 2 | 0:05 | Home | Tocar em "Estou no trem" | "Compartilhar é opcional e começa por uma ação do usuário." |
| 3 | 0:10 | **Divulgação proeminente** | Manter 4 s para leitura | "O aviso explica dados, uso e apagamento, inclusive com a tela bloqueada." |
| 4 | 0:16 | Mesma tela | Tocar em "Continuar" | |
| 5 | 0:18 | **Prompt do sistema de localização** | Escolher "Durante o uso do app" | "Permissão concedida apenas durante o uso." |
| 6 | 0:22 | Prompt de notificações (Android 13+) | Permitir | "Notificação necessária ao serviço em primeiro plano." |
| 7 | 0:26 | App compartilhando | Mostrar indicador persistente e botão Parar | "Indicador visível o tempo todo." |
| 8 | 0:30 | Bloquear a tela e abrir a sombra de notificações | Mostrar a notificação do serviço com "Parar" | "A coleta continua com a tela bloqueada, sempre com notificação." |
| 9 | 0:38 | Notificação | Tocar em "Parar" | "Um toque encerra o serviço." |
| 10 | 0:42 | App | Mostrar indicador e notificação removidos | "Coleta encerrada." |
| 11 | 0:46 | Ajustes > Privacidade | Mostrar exportar, excluir conta, histórico de consentimentos | "O usuário controla seus dados." |
| 12 | 0:52 | Conta de menor (opcional) | Mostrar "indisponível para menores de 18" | "Menores de 18 anos não compartilham localização." |

Gravação: conta de teste; `adb shell screenrecord` ou gravador do Android Studio; rota simulada no emulador (Extended controls > Location > Routes) com pontos sobre a linha para os passos 7 a 9; sem dados pessoais reais. Regravar se a divulgação ou a UI mudar.

## 5. Antes de enviar

- [ ] Build de release com `targetSdk` atual e FGS `location` declarado.
- [ ] Divulgação conferida contra o item 3.
- [ ] Vídeo no YouTube (não listado) e link no Console.
- [ ] Data safety coerente (`PLAY-DATA-SAFETY.md`).
- [ ] Política de privacidade publicada em [URL DA POLÍTICA].
