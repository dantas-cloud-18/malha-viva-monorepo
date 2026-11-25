# Paridade do motor: Apps Script x backend novo (Fase 7, etapa 7.2)

**Veredito (segunda rodada, depois das correções): paridade de algoritmo SIM sobre insumo sintético; paridade em campo NÃO comprovada.** Com o limiar padrão K=1 e envio a cada 5, 15 ou 30 s, os seis cenários ficam equivalentes ao `Motor.gs` (sentido 100%, contagem de trens igual em 92% a 100% dos ticks, posição entre motores com p95 de 29 a 53 m, dentro da tolerância de 100 m). Na primeira rodada o veredito era PARCIAL (S0, S1, S2 e S5 divergiam). O critério nº 2 de desligamento (DIAGNOSTICO §7) continua **não cumprido** porque falta rodar a comparação sobre posições reais (ver "O que falta para paridade sim").

## O que mudou desde a primeira rodada

| Achado | Correção | Onde |
|---|---|---|
| 1. Latência de lote (146 a 155 m de erro) | cada posição é projetada para "agora" (`along_m` + velocidade x idade, no sentido do trem) antes de agrupar; janela de dados de 30 para 60 s | `TrainClusterer.projected`, `ClusterPolicy.MAX_AGE_MS` |
| 2. Fragmentação do grupo e ids instáveis | a projeção alinha as fases de envio; `GROUP_GAP_M` 150 para 250 m; ids herdados do trem anterior (já existia) agora sobrevivem | `TrainClusterer` |
| 3. Sentido depende do lote | sem avanço de 50 m no lote, usa `bearing_deg` da última amostra (só com velocidade ≥ 1 m/s, projetando 50 m no rumo e olhando para que lado da via andou); sem rumo, vale o sentido da época. O app passou a enviar `bearing_deg` | `BatchIngestor.headingFromBearing`, Android `Reading.bearingDeg` |
| 4. Limite lateral de 60 m | 150 m, igual ao `Motor.gs` (constante `MAX_LATERAL_M`); cobre a precisão aceita (50 m) mais o desvio OSM (máx. 18 m) | `CorridorGeometry.kt` |
| 5. K=3 oculta trem de 1 e 2 celulares | decisão de produto: padrão K=1, piso 1, faixas de confiança 1 a 2 / 3 a 6 / 7 ou mais (RIPD R1/R2 atualizado, com risco aumentado e `[PENDENTE: revisão do Encarregado]`) | `ClusterPolicy`, contrato |
| 6. Situação, ETA e alerta de parado | viraram requisito: `situation` (`moving`, `at_station`, `stopped_between_stations` após 60 s), `station_id` e `etas` no `TrainState`; o app mostra o status, o alerta e usa o ETA do servidor | contrato, `TrainClusterer.describe`, `feature/trains` |
| Cadência de envio (pergunta aberta) | **confirmado**: o app só enviava ao fim da viagem (`TripPipeline.finish`); agora envia a cada 15 s (`TripPipeline.drain`), retendo os últimos 300 m | Android `TripPipeline`, `TrackingService` |

## O que existe

| Arquivo | Papel |
|---|---|
| `tools/paridade/gerar-fixture.js` | gera a fixture sintética determinística (LCG fixo), sobre o traçado e as estações de `Base.gs`, com os horários reais de parada da viagem `v-08d8e02a` |
| `tools/paridade/fixtures/fixture.json` | fixture gerada (6 cenários, 332 KiB); sem id de aparelho real e sem posição real de pessoa |
| `backend/src/test/kotlin/br/malhaviva/tracking/parity/ParityHarnessTest.kt` | lado novo: assina e envia lotes ao `BatchIngestor` (validação, `PolylineCorridor`, `LiveHub`, `TrainClusterer`) e grava `backend/build/paridade/novo.json`; envia `bearing_deg` calculado entre amostras consecutivas, como o GPS informa em movimento |
| `tools/paridade/comparar.js` | lado antigo: roda `Base.gs` + `Motor.gs` em `vm` (como `teste do motor.js`), lê `novo.json`, compara e imprime o relatório; grava `tools/paridade/out/resultado.json` |

