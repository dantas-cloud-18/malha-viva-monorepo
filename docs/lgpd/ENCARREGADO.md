# Encarregado pelo Tratamento de Dados Pessoais (DPO) e Canal do Titular — Malha Viva

> **RASCUNHO. Exige revisão jurídica antes de publicação.** O projeto prevê equipe própria para LGPD/DPO. Nenhum dado de pessoa foi inventado: preencha os placeholders.

Versão 0.1. Base: LGPD, art. 41 e Resolução CD/ANPD nº 18/2024.

## 1. Identificação (publicar de forma clara, de preferência no site do controlador)

| Campo | Valor |
|---|---|
| Controlador | [RAZÃO SOCIAL], CNPJ [CNPJ], [ENDEREÇO] |
| Encarregado | [NOME DO ENCARREGADO] |
| E-mail | [E-MAIL DO ENCARREGADO] |
| Formulário no site | [URL] |
| Canal no app | Ajustes > Privacidade > "Falar com o Encarregado" [PENDENTE: não existe ainda] |
| Endereço para correspondência | [ENDEREÇO] |

Divulgar a identidade e o contato do Encarregado na Política de Privacidade, no site e nas fichas das lojas.

## 2. Atribuições

1. Receber e responder reclamações e pedidos dos titulares (art. 18).
2. Receber comunicações da ANPD e adotar providências.
3. Orientar a equipe sobre boas práticas de proteção de dados.
4. Manter o RIPD, a LIA, o registro de operações e a lista de operadores atualizados.
5. Conduzir a resposta a incidentes (INCIDENTES.md) e as comunicações à ANPD e aos titulares.
6. Aprovar mudanças que afetem localização, menores ou novos operadores.

Independência: o Encarregado deve ter acesso à direção e não pode ser punido por exercer a função. Evitar conflito de interesses (por exemplo, acumular com a chefia de segurança ou de produto) [REVISAR COM O JURÍDICO].

## 3. Fluxo de atendimento ao titular

| Etapa | Prazo proposto |
|---|---|
| Confirmar o recebimento | 2 dias úteis |
| Verificar a identidade do solicitante (sem pedir mais dado que o necessário; para conta, exigir autenticação no app ou OTP no contato verificado) | Junto à confirmação |
| Responder pedido de confirmação de tratamento ou acesso | Imediato em formato simplificado, ou até 15 dias com declaração completa (art. 19) |
| Executar correção, eliminação, revogação | Até [PRAZO, proposta 15 dias], informando o titular |
| Registrar o pedido e a resposta | Sempre |

Pedidos de **menores**: aceitar do próprio adolescente e do responsável; para menores de 12 anos, atender o responsável após verificação.

Pedido sobre **posições**: explicar que as posições não são ligáveis à conta e são apagadas em até 24 h (RETENCAO.md), portanto não há dado individual a acessar ou excluir.

Não confirmar a existência de conta a quem não comprovar ser o titular (evita enumeração, na mesma linha do que o backend faz na recuperação).

## 4. Modelo de registro de pedido

| Campo | Valor |
|---|---|
| Protocolo | DSR-[AAAA]-[NNN] |
| Data de recebimento | |
| Titular (verificado: sim/não) e menor (sim/não) | |
| Direito exercido (art. 18, inciso) | |
| Operadores a acionar | |
| Resposta e data | |
| Evidência da execução (ex.: teste de exclusão) | |

## 5. Modelo de resposta

```
Assunto: Sua solicitação sobre dados pessoais — protocolo [PROTOCOLO]

Olá. Recebemos em [DATA] o seu pedido de [DIREITO].
[Confirmamos/Não foi possível confirmar] a sua identidade por [MEIO].
Resultado: [O QUE FOI FEITO, OU MOTIVO DE NÃO ATENDER, COM BASE LEGAL].
Anexo (se acesso): arquivo JSON com os dados da sua conta.
Se não concordar, você pode contatar este Encarregado ou a ANPD (gov.br/anpd).

[NOME DO ENCARREGADO], Encarregado de Dados — [RAZÃO SOCIAL]
```

## 6. Pendências

1. Designar a pessoa e publicar o ato de nomeação [MODELO A CRIAR].
2. Criar o e-mail dedicado e o formulário.
3. Implementar no produto os direitos do art. 18 (exportar JSON, corrigir, excluir com teste automatizado, revogar, histórico de consentimentos), hoje NAO IMPLEMENTADOS.
4. Definir o prazo interno de resposta e a ferramenta de registro dos pedidos.
