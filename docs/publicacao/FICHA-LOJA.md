# Ficha da loja (pt-BR)

> **RASCUNHO de texto de marketing.** Só prometer o que existe na build enviada. Não citar avanços ainda não implementados (ver PENDÊNCIAS nos relatórios). Nome da empresa e links: [RAZÃO SOCIAL], [URL DA POLÍTICA], [URL DE SUPORTE]. V1: Trens RJ.

## 1. Texto

**Título (≤ 30):** `Malha Viva: trens ao vivo` (25)

Alternativa: `Malha Viva` (10) com subtítulo da App Store "Trens ao vivo, sem rastrear você".

**Descrição curta, Play (≤ 80):**
`Veja onde estão os trens e quando chegam. Colaborar é opcional e anônimo.` (74)

**Subtítulo App Store (≤ 30):** `Trens ao vivo e anônimo` (23)

**Texto promocional App Store (≤ 170):**
`Acompanhe o trem deslizando pela linha e saiba o próximo horário. Compartilhar sua posição é opcional, anônimo e você para quando quiser.` (136)

**Descrição completa (≤ 4000; versão abaixo tem cerca de 2100):**

```
Chega de esperar sem saber. O Malha Viva mostra onde os trens estão e quando devem chegar à sua estação.

VEJA O TREM DE VERDADE
O trem desliza pelo traçado da linha, sem pulos, com horário estimado de chegada em cada estação. Quando não há sinal de passageiros, mostramos o horário programado, sempre identificado como "previsto".

FEITO PARA O DIA A DIA
• Favoritas na tela inicial: próximo trem a um toque.
• Linha do tempo da viagem com estações, ETA e baldeações.
• Lotação das estações em 3 níveis (baixa, média, alta), quando há dados suficientes.
• Alertas de linha e de estação. A permissão de notificações só é pedida quando você cria o primeiro alerta.
• Widgets com o próximo trem da sua estação favorita e o status da linha.
• Live Activity e Dynamic Island no iPhone, e notificação de viagem no Android, com contagem regressiva.
• Acessível: leitor de tela, fonte ampliada, alto contraste e opção de reduzir movimento.

COLABORE SE QUISER
O app funciona sem compartilhar nada. Se você quiser ajudar outros passageiros, toque em "Estou no trem" ou "Estou na estação". Você lê um aviso antes e pode parar a qualquer momento com um toque.

PRIVACIDADE EM PRIMEIRO LUGAR
• Sua posição é enviada sem seu nome, conta, telefone ou aparelho.
• O início e o fim da viagem são descartados para proteger sua casa e seu trabalho.
• As posições são apagadas em até 24 horas.
• Sem propaganda, sem venda de dados, sem perfil para anúncios.
• Menores de 18 anos não compartilham localização.
• Você pode exportar seus dados e excluir sua conta no próprio app.

CONTA SIMPLES
Crie sua conta com um nome de usuário e uma senha. Telefone e e-mail são opcionais e servem apenas para segurança e recuperação. Autenticação em dois fatores disponível.

Linhas atendidas na versão inicial: trens urbanos do Rio de Janeiro [CONFIRMAR LINHAS COBERTAS].

O Malha Viva é um serviço independente e não é operado por [CONCESSIONÁRIA] [CONFIRMAR: não usar nome ou logotipo de operadoras sem autorização].

Política de privacidade: [URL DA POLÍTICA]
Dúvidas: [E-MAIL DE SUPORTE]
```

Itens a **validar contra a build** antes de publicar: widgets, Live Activity, alertas, lotação, 2FA, exportação e exclusão. Remover do texto o que não estiver na versão enviada. Não usar "oficial" nem logos de operadoras.

## 2. Storyboard dos screenshots

Formatos: Play, mínimo 2 e até 8 de celular (16:9 ou 9:16, 320 a 3840 px); tablet 7" e 10" se houver suporte. App Store: 6,9" (1320x2868) e 6,5"/6,1" conforme exigido no App Store Connect; iPad se suportado. Legenda curta no topo (≤ 6 palavras), claro e escuro alternados, sem dados pessoais, mapa diurno e noturno.

