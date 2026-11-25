# Política de segurança

Este documento descreve como reportar vulnerabilidades no Malha Viva e o que esperar depois do relato.

## Como reportar

Use o **relato privado de vulnerabilidades do GitHub** deste repositório: aba **Security**, botão
**Report a vulnerability**. O relato fica visível só para os mantenedores.

- **Não** abra issue pública, pull request ou discussão com detalhes de uma vulnerabilidade.
- Inclua: componente afetado (backend, Android, iOS, contratos, infraestrutura), passos para reproduzir,
  impacto que você observou e versão ou commit testado.
- Não inclua dados pessoais reais de terceiros nem posições de localização reais. Use dados de teste.
- Se você encontrar um segredo (chave, token, senha) no repositório ou no histórico, relate da mesma forma
  e não o use.

## Metas de resposta

São metas de trabalho, não garantias ou compromissos contratuais. O projeto é mantido por poucas pessoas.

| Etapa | Meta |
|---|---|
| Confirmação de recebimento | até 5 dias úteis |
| Avaliação inicial (reproduzida ou não, gravidade estimada) | até 15 dias corridos |
| Correção ou plano de mitigação para falhas críticas e altas | até 90 dias corridos |

Avisaremos pelo próprio relato quando houver correção. Se o relato envolver dados pessoais, o projeto
seguirá o procedimento de incidentes da LGPD (art. 48) previsto para a Fase 5.

## Escopo

Dentro do escopo:

- API do backend (`backend/`) e os contratos em `contracts/`.
- App Android (`android/`) e app iOS (`ios/`), incluindo widgets e Live Activities.
- Infraestrutura como código em `infra/` e os workflows de CI em `.github/`.
- Falhas que permitam: identificar quem enviou uma posição, forjar ou envenenar posições de trens,
  burlar a atestação do cliente, assinatura, nonce ou limites de taxa, acessar dados de outra conta,
  ou expor segredos.

## Fora do escopo

- Ataques de negação de serviço volumétricos e testes automatizados de carga contra ambientes em produção.
- Engenharia social contra mantenedores ou usuários, e ataques físicos.
- Vulnerabilidades em dependências de terceiros sem um caminho de exploração demonstrado neste projeto
  (relate ao projeto original; avise-nos se houver impacto aqui).
- Relatos de scanners sem prova de impacto, ausência de cabeçalhos HTTP sem consequência demonstrada,
  e problemas que exigem aparelho com root/jailbreak controlado pelo próprio atacante contra a própria conta.
- Funcionalidades ainda não implementadas. O estado atual das proteções, incluindo o que falta, está em
  [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md); lacunas ali listadas como "NAO IMPLEMENTADO" já são conhecidas.
- Serviços de terceiros (GitHub, lojas de aplicativos, provedores de nuvem).

## Versões suportadas

O projeto está em desenvolvimento e ainda não tem versão publicada nas lojas. Até a primeira versão estável,
só o branch `main` recebe correções de segurança. Depois do lançamento, esta seção passará a listar
as versões do app e do backend suportadas.

## Boa-fé na pesquisa

Pesquisa feita de boa-fé, dentro deste escopo, sem acessar dados de outras pessoas, sem degradar o serviço e
com relato privado, será tratada como colaboração e não motivará ação do projeto. Isto não é um
programa de recompensas e não substitui a lei aplicável nem autoriza acesso a sistemas de terceiros.
