# Fase 7: desligamento do Apps Script (plano)

Fonte: BRIEF.md (Fase 7) e DIAGNOSTICO.md (seções 2 e 7). Status: plano para aprovação, nada executado.

## Objetivo
Rodar o backend novo em paralelo ao Apps Script até a paridade comprovada, migrar os dados finais, arquivar o script em modo somente leitura e revogar credenciais e gatilhos antigos.

## Critério de desligamento (DIAGNOSTICO §7)
1. Backend novo no ar, em paralelo.
2. Paridade do motor comprovada nos mesmos dados de teste.
3. Nenhum cliente apontando para a URL antiga do Apps Script.

## Pré-requisitos que hoje faltam (decisões do Luciano)
- P1. Hospedagem do backend e do PostGIS em produção (provedor, região, custo) e domínio real; hoje a URL de release é placeholder.
- P2. Destino do PWA e do Supabase atual: desligar junto, migrar ou manter. O PWA também é cliente.
- P3. Quais clientes ainda usam a URL do Apps Script (Traccar, OwnTracks, PWA, testes manuais).
- P4. Retenção dos dados históricos da planilha: migrar, anonimizar ou descartar (o briefing proíbe ligar posição a pessoa).

## Etapas
7.1 Inventário de clientes e gatilhos: listar URLs de implantação, gatilhos de tempo, chaves e contas de serviço do Apps Script (leitura, sem alterar).
7.2 Harness de paridade: script que alimenta os mesmos insumos (posições de teste, incluindo a viagem v-08d8e02a) no motor do Apps Script (`Motor.gs`, rodado local via `teste do motor.js`) e no backend novo, e compara trens inferidos, sentido, posição ao longo da linha e paradas, com tolerâncias documentadas.
7.3 Implantação em paralelo (depende de P1): backend com CI de deploy, migrações Flyway, segredos no cofre do provedor, monitoramento básico.
7.4 Migração final (depende de P4): planilha para CSV, descarte de "Quem" e id do aparelho, carga nas tabelas, contagem de linhas por aba e checksum de amostra.
7.5 Corte: redirecionar clientes (P3) para o backend novo; janela de observação de 7 dias sem tráfego na URL antiga.
7.6 Desligamento: remover gatilhos, desfazer implantações web, revogar credenciais e tokens, arquivar o script e a planilha em somente leitura; registro em docs/relatorios.
7.7 Entrega final: docs/relatorios/FINAL.md (feito, métricas, segurança, cobertura, riscos residuais, pendências).

## O que posso fazer sem decisões
- 7.1 (inventário a partir dos arquivos locais `.gs`).
- 7.2 (harness de paridade local, sem tocar no Apps Script publicado).
- Esqueleto do FINAL.md.

## Riscos
- Divergência do motor por diferenças de filtro (Kalman no PWA x filtro do backend).
- Clientes esquecidos apontando para a URL antiga.
- Revogação de credenciais é irreversível: só com confirmação explícita.