Execução (a partir da raiz do repositório; os `.gs` ficam fora do repositório, na pasta do projeto no Drive, por conterem a tabela de aparelhos em `Code.gs`):

```
node tools/paridade/gerar-fixture.js            # opcional, a fixture já está versionada
cd backend && .\gradlew.bat test --tests "br.malhaviva.tracking.parity.*" --rerun
cd .. && node tools/paridade/comparar.js        # ou: node tools/paridade/comparar.js <pasta-dos-.gs>
```

`--rerun` é necessário porque o Gradle não enxerga a fixture como entrada do teste. A pasta dos `.gs` também pode vir de `MV_APPS_SCRIPT_DIR`. Nada é enviado ao Apps Script publicado, à planilha ou ao Supabase.

## Insumo e limitação principal

Não existem posições brutas no repositório nem na pasta do projeto: `viagens-medidas.json` guarda só horários de parada, e o `posicoes.csv` citado por `teste do motor.js` não está nos arquivos locais. Por isso a fixture é **sintética** (decisão mantida):

- **S0**: viagem `v-08d8e02a` (Santa Cruz a Padre Miguel). Os horários de chegada e saída em cada estação são os medidos; a posição entre eles é interpolada, com 4 celulares simulados a bordo (deslocamento ao longo da linha de ±25 m, lateral de ±12 m, ruído de ±3 m, precisão de 5 a 12 m, amostra a cada 5 s andando e a cada 10 s parado).
- **S1** dois trens no mesmo sentido (3 km de distância, 4 celulares cada, 12 m/s). **S2** sentidos opostos (cruzamento perto do km 14, 11 m/s). **S3** um celular isolado. **S4** dois celulares. **S5** quatro celulares a cerca de 100 m da linha.
- **Geometria comum aos dois lados**: o traçado de `Base.gs` (42 vértices, Santa Cruz a Realengo, 27,363 km). O lado novo recebe a mesma polilinha invertida (along 0 = Central) e as posições são mapeadas de volta ao km de `Base.gs` vértice a vértice. A polilinha nova mede 27 394 m contra 27 363 m do `km` de `Base.gs` (diferença de 31 m por fórmulas de distância distintas).
- **Base.gs x corredor OSM (V11)**: 587 pontos do traçado antigo, a cada 50 m, ficam a no máximo 17,8 m do corredor OSM (mediana 4,2 m, p95 10,8 m).
- **Cadência de envio**: um lote a cada 15 s por celular, com fase escalonada (3 amostras por lote), mais duas sensibilidades (5 s e 30 s). O motor antigo recebe todas as amostras sem atraso, como na página real. O harness **não reproduz a retenção dos últimos 300 m** do app (o app real entrega a posição mais velha em 25 a 40 s a 12 m/s; a janela de 60 s e a projeção cobrem isso, mas o efeito não foi medido aqui).
- A fixture cobre só o trecho comum: o antigo vai de Santa Cruz a Realengo, o novo cobre o corredor inteiro (23 estações da V11).

Resultados sobre dado sintético provam equivalência de algoritmo, não de qualidade em campo.

## Tolerâncias (definidas antes de olhar os resultados, inalteradas)

| Métrica | Tolerância | Justificativa |
|---|---|---|
| Diferença de posição ao longo da linha entre trens pareados (pareamento guloso por proximidade, até 1,5 km) | p95 ≤ 100 m e máximo ≤ 250 m | um trem a 12 m/s anda 60 m em 5 s; mediana da precisão medida na viagem real: 26 m |
| Número de trens por tick de 30 s | igual em ≥ 90% dos ticks | permite os ticks de transição (partida, chegada) |
| Sentido (Central x Santa Cruz) | igual em 100% dos pares | binário |
| Próxima estação | informativo | regra de `vizinhas_` do `Motor.gs` aplicada à posição nova |
| Paradas em estação | informativo | derivada de `speed_mps` < 1 m/s a até 250 m da estação |

