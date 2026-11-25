# Inventário do Apps Script (Fase 7, etapa 7.1)

Fonte: leitura local dos arquivos `Code.gs` (288 linhas), `Motor.gs` (259), `Base.gs` (7), `Motor v1 (proposta).gs` (308, não implantado), `index.html`, `teste do motor.js`, `teste do servidor.js`, `gerar base.py` e `gerar tabela.js`, na pasta do projeto no Drive. Nenhuma chamada ao script publicado, à planilha ou a serviço remoto foi feita. Valores de chaves e identificadores foram omitidos (`<REDACTED>`).

Legenda: **[código]** provado pelo código-fonte local; **[a confirmar no painel do Apps Script]** o código sozinho não prova (implantação, permissões, gatilhos criados pela interface, quotas, contas).

## 1. Endpoints

O script é um web app. Há duas entradas HTTP (`Code.gs:34` e `Code.gs:46`) e quatro funções expostas à página via `google.script.run`.

| Entrada | Parâmetros | Resposta | Origem |
|---|---|---|---|
| `doGet(e)` com posição | `id` ou `deviceid`, `lat`, `lon`, e opcionais `timestamp`, `accuracy` ou `hdop`, `batt` (formato OsmAnd / Traccar Client antigo) | texto `OK` ou `IGNORADO` | `Code.gs:34-44`, `Code.gs:59-62` [código] |
| `doGet(e)` sem posição | nenhum | página HTML `index` (`HtmlService`), `XFrameOptionsMode.ALLOWALL` | `Code.gs:40-43` [código] |
| `doPost(e)`, corpo JSON Traccar Client novo | `device_id` (ou `id`), `location.timestamp`, `location.coords.{latitude,longitude,accuracy}`, `location.battery.level` | `OK` (texto) | `Code.gs:66-73` [código] |
| `doPost(e)`, corpo JSON OwnTracks | `_type:"location"`, `tid`, `lat`, `lon`, `tst`, `acc`, `batt` | `[]` com MIME JSON | `Code.gs:75-76`, `Code.gs:46-56` [código] |
| `doPost(e)`, JSON simples | `id` ou `device_id`, `lat`, `lon`, e opcionais `timestamp` ou `ts`, `accuracy`, `batt` | `OK` | `Code.gs:77-80` [código] |
| `doPost(e)` sem formato reconhecido | qualquer | `OK`; grava linha no `Log` ("POST sem posição reconhecida") | `Code.gs:49-51` [código] |

Funções chamadas pela página (`google.script.run`, `index.html:245-253`):

| Função | Argumentos | Efeito | Origem |
|---|---|---|---|
| `obterEstado()` | nenhum | lê as últimas 800 linhas de `Posicoes`, roda o motor, devolve posições por celular (sem o id), alertas das últimas 3 h e o estado do motor | `Code.gs:125-132` [código] |
| `obterBase()` | nenhum | traçado e estações de `BASE_RAMAL` | `Code.gs:134-141` [código] |
| `emitirAlerta(quem, estacao, sentido, tipo, nota)` | texto livre truncado (60/30/40/140 caracteres) | grava linha em `Alertas`, devolve `A-<8 hex>` | `Code.gs:143-154` [código] |
| `confirmarAlerta(alertaId, quem)` | id do alerta, nome | grava linha em `Confirmacoes`; autor não confirma o próprio alerta; sem duplicata | `Code.gs:156-172` [código] |

Observações de comportamento (código):

- Qualquer posição é aceita se o `id` do aparelho estiver na tabela `DISPOSITIVOS`; id desconhecido vai ao `Log` e a resposta é `IGNORADO` (`Code.gs:85-88`). O id viaja na URL (GET) ou no corpo (POST).
- Posição com mais de 15 min de atraso é descartada e registrada no `Log` uma vez por registro (`Code.gs:94-100`).
- A página consulta `obterEstado` a cada 5 s (`INTERVALO=5000`, `INTERVALO_ERRO=15000`, `index.html:243`), aumentando a carga de leitura na planilha.
- Não há autenticação para `doGet`/`doPost` além do id do aparelho, nem para as quatro funções da página; o campo `quem` de alertas e confirmações vem da própria página (`nomeValido_`, `Code.gs:275`), logo qualquer visitante da URL pode se passar por um dos nomes. É um achado do piloto, não decisão de desenho do backend novo.
- Configuração do web app (quem executa, quem acessa, URL de implantação, versão): **[a confirmar no painel do Apps Script]**. `appsscript.json` não está entre os arquivos locais.

