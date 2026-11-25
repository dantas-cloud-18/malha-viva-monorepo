# Plano de Resposta a Incidentes de Segurança com Dados Pessoais — Malha Viva

> **RASCUNHO. Exige revisão jurídica antes de qualquer uso.** Prazos e formulários da ANPD devem ser conferidos na norma vigente (Resolução CD/ANPD nº 15/2024 e atualizações) antes da adoção. Dados da empresa estão como placeholders.

Controlador: [RAZÃO SOCIAL]. Encarregado: [NOME], [E-MAIL DO ENCARREGADO], [TELEFONE PLANTÃO]. Base: LGPD, art. 48. Versão 0.1.

## 1. Escopo e definições

Incidente: evento adverso que comprometa confidencialidade, integridade ou disponibilidade de dado pessoal. Exemplos neste produto:

- Vazamento de `accounts` (handle, telefone, e-mail, hash de senha).
- Vazamento do pepper, do segredo JWT ou da chave `MALHAVIVA_OTP_KEY` (expõe a verificação de OTP, TOTP e códigos de backup).
- Acesso indevido a `telemetry_points` durante o TTL de 24 h.
- Ligação indevida entre conta e posição (quebra da separação de planos).
- Uso de credencial de admin comprometida.
- Operador (AWS, provedor de SMS ou e-mail) reporta incidente próprio.
- Envio de SMS ou e-mail a destinatário errado com conteúdo pessoal.

## 2. Papéis

| Papel | Responsável | Função |
|---|---|---|
| Coordenador do incidente | [NOME] | Decide contenção, comunica |
| Encarregado | [NOME] | Avalia risco ao titular, comunica ANPD e titulares |
| Técnico (backend/infra) | [NOME] | Contenção, evidências, correção |
| Jurídico | [NOME OU ESCRITÓRIO] | Parecer, texto de comunicação |
| Comunicação | [NOME] | Mensagens públicas e suporte |

## 3. Fluxo

1. **Detecção e triagem (imediato)**: qualquer pessoa da equipe avisa o Encarregado em [CANAL INTERNO]. Registrar hora do **conhecimento** (inicia os prazos).
2. **Contenção**: revogar sessões (família de refresh), rotacionar pepper, segredo JWT, chave OTP e credenciais afetadas; isolar o componente; preservar logs e evidências (cópia imutável, sem alterar o original).
3. **Avaliação de risco** (Encarregado, em horas, não dias): quais dados, quantos titulares, se há menores, se há localização, se o dado estava protegido (hash, cifra), probabilidade e gravidade do dano (fraude, discriminação, exposição de rotina, risco físico).
4. **Decisão de comunicar**: se o incidente puder acarretar **risco ou dano relevante** aos titulares, comunicar à ANPD e aos titulares. Em dúvida, tratar como relevante. Incidente com dados de crianças e adolescentes ou com localização tende a ser relevante.
5. **Comunicação** conforme seção 4.
6. **Erradicação e recuperação**: corrigir a causa, restaurar, validar, monitorar.
7. **Pós-incidente**: relatório com causa raiz, linha do tempo, lições, atualização do RIPD e do `docs/THREAT-MODEL.md`.
8. **Registro**: manter registro interno de **todos** os incidentes (inclusive os não comunicados) pelo prazo da norma vigente [PRAZO, a confirmar: a Resolução 15/2024 prevê 5 anos].

## 4. Prazos de comunicação (a conferir na norma vigente)

| Destinatário | Prazo | Observação |
|---|---|---|
| ANPD | **3 dias úteis** contados do conhecimento de que o incidente afetou dado pessoal e pode gerar risco ou dano relevante | Formulário eletrônico da ANPD |
| Titulares | Mesmo prazo de 3 dias úteis, por canal direto (e-mail, SMS, notificação no app) | Linguagem clara. Para menores, dirigir também ao responsável |
| Complemento à ANPD | Até **20 dias úteis** do primeiro comunicado, se faltarem informações | Pode ser pedido pela ANPD |
| Operador → Controlador | Conforme contrato: proposta **24 horas** do conhecimento | Incluir em cada contrato de operador |