| # | Tela | Legenda | Detalhe |
|---|---|---|---|
| 1 | **Mapa com o trem deslizando** (hero) | "Veja o trem deslizar pela linha" | Marcador em movimento com rastro curto, anel de confiança, hora "Atualizado há 3 s". Em mídia animada (vídeo de prévia): ver item 4 |
| 2 | Home com favoritas | "Seu próximo trem a um toque" | 2 favoritas com ETA, status por cor + ícone + texto |
| 3 | Linha do tempo da viagem | "Cada estação, cada horário" | Trem animado entre estações, passadas esmaecidas, baldeação sinalizada |
| 4 | Estação com lotação | "Saiba se a estação está cheia" | Três níveis (ícone + texto). Mostrar caso real com dados, não vazio |
| 5 | Widgets (tela de bloqueio e inicial) | "Sem abrir o app" | Próximo trem e status da linha |
| 6 | Live Activity / Dynamic Island (iOS) ou notificação (Android) | "Acompanhe a viagem" | Contagem regressiva e progresso |
| 7 | Tela "Estou no trem" com aviso | "Colaborar é opcional" | Aviso de privacidade e indicador com "Parar" |
| 8 | Privacidade e dados | "Seus dados, sob seu controle" | Exportar, excluir, dispositivos conectados |

Dica de captura: gerar com o emulador/simulador e rota simulada; manter os mesmos horários nos prints; tela do "trem deslizando" também como GIF/MP4 para a prévia da App Store (15 a 30 s) e para o vídeo promocional do Play (YouTube).

## 3. Vídeo de prévia (trem deslizando)

15 a 30 s, sem áudio obrigatório: mapa dia, trem percorre a polilinha entre duas estações com ETA caindo, cai na tela da estação com lotação, widget e Live Activity. Não mostrar localização de pessoa real. Para o Play, link do YouTube sem anúncios.

## 4. Feature graphic (Play, 1024x500 px, PNG ou JPEG, sem transparência)

- **Conteúdo:** à esquerda, logotipo Malha Viva (de `docs/brand/logo-original.*` quando existir; tokens em `contracts/brand-tokens.json`) e o slogan "Trens ao vivo. Privacidade de verdade."; à direita, trecho do mapa com o trem e um rastro suave, com um celular parcialmente à mostra.
- **Estilo:** premium e minimalista; paleta dos tokens de marca; fundo escuro ou claro conforme os tokens; **sem mascote, sem blur pesado, sem logos de operadoras, sem selos de loja**.
- **Zona segura:** manter texto e logotipo dentro de uma margem de 15% (o Play recorta nas bordas em alguns formatos).
- **Texto:** ≤ 5 palavras na imagem, fonte legível em miniatura, contraste validado.

## 5. Ícone

Ícone 512x512 (Play, PNG 32 bits) e 1024x1024 (App Store, sem transparência nem cantos arredondados). Vem do logotipo original. [CONFIRMAR assets finais da Fase 2].

## 6. Metadados

| Campo | Valor |
|---|---|
| Categoria Play | Mapas e navegação (alternativa: Ferramentas) |
| Categoria App Store | Navegação (secundária: Utilitários) |
| Palavras-chave App Store (≤ 100 caracteres) | `trem,metrô,horário,Rio,linha,estação,ETA,transporte,ao vivo` (sem marcas de terceiros; [CONFIRMAR]) |
| E-mail de suporte | [E-MAIL DE SUPORTE] |
| Site / suporte | [URL DE SUPORTE] |
| Política de privacidade | [URL DA POLÍTICA] |
| Termos de uso | [URL DOS TERMOS] |
| Idioma padrão | Português (Brasil) |
| Notas de versão 1.0 | "Primeira versão: acompanhe os trens do Rio ao vivo, com widgets e alertas. Colaborar é opcional e anônimo." [AJUSTAR ao que for enviado] |
