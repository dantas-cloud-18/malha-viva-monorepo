# Google Play: Classificação de conteúdo, público-alvo e anúncios

> **RASCUNHO. Decisões de público e idade são do dono do produto com o jurídico.** Base: `docs/lgpd/POLITICA-MENORES.md`, `docs/lgpd/RIPD.md`, `contracts/openapi.yaml`. Os textos exatos dos questionários do Play e da IARC mudam; refazer a conferência no Console no momento do envio.

## 1. Anúncios

| Pergunta | Resposta |
|---|---|
| O app contém anúncios? | **Não** |
| SDKs de anúncio ou de atribuição de publicidade? | Nenhum. Não usar ID de publicidade |

Coerente com a política (sem marketing, sem perfilamento, sem publicidade direcionada). Se um dia houver anúncios, rever Data safety, público-alvo e a política de privacidade.

## 2. Público-alvo e conteúdo (Play Console > Conteúdo do app)

### 2.1 Estado do produto

- O backend implementa portão de idade: `birth_date` obrigatório no cadastro, faixa derivada (`under_12`, `teen_12_17`, `adult`), conta de menor de 12 em `pending_parental_consent` com consentimento do responsável (`/auth/parental-consent/*`). A faixa é **autodeclarada** (não há verificação forte; RIPD R4 segue aberto).
- Menores de 18 anos não compartilham localização (bloqueio no servidor e no app).
- Sem chat, perfil público de criança, anúncios, compras ou ranking.

### 2.2 Decisão recomendada

**Faixas de público-alvo: 13 a 15, 16 a 17 e 18 ou mais. Não incluir "12 ou menos" e não declarar o app como feito para crianças.**

Justificativa:

1. Incluir menores de 13 no público-alvo aplica a **Política de Famílias** do Play (Families). Implicações, em resumo: SDKs apenas certificados para famílias; proibido ID de publicidade e coleta de dados sensíveis; restrições a permissões sensíveis e à coleta de localização em apps para crianças; declaração e conformidade com COPPA/GDPR-K e leis locais; verificação na revisão. O app usa permissão de localização (opcional, mas pedida pelo app) e atestação do dispositivo; entrar no programa Families aumenta muito o risco de rejeição, sem benefício de produto.
2. O produto é utilitário de transporte, sem apelo infantil (identidade premium, minimalista, sem mascote).
3. A política interna já vê < 12 como exceção protegida (consentimento parental), não como público-alvo.

**Inconsistência a decidir (pergunta ao dono):** o backend aceita contas `under_12` com responsável. Declarar 13+ no Play e aceitar < 12 é incoerente. Opções:
- **A (recomendada):** a build das lojas **bloqueia cadastro de menores de 13** com mensagem educada ("Disponível a partir de 13 anos") e mantém o fluxo parental apenas para teste/uso futuro. Menos risco no Play e na App Store; exige decisão jurídica sobre o ECA Digital (Lei 15.211/2025) — confirmar se a vedação é suficiente ou se se exige alternativa.
- **B:** manter o fluxo parental e declarar < 13 no público-alvo → aplicar a Política de Famílias integralmente (revisar SDKs, permissões e atestação, e considerar remover a função de localização desse perfil na build).

"O app atrai crianças?" (pergunta do Play para apps que não são para crianças): responder **Não**, com a opção A.

### 2.3 Respostas da seção "Público-alvo e conteúdo"

| Pergunta | Resposta (opção A) |
|---|---|
| Faixas de idade | 13-15, 16-17, 18+ |
| O app pode atrair crianças involuntariamente? | Não |
| Programa Families / anúncios para crianças | Não aplicável |
| Seção de apps de notícias, COVID, governo, saúde, finanças | Não. (Transporte público não é "governo" salvo se o app for oficial: o dono confirma que **não** é representante de órgão público, para não acionar a declaração de apps governamentais) |
| Conta: método de login | Handle e senha (sem Google/Facebook). Fornecer **instruções de acesso para revisão** (usuário e senha de teste) |
| Exclusão de conta | URL e fluxo no app (ver Data safety) |

## 3. Questionário de classificação de conteúdo (IARC, via Play Console)

Categoria: **Utilitário, Produtividade, Comunicação ou outros** (provável: "Utilidade/Referência"). Respostas por pergunta típica:

| Tema | Resposta |
|---|---|
| Violência (realista ou fantasia, sangue, abuso) | Não |
| Sexualidade e nudez | Não |
| Linguagem imprópria | Não |
| Drogas, álcool e tabaco (referência ou uso) | Não |
| Jogos de azar (simulados ou reais) | Não |
| Conteúdo gerado pelo usuário visível a outros (texto, imagem, chat) | **Não.** O app não tem chat nem postagens. O handle é público por desenho; confirmar que nenhuma tela exibe handles de outros usuários |
| Interação entre usuários | **Não** (sem mensagens, sem perfil social) |
| O app compartilha a localização do usuário com outros usuários? | **Resposta cuidadosa:** o app exibe a posição agregada de trens, derivada de vários passageiros anonimizados, sem identificar ninguém. Responder **Não** para "compartilha localização com outros usuários" **se** o limiar mínimo de sinais (k) estiver implementado; **hoje não está** (RIPD R2). Enquanto k não existir, tratar como **Sim** para não subdeclarar, o que pode elevar a classificação. [DECIDIR após implementar k] |
| Compras digitais | Não |
| Acesso irrestrito à internet / navegador web | Não |
| Anúncios | Não |
| Conteúdo de terceiros (feeds de notícias) | Não |

Resultado esperado: **Livre (ClassInd)**, PEGI 3, ESRB Everyone. Um "Sim" em localização compartilhada pode acrescentar o aviso "Compartilha localização" e, em alguns países, subir a faixa. Aceitar o resultado calculado; não tentar forçar. Guardar o PDF do certificado IARC para auditoria.

## 4. Itens do Console ligados a esta seção

- [ ] Política de privacidade (URL).
- [ ] Declaração de FGS location (ver `PLAY-LOCALIZACAO-SEGUNDO-PLANO.md`).
- [ ] Data safety (ver `PLAY-DATA-SAFETY.md`).
- [ ] ID de publicidade: declarar **não usa**.
- [ ] Instruções de acesso do app para revisão (conta de teste adulta, 2FA desligado).
- [ ] Contato de segurança e e-mail público do desenvolvedor.