Referência extra: o simulador conhece a posição verdadeira de cada trem, e o relatório mostra o erro de cada motor contra ela.

## Resultados antes e depois (K=1, envio de 15 s)

Antes = primeira rodada (backend com projeção ausente, `GROUP_GAP_M` 150 m, lateral 60 m, sem `bearing_deg`). Depois = esta rodada. Erro vs verdade = mediana / p95 do backend novo em metros; o do `Motor.gs` não mudou (S0 46/55, S1 35/38, S2 33/36, S3 14/16, S4 48/49, S5 7/13).

| Cenário | Contagem de trens igual (antes → depois) | Posição entre motores, p95 / máx (antes → depois) | Erro novo vs verdade (antes → depois) | Ids de trem (antes → depois) | Sentido |
|---|---|---|---|---|---|
| S0 viagem real | 54% → 92% (trem-ticks 124 → 86 contra 78) | 68 m → 53 / 91 m (dentro) | 51 / 267 → 8 / 310 (com K=3: 42 / 116 → 8 / 82) | 96 → 9 | 100% |
| S1 dois trens | 100% → 100% | 129 m (fora) → 29 / 29 m (dentro) | 146 / 151 → 8 / 13 | 8 → 2 | 100% |
| S2 opostos | 96% → 100% | 133 m (fora) → 52 / 54 m (dentro) | 155 / 160 → 12 / 17 | 2 → 2 | 100% |
| S3 1 celular | 100% → 100% | 0,2 m → 14 m | 14 / 16 → 1 / 4 | 1 → 1 | 100% |
| S4 2 celulares | 100% → 100% | 32 m → 42 m | n/d → 7 / 10 | 1 → 1 | 100% |
| S5 lateral 100 m | 0 contra 16 (380 amostras `OFF_CORRIDOR`) → 100%, nenhuma rejeitada | n/a → 32 m | n/a → 38 / 43 | n/a → 1 | 100% |

O p95 de 310 m em S0 com K=1 vem de fragmentos de um celular só que se separam do grupo nas partidas e paradas; com K=3 cai a 82 m. Veredito automático do script (K=1, 15 s): **equivalentes** S0, S1, S2, S3, S4 e S5; divergentes: nenhum.

Demais números:

- **Próxima estação** igual em 98% (S0) e 100% (S1, S2, S3, S4) dos ticks em que o trem anda.
- **Paradas (S0)**: o antigo detecta "Parado em X" em 6 das 7 estações com parada de 45 s ou mais; o novo (derivado da velocidade) detecta as mesmas 6 de 7. A parada de 605 s no terminal de Santa Cruz não vira "parado" em nenhum dos dois, pois o trem só existe depois que os celulares andam.
- **K=2 e K=3** continuam ocultando S3 (1 celular) e S4 (2 celulares), de propósito, quando o operador sobe `MALHAVIVA_TRAIN_MIN_CONTRIBUTORS`.

### Sensibilidade ao intervalo de envio

| Envio | Antes | Depois |
|---|---|---|
| 5 s (1 amostra por lote) | nenhum trem em nenhum cenário (sem avanço no lote, sem sentido) | funciona: S0 93% dos ticks, S1 e S2 100%; o sentido vem de `bearing_deg`. Sem rumo no lote, continua sem sentido |
| 15 s (referência) | posição atrasada em média meia janela de envio (cerca de 90 m a 12 m/s) | erro de posição de 8 a 12 m (mediana) |
| 30 s | ids deixam de se manter: S1 K=1 gerava 41 ids, S2 K=1 gerava 49; p95 de 186 m contra a verdade (S0, K=3) e 288 m entre motores (S2, K=3) | S1 e S2 K=1 geram 2 ids; p95 contra a verdade (S0, K=3) 105 m; entre motores (S2, K=3) 53 m |

