# Notas de seguranca do app Android (Fase 4.3)

Resumo honesto: o que existe, o que NAO esta configurado.

## O que existe
- `SecretStore` / `KeystoreSecretStore` (`:core:data`): AES-256-GCM no Android Keystore, chave nao exportavel,
  IV aleatorio por escrita, falha fechada (chave invalidada => entrada apagada, retorna null). Ainda nao usado
  por nenhuma feature. Tokens de telemetria continuam so em memoria, de proposito.
- Release: R8 + shrink, logs `Log.v/d/i` removidos (`app/proguard-rules.pro`), `debuggable=false`,
  `usesCleartextTraffic=false`, sem backup (`allowBackup=false` + `data_extraction_rules` excluindo todos os dominios).
  Cleartext so no debug (`app/src/debug/.../network_security_config.xml`, apenas 10.0.2.2 e localhost).
- `SecureScreen` (`:core:designsystem`): FLAG_SECURE enquanto a tela esta composta. Ainda sem uso.
- `DeviceRiskSignals` (`:core:data`): su, test-keys, debugger, emulador. Sinal FRACO e contornavel, nunca bloqueia.
  Atestacao real e Play Integrity, NAO integrado.

## Pinning de certificado: DESLIGADO
O dominio da API e os certificados reais ainda nao existem. Nenhum pin foi inventado. O release usa so a CA do
sistema, sem cleartext.

Para ligar (somente release; so ha pin-set no dominio de release, nunca no debug):

    .\gradlew.bat :app:assembleRelease `
      -Pmalhaviva.apiBaseUrl=https://api.exemplo.com.br `
      -Pmalhaviva.pinSha256=<spki-atual>,<spki-backup> `
      -Pmalhaviva.pinExpiration=2027-06-30

- Pin = SHA-256 do SPKI em base64. Obter: `openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
- A build FALHA com menos de 2 pins (backup obrigatorio), pin malformado ou sem `pinExpiration`.
- `pinExpiration`: passada a data, o Android deixa de aplicar o pin (nao bloqueia o app). Ajuste a cada release.

## Plano de rotacao
1. Gere com antecedencia a proxima chave (par de chaves) e guarde o SPKI como pin de BACKUP; ele ja vai no app
   junto com o atual. A chave privada do backup fica offline.
2. Release N: pins = {atual, backup}. Distribua e aguarde a adocao (acompanhe a versao minima ativa).
3. Troque o certificado do servidor para a chave de backup (so depois que a maioria dos usuarios esta na release N).
4. Release N+1: pins = {nova atual (antiga backup), novo backup}. Renove `pinExpiration`.
5. Emergencia (chave comprometida): trocar para o backup imediatamente e publicar release com novo backup;
   usuarios em versoes antigas ficam sem conexao ate atualizar (aceite consciente; considere forcar atualizacao).
6. Nunca pine apenas a folha sem backup, e nao pine CA intermediaria que voce nao controla sem avaliar o risco.

## Nao testado em aparelho
Keystore real (StrongBox/TEE), FLAG_SECURE na janela, deteccao de root em aparelho rooteado, e o handshake com
pin-set (nao ha dominio ainda). Testados em JVM: AesGcmCodec, contador do FLAG_SECURE, logica dos sinais.
