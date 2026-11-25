# Paridade do motor: Apps Script x backend novo (Fase 7, etapa 7.2)

**Veredito: paridade PARCIAL.** Sentido, contagem de trens e posição batem quando os envios do aparelho são frequentes e densos; divergem em quatro pontos mensuráveis (latência de envio em lote, fragmentação do grupo, rejeição lateral e k-anonimato), listados em "Achados". O critério nº 2 de desligamento (DIAGNOSTICO §7) **não está cumprido**: a comparação roda sobre insumo sintético e falta rodá-la sobre posições reais (ver "O que falta para paridade sim").

## O que existe

| Arquivo | Papel |
|---|---|
| `tools/paridade/gerar-fixture.js` | gera a fixture sintética determinística (LCG fixo), sobre o traçado e as estações de `Base.gs`, com os horários reais de parada da viagem `v-08d8e02a` |
| `tools/paridade/fixtures/fixture.json` | fixture gerada (6 cenários, 332 KiB); sem id de aparelho real e sem posição real de pessoa |
| `backend/src/test/kotlin/br/malhaviva/tracking/parity/ParityHarnessTest.kt` | lado novo: assina e envia lotes ao `BatchIngestor` (validação, `PolylineCorridor`, `LiveHub`, `TrainClusterer`) e grava `backend/build/paridade/novo.json` |
| `tools/paridade/comparar.js` | lado antigo: roda `Base.gs` + `Motor.gs` em `vm` (como `teste do motor.js`), lê `novo.json`, compara e imprime o relatório; grava `tools/paridade/out/resultado.json` |

Execução (a partir da raiz do repositório; os `.gs` ficam fora do repositório, na pasta do projeto no Drive, por conterem a tabela de aparelhos em `Code.gs`):

```
node tools/paridade/gerar-fixture.js            # opcional, a fixture já está versionada
cd backend && .\gradlew.bat test --tests "br.malhaviva.tracking.parity.*" --rerun
cd .. && node tools/paridade/comparar.js        # ou: node tools/paridade/comparar.js <pasta-dos-.gs>
```

`--rerun` é necessário porque o Gradle não enxerga a fixture como entrada do teste. A pasta dos `.gs` também pode vir de `MV_APPS_SCRIPT_DIR`. Nada é enviado ao Apps Script publicado, à planilha ou ao Supabase.

## Insumo e limitação principal

Não existem posições brutas no repositório nem na pasta do projeto: `viagens-medidas.json` guarda só horários de parada, e o `posicoes.csv` citado por `teste do motor.js` não está nos arquivos locais. Por isso a fixture é **sintética**:

- **S0**: viagem `v-08d8e02a` (Santa Cruz a Padre Miguel). Os horários de chegada e saída em cada estação são os medidos; a posição entre eles é interpolada, com 4 celulares simulados a bordo (deslocamento ao longo da linha de ±25 m, lateral de ±12 m, ruído de ±3 m, precisão de 5 a 12 m, amostra a cada 5 s andando e a cada 10 s parado).
- **S1** dois trens no mesmo sentido (3 km de distância, 4 celulares cada, 12 m/s). **S2** sentidos opostos (cruzamento perto do km 14, 11 m/s). **S3** um celular isolado. **S4** dois celulares. **S5** quatro celulares a cerca de 100 m da linha.
- **Geometria comum aos dois lados**: o traçado de `Base.gs` (42 vértices, Santa Cruz a Realengo, 27,363 km). O lado novo recebe a mesma polilinha invertida (along 0 = Central) e as posições são mapeadas de volta ao km de `Base.gs` vértice a vertice. A polilinha nova mede 27 394 m contra 27 363 m do `km` de `Base.gs` (diferença de 31 m por fórmulas de distância distintas).
- **Base.gs x corredor OSM (V11)**: 587 pontos do traçado antigo, a cada 50 m, ficam a no máximo 17,8 m do corredor OSM (mediana 4,2 m, p95 10,8 m; 0% acima dos 60 m do limite lateral). A geometria, portanto, não explica nenhuma divergência abaixo.
- **Cadência de envio**: o contrato não fixa quando o app envia lotes. A referência usada é **um lote a cada 15 s por celular, com fase escalonada** (3 amostras por lote), mais duas sensibilidades (5 s e 30 s). O motor antigo recebe todas as amostras sem atraso, como na página real (`Posicoes` gravada a cada posição).
- A fixture cobre só o trecho comum: o antigo vai de Santa Cruz a Realengo, o novo cobre o corredor inteiro (23 estações da V11).

Resultados sobre dado sintético provam equivalência de algoritmo, não de qualidade em campo.

## Tolerâncias (definidas antes de olhar os resultados)

