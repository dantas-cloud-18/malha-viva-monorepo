# Política para Crianças e Adolescentes — Malha Viva

> **RASCUNHO. Exige revisão jurídica antes de publicação.** Referências: LGPD art. 14 e Lei 15.211/2025 (ECA Digital). Faixas etárias, verificação de idade e vigência dos artigos do ECA Digital devem ser confirmadas pelo jurídico. Itens [PENDENTE] não existem ainda no código.

Versão 0.1. Controlador: [RAZÃO SOCIAL]. Encarregado: [E-MAIL DO ENCARREGADO].

## Parte A. Princípios (interno)

1. **Melhor interesse** da criança e do adolescente em todo tratamento (LGPD, art. 14, *caput*).
2. **Padrões protetivos por padrão**: privacidade máxima ligada, sem escolha do usuário para reduzi-la.
3. Para menores de 18 anos: **sem compartilhamento de localização** (bloqueio no servidor e no app), **sem marketing, sem perfilamento, sem publicidade direcionada**, sem selos de compartilhamento, **sem engajamento compulsivo** (sem streaks, sem notificações de insistência, sem ranking, sem perfil social).
4. **Minimização**: só handle e senha; telefone e e-mail opcionais, só para segurança.
5. **Verificação de idade proporcional** ao risco, sem coletar mais dados do que o necessário [PROPOSTA ABAIXO].
6. Informação **clara, em linguagem adequada à idade** (partes B e C).
7. Consentimento de responsável para menores de 12 anos, verificável.

## Parte A.2. Verificação de idade e consentimento parental (proposta)

| Item | Proposta | Estado |
|---|---|---|
| Declaração de faixa etária no cadastro (neutra, sem induzir a mentir) | Perguntar a data de nascimento ou faixa, sem estimular resposta falsa | NAO IMPLEMENTADO |
| Marcador de perfil (`sharingBlocked`) | Derivado da idade; bloqueia emissão de token de telemetria | Cliente recebe o marcador; fonte (perfil de idade) NAO IMPLEMENTADO |
| < 12 anos | Conta só com consentimento parental verificado: responsável informa o contato e confirma por OTP, mais verificação adicional [MECANISMO, ex.: confirmação por documento ou meio de pagamento — A DEFINIR com o jurídico] | NAO IMPLEMENTADO |
| 12 a 17 anos | Conta com padrões protetivos. Avaliar se o ECA Digital exige vínculo da conta do adolescente a um responsável para certa faixa [CONFIRMAR NO TEXTO DA LEI] | NAO IMPLEMENTADO |
| Mudança de faixa (aniversário) | Reavaliar proteções; aos 18 anos, localização só com novo consentimento do próprio titular | NAO IMPLEMENTADO |
| Dados de idade | Guardar só o necessário (faixa ou data), separado da telemetria | A definir |
| Menor que declarou idade falsa | Procedimento de revisão e bloqueio ao detectar | A definir |

## Parte B. Texto para adolescentes de 12 a 17 anos

**O que o Malha Viva faz**
Mostramos onde estão os trens e quais horários esperar. Você pode usar o app sem dar sua localização.

**O que fazemos diferente para você**
- **Não compartilhamos sua localização.** A função "Estou no trem" não fica disponível para menores de 18 anos.
- **Sem propaganda feita para você.** Não fazemos perfil seu para anúncios e não mandamos marketing.
- **Sem pressão para usar mais.** Não temos ranking, nem placar, nem aviso insistente para você voltar ao app.

**O que guardamos sobre você**
- O seu nome de usuário e a sua senha (a senha é guardada de um jeito que nem a gente consegue ler).
- Se você quiser, seu telefone ou e-mail, só para recuperar a sua conta se você esquecer a senha. Nunca para propaganda.

**O que você pode fazer**
- Ver quais aparelhos estão logados na sua conta e sair deles.
- Pedir para ver, corrigir ou apagar seus dados.
- Excluir sua conta quando quiser.

**Conversar com um adulto**
É uma boa ideia falar com um responsável sobre o que você usa na internet. Se algo parecer errado ou você tiver dúvida, escreva para [E-MAIL DO ENCARREGADO]. Nós respondemos em linguagem simples.

**Nunca peça nem passe**
Nunca passe sua senha ou os códigos que chegam por mensagem para ninguém. Nós nunca pedimos esses códigos.

## Parte C. Texto para responsáveis (crianças menores de 12 anos)

Para criar uma conta para uma criança com menos de 12 anos, precisamos do **seu consentimento específico e destacado**, como responsável legal (LGPD, art. 14, § 1º).

**Quem somos:** [RAZÃO SOCIAL], CNPJ [CNPJ]. Encarregado: [NOME], [E-MAIL DO ENCARREGADO].

**O que o app faz:** mostra a posição estimada de trens e horários. Não há chat, perfil público de criança, anúncios nem compras.

**Dados que tratamos da criança:** nome de usuário e senha (em hash). Telefone ou e-mail opcionais, só para recuperação da conta. **Não coletamos localização de menores**; a função de compartilhar posição é bloqueada.

**O que não fazemos:** marketing, perfilamento, publicidade direcionada, venda de dados, ranking, notificações de insistência.

**Como verificamos o seu consentimento:** [MECANISMO, A DEFINIR]. Guardamos o registro do consentimento (data, versão do texto, quem consentiu) separado de qualquer dado de uso.

**Seus direitos como responsável:** confirmar o tratamento, acessar, corrigir, excluir a conta e os dados, revogar o consentimento a qualquer momento, sem custo, por [E-MAIL DO ENCARREGADO] ou pelo app [PENDENTE].

**Se revogar:** a conta da criança é encerrada e os dados de conta são eliminados, ressalvado o que a lei exigir guardar.

**Incidentes:** em caso de incidente com risco ou dano relevante, avisaremos você e a ANPD.

**Consentimento (a ser coletado por mecanismo verificável):**
"Eu, [NOME DO RESPONSÁVEL], responsável legal por [NOME OU @USUARIO DA CRIANÇA], li e concordo com o tratamento descrito acima. Versão do texto: [VERSÃO]. Data: [DATA]."

## Parte D. Pendências

1. Mecanismo de verificação de idade e de consentimento parental (decisão do dono do produto e do jurídico).
2. Confirmar no texto da Lei 15.211/2025 faixas etárias, vínculo com responsável, vedações e vigência.
3. Implementar o perfil de idade, o bloqueio de telemetria para menores e o teste automatizado correspondente.
4. Revisão do texto da parte B por especialista em comunicação com adolescentes.
5. Avaliar a classificação indicativa e a declaração de público-alvo nas lojas (Fase 6).