## Diferenças semânticas conhecidas

| Tema | Apps Script (`Motor.gs` v0) | Backend novo |
|---|---|---|
| Anonimato | mostra qualquer trem, até com 1 celular; `id` do aparelho e nome na planilha | também publica trem de 1 celular (K=1, padrão; RIPD R2), mas com época anônima por token; nenhum identificador sai do `LiveHub` |
| Janela de dados | 10 min de histórico; celular "presente" até 180 s sem enviar | só a última posição por época, com no máximo 60 s, projetada para agora |
| Persistência de trem | mantém trem 4 min após o celular parar, estima posição por até 20 min, mantém trem vazio no terminal por 90 min, "ponte" de código no terminal | sem estado de terminal nem posição estimada além da projeção; trem some 60 s após o último sinal |
| Sentido | velocidade sustentada ≥ 2,5 m/s ao longo da linha, em histórico de ≥ 15 s | avanço ≥ 50 m entre primeira e última amostra de um lote; senão `bearing_deg`; senão o último sentido da época |
| Agrupamento | mesmo sentido e até 400 m da média do grupo | cadeia com salto ≤ 250 m (após projeção), rejeição de discrepantes (3×MAD, banda mínima de 100 m), mediana |
| Filtro lateral | 150 m da linha | 150 m da linha, mais: precisão > 50 m, velocidade > 40 m/s, velocidade implícita > 40 m/s, `mock_location`, amostra repetida ou com mais de 10 min |
| Saída | `lat`, `lon`, `km`, `sentido`, `situacao` ("Parado em X", "Andando"), `proxima`, `etaMin`, alertas de parado entre estações | `along_m`, `speed_mps`, faixa de confiança, `situation`, `station_id`, `etas`, `updated_at_ms`; o texto é montado pelo app. Lotação de estação é outra função (`StationCrowding`, k=5, com histerese) |
| Eixo | km 0 em Santa Cruz | along 0 em Central, cresce para Santa Cruz |
| Cobertura | Santa Cruz a Realengo | corredor completo (V11) |

## Pontos que continuam abertos

1. **Insumo real**: nada disso foi medido com posições reais. Falta rodar o mesmo harness sobre a aba `Posicoes` migrada sem "Quem" e sem id (depende de P4 do plano) ou sobre uma viagem gravada com o app novo; `gerar-fixture.js` troca-se por um conversor de CSV para o mesmo `fixture.json`.
2. **Retenção de 300 m no app x posição ao vivo**: a amostra mais nova que o app libera está 300 m atrás do trem (25 a 40 s a 12 m/s). A projeção compensa a velocidade constante, não frenagem em estação. Medir em campo. A 5 m/s ou menos a retenção passa de 60 s e o trem some do mapa até acelerar.
3. **Lotação de estação** usa só a última posição de cada viagem dentro de 30 s e velocidade ≤ 1 m/s; com a retenção de 300 m, quem está parado na estação deixou de enviar há mais de 30 s e não conta. A lotação por esse caminho provavelmente não funciona com envio ao vivo; precisa de decisão de produto (check-in explícito, RIPD 7.1).
4. **Limite lateral do app** continua em 60 m (`TrackPolicy.MAX_LATERAL_M`, também usado no auto-encerramento fora da rota); o do servidor passou a 150 m. Alinhar o app só ganha amostras se o auto-encerramento for revisto junto.
5. **Fila offline**: não existe (lote que falha é descartado, o que é aceitável para posição ao vivo, mas perde a viagem em túnel ou sem sinal).
6. **Envio ao vivo muda o RIPD** (R1/R2): a posição agregada de um trem passa a existir em tempo quase real, com 1 celular basta. Pendente de revisão do Encarregado.
7. **Pedestres**: com K=1 e lateral de 150 m, uma pessoa andando a pé perto da linha (ou parada numa estação) pode aparecer como trem; não há filtro de velocidade mínima por amostra.