| Métrica | Tolerância | Justificativa |
|---|---|---|
| Diferença de posição ao longo da linha entre trens pareados (pareamento guloso por proximidade, até 1,5 km) | p95 ≤ 100 m e máximo ≤ 250 m | um trem a 12 m/s anda 60 m em 5 s; mediana da precisão medida na viagem real: 26 m |
| Número de trens por tick de 30 s | igual em ≥ 90% dos ticks | permite os ticks de transição (partida, chegada) |
| Sentido (Central x Santa Cruz) | igual em 100% dos pares | binário |
| Próxima estação | informativo | regra de `vizinhas_` do `Motor.gs` aplicada à posição nova |
| Paradas em estação | informativo | o backend novo não emite parada; foi derivada de `speed_mps` < 1 m/s a até 250 m da estação |

Referência extra: o simulador conhece a posição verdadeira de cada trem, e o relatório mostra o erro de cada motor contra ela.

## Resultados (envio de 15 s; K=1 isola o algoritmo, K=3 é a produção)

| Cenário | Contagem de trens | Posição entre motores | Erro vs verdade (mediana / p95) antigo x novo | Sentido |
|---|---|---|---|---|
| S0 viagem real | K=1: 54% dos ticks iguais (78 trem-ticks no antigo, 124 no novo, 96 ids); K=3: 32 trem-ticks no novo contra 78 | p95 68 m (K=1) e 71 m (K=3), dentro da tolerância | 46 / 55 m x 51 / 267 m (K=1); 46 / 55 m x 42 / 116 m (K=3) | 100% |
| S1 dois trens | 100% (K=1 e K=3); ids: 8 (K=1) e 4 (K=3) para 2 trens | mediana 122 m, p95 129 m: **fora** | 35 / 38 m x 146 / 151 m | 100% |
| S2 opostos | K=1 96%, K=3 100% | mediana 126 m, p95 133 m: **fora** | 33 / 36 m x 155 / 160 m | 100% |
| S3 1 celular | K=1 100%; **K=3: 0 contra 16 trem-ticks** | 0,2 m | 14 / 16 m x 14 / 16 m (K=1) | 100% |
| S4 2 celulares | K=1 e K=2 100%; **K=3: 0 contra 16** | 30 m (p95 32 m) | n/d | 100% |
| S5 lateral 100 m | **novo: 0 contra 16**; 380 amostras rejeitadas como `OFF_CORRIDOR` | n/a | n/a | n/a |

Demais números:

- **Próxima estação** igual em 95% (S0), 98% (S1, S2) e 100% (S3, S4) dos ticks em que o trem anda. Em S0 com K=3 cai a 84%, só pelos poucos ticks em que o trem aparece.
- **Paradas (S0)**: o antigo detecta "Parado em X" em 6 das 7 estações com parada de 45 s ou mais; o novo (derivado da velocidade) detecta as mesmas 6 de 7, nos modos K=1 e K=3. A parada de 605 s no terminal de Santa Cruz não vira "parado" em nenhum dos dois, pois o trem só existe depois que os celulares andam. O antigo não detecta paradas de 24 a 36 s em tick de 30 s; o novo detecta algumas delas por ver a velocidade de cada amostra.
- Veredito automático do script (K=1, 15 s, tolerâncias acima): equivalentes S3 e S4; divergentes S0 (contagem), S1 e S2 (posição) e S5 (rejeição).

### Sensibilidade ao intervalo de envio

| Envio | Efeito no backend novo |
|---|---|
| 5 s (1 amostra por lote) | **nenhum trem em nenhum cenário**: o sentido sai do avanço entre a primeira e a última amostra aceita do mesmo lote (≥ 50 m), e um lote de 1 amostra não tem avanço |
| 15 s (referência) | funciona; posição atrasada em média meia janela de envio (cerca de 90 m a 12 m/s) |
| 30 s | os ids deixam de se manter (o trem anda 360 m entre recálculos, acima de `MATCH_RADIUS_M` = 300 m): S1 K=1 gera 41 ids, S2 K=1 gera 49; p95 sobe a 186 m contra a verdade (S0, K=3) e a 288 m de diferença entre motores (S2, K=3) |

## Diferenças semânticas conhecidas