## 2. Gatilhos

- Busca por `ScriptApp`, `onEdit`, `onOpen`, `onInstall`, `setTrigger`, `everyMinutes`, `newTrigger` nos quatro `.gs`: sem ocorrência [código]. Não há gatilho de tempo nem de planilha criado por código.
- Gatilhos criados pela interface (painel "Acionadores"): **[a confirmar no painel do Apps Script]**. Precisam ser listados antes de 7.6, pois só o painel os mostra.
- A única função de "execução manual" prevista é `preparar()` (`Code.gs:286`), que autoriza e cria a planilha na primeira vez [código].

## 3. Propriedades, cache e trava

| Serviço | Chave | Conteúdo | TTL | Origem |
|---|---|---|---|---|
| `PropertiesService.getScriptProperties` | `SSID` | id da planilha (valor `<REDACTED>`) | persistente | `Code.gs:246-250` [código] |
| `PropertiesService.getScriptProperties` | `MOTOR_ESTADO` | JSON `{trens, cel, seq}`; `cel` é mapa por **id do aparelho** | persistente, regravada a cada cálculo | `Code.gs:229-233` [código] |
| `CacheService.getScriptCache` | `motor_saida` | saída do motor | 15 s | `Code.gs:235-236` [código] |
| `CacheService.getScriptCache` | `motor_ultima` | última saída conhecida | 300 s | `Code.gs:237` [código] |
| `CacheService.getScriptCache` | `atraso_<id>_<ms>` | marca de "já registrado no Log" | 6 h | `Code.gs:94-100` [código] |
| `LockService.getScriptLock` | n/a | `tryLock(3000)` no motor, `waitLock(10000)` nas escritas | n/a | `Code.gs:218`, `Code.gs:270` [código] |

Tabela de dispositivos (`DISPOSITIVOS`, `Code.gs:13-17`): 3 entradas, mapeando um id secreto de aparelho para um primeiro nome (duas pessoas; a terceira é o app próprio do mesmo usuário). Ids e nomes ficam fora deste documento. Cada id funciona como credencial compartilhada de envio; **revogar ao desligar (etapa 7.6)**: no formato GET o id viaja na URL, e os arquivos do Drive o guardam em texto.

Chaves de API, tokens OAuth, segredos de terceiros: nenhum no código [código]. Propriedades adicionais criadas pelo painel: **[a confirmar no painel do Apps Script]** (Configurações do projeto, Propriedades do script).

## 4. Serviços externos chamados

- `UrlFetchApp`, `MailApp`, `GmailApp`, `DriveApp`, `Session`: sem ocorrência [código]. O servidor não chama serviço externo.
- Serviços Google usados: `SpreadsheetApp` (única persistência), `PropertiesService`, `CacheService`, `LockService`, `ContentService`, `HtmlService`, `Utilities.getUuid` [código].
- A página `index.html` carrega do navegador: Leaflet 1.9.4 (`unpkg.com`), fontes Google (`fonts.googleapis.com`, `fonts.gstatic.com`) e tiles `tile.openstreetmap.org` [código]. São chamadas do cliente, não do servidor.
- Escopos OAuth autorizados, conta proprietária e conta de execução: **[a confirmar no painel do Apps Script]**.

## 5. Planilha

Nome: "Malha Viva · Teste ao vivo" (`Code.gs:9`); id guardado na propriedade `SSID`. Quatro abas, criadas por `planilha_()` (`Code.gs:25-30`, `Code.gs:252-261`):