Se o controlador atuar como operador de terceiros, avisar o controlador imediatamente. Não esperar apuração completa para comunicar: complementar depois.

## 5. Conteúdo mínimo da comunicação (art. 48, § 1º)

1. Descrição da natureza dos dados afetados.
2. Informações sobre os titulares envolvidos.
3. Medidas técnicas e de segurança utilizadas, observados os segredos comercial e industrial.
4. Riscos relacionados ao incidente.
5. Motivos da demora, se a comunicação não foi imediata.
6. Medidas adotadas ou a adotar para reverter ou mitigar os efeitos.

## 6. Modelo: comunicação à ANPD

```
Assunto: Comunicação de incidente de segurança com dados pessoais — [RAZÃO SOCIAL]

Controlador: [RAZÃO SOCIAL], CNPJ [CNPJ]. Encarregado: [NOME], [E-MAIL DO ENCARREGADO].
Data/hora do incidente: [DATA/HORA]. Data/hora do conhecimento: [DATA/HORA]. Data/hora desta comunicação: [DATA/HORA].
Tipo: [vazamento / acesso indevido / perda / alteração / indisponibilidade].
Descrição: [O QUE ACONTECEU, SISTEMA, CAUSA CONHECIDA].
Dados afetados: [CATEGORIAS: handle, telefone, e-mail, hash de senha, posições, outros]. Dado sensível: [SIM/NÃO]. Dados de crianças/adolescentes: [SIM/NÃO].
Titulares afetados: [NÚMERO ou ESTIMATIVA]. Menores: [NÚMERO].
Medidas de segurança existentes (cifra, hash, TTL): [DESCRIÇÃO].
Riscos aos titulares: [DESCRIÇÃO].
Medidas de contenção e mitigação: [DESCRIÇÃO].
Titulares comunicados: [SIM/NÃO, DATA, CANAL]. Se não, motivo: [TEXTO].
Operadores envolvidos: [LISTA].
Informações pendentes e previsão de complemento: [TEXTO].
```

## 7. Modelo: comunicação ao titular

```
Assunto: Aviso de segurança sobre sua conta Malha Viva

Olá. Em [DATA], identificamos [DESCRIÇÃO SIMPLES DO QUE ACONTECEU].

O que foi afetado: [DADOS, em linguagem simples. Ex.: seu nome de usuário e seu e-mail].
O que NÃO foi afetado: [Ex.: sua senha em texto, que nunca guardamos; a sua localização, que não fica ligada à sua conta].
O que já fizemos: [MEDIDAS].
O que recomendamos que você faça: [Ex.: troque sua senha; desconfie de mensagens pedindo códigos; confira "Dispositivos conectados"].
Riscos: [DESCRIÇÃO HONESTA].
Dúvidas: [E-MAIL DO ENCARREGADO].

Pedimos desculpas pelo ocorrido.
[RAZÃO SOCIAL]
```

Versão para menores e responsáveis: usar o texto acima, sem jargão, e enviar também ao canal do responsável quando houver (ver POLITICA-MENORES.md).

## 8. Modelo: registro interno de incidente

| Campo | Valor |
|---|---|
| ID | INC-[AAAA]-[NNN] |
| Conhecimento | [DATA/HORA] |
| Descrição | |
| Dados e titulares | |
| Severidade e risco relevante (S/N) | |
| ANPD comunicada (data/protocolo) | |
| Titulares comunicados (data/canal) | |
| Contenção | |
| Causa raiz | |
| Correção | |
| Lições e mudanças em documentos | |

## 9. Preparação

- Manter lista de contatos de plantão e dos operadores atualizada.
- Procedimentos de rotação de segredos escritos e testados. **Hoje a rotação de pepper, segredo JWT e chave OTP é NAO IMPLEMENTADA** (`docs/THREAT-MODEL.md`, pendência 5): sem ela, a contenção de um vazamento de segredo é lenta. Resolver antes da produção.
- Trilha de auditoria de autenticação, recuperação e troca de contato (**NAO IMPLEMENTADO**): necessária para reconstruir um incidente.
- Exercício de simulação (tabletop) ao menos uma vez por ano.