| Tema | Apps Script (`Motor.gs` v0) | Backend novo |
|---|---|---|
| Anonimato | mostra qualquer trem, até com 1 celular | só publica trem com K ≥ 3 ids efêmeros distintos (RIPD R1/R2); K=2 é o piso configurável |
| Identidade | `id` do aparelho e nome, guardados na planilha | época anônima por token; nenhum identificador sai do `LiveHub` |
| Janela de dados | 10 min de histórico; celular "presente" até 180 s sem enviar | só a última posição por época, com no máximo 30 s |
| Persistência de trem | mantém trem 4 min após o celular parar, estima posição por até 20 min, mantém trem vazio no terminal por 90 min, "ponte" de código no terminal | sem estado de terminal nem posição estimada; trem some 30 s após o último sinal |
| Sentido | velocidade sustentada ≥ 2,5 m/s ao longo da linha, em histórico de ≥ 15 s | avanço ≥ 50 m entre primeira e última amostra aceita de um lote; mantém o último sentido da época |
| Agrupamento | mesmo sentido e até 400 m da média do grupo | cadeia com salto ≤ 150 m, rejeição de discrepantes (3×MAD, banda mínima de 100 m), mediana |
| Filtro lateral | 150 m da linha | 60 m da linha, mais: precisão > 50 m, velocidade > 40 m/s, velocidade implícita > 40 m/s, `mock_location`, amostra repetida ou com mais de 10 min |
| Saída | `lat`, `lon`, `km`, `sentido`, `situacao` ("Parado em X", "Andando"), `proxima`, `etaMin`, alertas de parado entre estações, contagem de pessoas esperando por estação | `along_m`, `speed_mps`, faixa de confiança, `updated_at_ms`; lotação de estação é outra função (`StationCrowding`, com histerese) |
| Eixo | km 0 em Santa Cruz | along 0 em Central, cresce para Santa Cruz |
| Cobertura | Santa Cruz a Realengo | corredor completo (V11) |

## Achados

1. **Latência de lote (posição)**: com envio de 15 s, a posição publicada é a última amostra de cada celular, em média 7,5 s velha; contra a verdade, o novo erra 146 a 155 m (S1, S2) e o antigo 33 a 35 m. O `Motor v1 (proposta)` do Apps Script já previa projetar cada celular "para agora"; o `TrainClusterer` não projeta.
2. **Fragmentação do grupo**: os celulares do mesmo trem enviam em fases diferentes; a 12 m/s ou mais, as últimas posições se espalham além de `GROUP_GAP_M` (150 m). O trem se parte em dois ou mais grupos (S0: 124 trem-ticks contra 78) e, com K=3, desaparece (S0: visível em 32 de 78 ticks).
3. **Sentido depende do lote**: com 1 amostra por lote o novo não infere sentido e não publica trem algum. O app precisa enviar lotes com 2 amostras ou mais e ≥ 50 m de avanço, ou o sentido deve vir de outra fonte (rumo `bearing_deg` do contrato, hoje não usado pelo `BatchIngestor`).
4. **Limite lateral de 60 m**: rejeita tudo a 100 m da linha (S5), e o antigo aceita até 150 m. A geometria V11 está a menos de 18 m do traçado do piloto, mas a precisão mediana medida em campo foi 26 m; em trechos de GPS ruim o novo descarta mais amostras. Registrar em campo a taxa de `OFF_CORRIDOR`.
5. **K=3 oculta de propósito** trens de 1 e 2 celulares (S3, S4). No piloto de 2 a 3 pessoas isso significa que o mapa novo só mostra trem com 3 celulares a bordo: é o comportamento correto para o RIPD, mas muda a experiência de quem hoje vê trem com 1 celular.
6. **Funções sem equivalente no novo motor**: situação textual, ETA, alerta de parado entre estações, trem vazio no terminal e estimativa de posição. Se o produto depender delas, precisam virar requisito (contrato) antes do desligamento.
7. **Ids estáveis** (S1, K=3: 4 ids para 2 trens) não são estáveis em envio lento; afeta qualquer cliente que acompanhe um trem pelo id.

## O que falta para "paridade sim"

1. Rodar o mesmo harness sobre posições reais anonimizadas (a aba `Posicoes` migrada sem "Quem" e sem id, depende de P4 do plano) ou sobre uma viagem nova gravada com o app próprio; `gerar-fixture.js` troca-se por um conversor de CSV para o mesmo `fixture.json`.
2. Definir o intervalo de envio do app e o que o contrato exige por lote (achados 1 a 3); decidir se o servidor projeta as posições para "agora" e se usa `bearing_deg`.
3. Decidir o destino de ETA, situação textual e alerta de parado (achado 6).
4. Reavaliar as tolerâncias de posição com o intervalo real (hoje p95 ≤ 100 m falha em S1 e S2 só por latência de envio).

## Perguntas abertas

- Qual é a cadência real de envio do app novo? Pela leitura rápida de `TripPipeline.finish` e `BatchBuilder`, os lotes saem ao fim da viagem; confirmar com quem mantém o app antes de adotar 15 s como referência.
- Há posições reais (aba `Posicoes` ou `posicoes.csv`) acessíveis para repetir o teste sem sintético?
- A diferença de experiência com K=3 (achado 5) é aceita para o piloto?