| Aba | Colunas | Observação |
|---|---|---|
| `Posicoes` | Hora da posição, Dispositivo, Quem, Latitude, Longitude, Precisão (m), Bateria, Hora do recebimento | `Dispositivo` e `Quem` ligam posição a pessoa. Não migrar (briefing e DIAGNOSTICO §7). |
| `Alertas` | ID, Hora, Quem, Estação, Sentido, O que aconteceu, Observação | `ID` tem prefixo `A-`; `Quem` identifica autor. |
| `Confirmacoes` | ID do alerta, Hora, Quem confirmou | `Quem confirmou` identifica pessoa. |
| `Log` | Hora, Motivo, Detalhe | limitado a 200 linhas (`MAX_LINHAS_LOG`); detalhe pode conter o id do aparelho. |

Volume real, permissões de compartilhamento, histórico de versões e existência de abas extras: **[a confirmar no painel do Apps Script]** (e na própria planilha).

## 6. Clientes esperados

| Cliente | Como chama | Evidência | Situação atual |
|---|---|---|---|
| Traccar Client (formato antigo, OsmAnd) | `GET ?id&lat&lon&timestamp&accuracy&batt` | `interpretarEnvio_` [código] | **[a confirmar no painel do Apps Script]** se algum aparelho ainda envia |
| Traccar Client (formato novo) | `POST` JSON `device_id`/`location` | `interpretarEnvio_` [código] | idem |
| OwnTracks | `POST` JSON `_type:location`; espera `[]` | `doPost` [código] | idem |
| JSON simples | `POST` com `id`, `lat`, `lon` | `interpretarEnvio_` [código] | idem; usado em testes |
| App próprio Malha Viva (o terceiro dispositivo, comentário "v6, 17/11/2026") | formato não determinável pelo código | comentário em `Code.gs:16` | **[a confirmar no painel do Apps Script]** e no código do PWA |
| Página `index.html` (HTML Service) | `google.script.run` para as 4 funções | `index.html:245-253`, `:415`, `:423`, `:434`, `:472` [código] | enquanto a URL existir, qualquer navegador |
| PWA atual (Supabase) | **não chama o Apps Script** segundo o código local; usa o Supabase | `docs/ESTADO.md`, `docs/BACKEND.md` | fora do escopo deste inventário (P2 do plano) |
| Protótipos HTML v1 a v3.2 | sem `fetch`, `XMLHttpRequest` nem `script.google` | busca nos arquivos [código] | não são clientes |
| Testes manuais (navegador, curl) | `GET`/`POST` | n/a | **[a confirmar no painel do Apps Script]** |

A prova de "nenhum cliente apontando para a URL antiga" (critério de desligamento nº 3) só sai do painel "Execuções" do Apps Script (origem e frequência das chamadas) mais a janela de observação de 7 dias da etapa 7.5. O código local não prova ausência de clientes.

## 7. Itens para confirmar no painel (checklist de leitura)

Somente leitura, antes de 7.5:

1. Implantações do web app: lista, versão e URLs (anotar sem copiar o id para o repositório).
2. Configuração do web app: "Executar como" e "Quem tem acesso".
3. Acionadores: lista completa (esperado: vazio).
4. Propriedades do script: lista de chaves (esperado: `SSID`, `MOTOR_ESTADO`).
5. Projeto vinculado: projeto Google Cloud padrão, escopos autorizados, serviços avançados.
6. Execuções dos últimos 7 a 30 dias: contagem por função e por tipo (`doGet`, `doPost`, `obterEstado`...), para provar quem ainda chama.
7. Planilha: contagem de linhas por aba, compartilhamento, aba não prevista.
8. Contas com acesso de edição ao script e à planilha.

## 8. Arquivos auxiliares (não implantados)

- `Motor v1 (proposta).gs`: proposta de 17/11/2026, "NÃO IMPLANTADA"; mesma interface `motorPasso_`. Não entra na paridade (a produção roda o `Motor.gs` v0).
- `teste do motor.js`: roda `Base.gs` + `Motor.gs` em `vm`; lê `posicoes.csv` (cópia da aba `Posicoes`, que **não está** na pasta local).
- `teste do servidor.js`: roda `Code.gs` com serviços simulados em memória.
- `gerar base.py` e `gerar tabela.js`: geram `Base.gs` e a tabela de tempos a partir de `posicoes.csv`; contêm id de aparelho no filtro (não copiar).
