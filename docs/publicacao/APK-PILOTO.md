# APK de piloto (GitHub Release)

O release aceita `-Pmalhaviva.apiBaseUrl=https://...` (exige https) e assina com `MALHAVIVA_KEYSTORE_*` lidas do ambiente ou de `~/.gradle/gradle.properties`. Sem todas as quatro, o `assembleRelease` gera APK **sem assinatura** (nao instalavel). Pinning TLS fica desligado por padrao, o que serve para o host `sslip.io`.

## 1. Criar a keystore (uma vez, por voce)

Digite as senhas no prompt do `keytool`; nunca as cole em chat nem as versione. Guarde o arquivo fora do repositorio e faca backup: sem ele nao ha como atualizar o app instalado.

```powershell
keytool -genkeypair -v -keystore $HOME\malhaviva-piloto.jks -alias malhaviva -keyalg RSA -keysize 2048 -validity 10000
```

## 2. Gerar o APK assinado

```powershell
$env:MALHAVIVA_KEYSTORE_PATH = "$HOME\malhaviva-piloto.jks"
$env:MALHAVIVA_KEY_ALIAS = "malhaviva"
$env:MALHAVIVA_KEYSTORE_PASSWORD = Read-Host "senha da keystore"
$env:MALHAVIVA_KEY_PASSWORD = Read-Host "senha da chave"
cd android   # volte para a raiz do repositorio (cd ..) antes do passo 3
.\gradlew.bat :app:assembleRelease "-Pmalhaviva.apiBaseUrl=https://<IP>.sslip.io"
```

No PowerShell as aspas em `-P...` sao obrigatorias (sem elas o ponto quebra o argumento).

Saida: `android/app/build/outputs/apk/release/app-release.apk`.

## 3. Publicar como pre-release

```powershell
gh release create v0.1.0-piloto android/app/build/outputs/apk/release/app-release.apk --prerelease --title "Piloto 0.1.0" --notes "APK de piloto. Instalacao fora da Play Store."
```

## Observacoes

- O release usa captcha e integridade **stub**: o servidor do piloto precisa rodar com `MALHAVIVA_DEV_CAPTCHA=true` e `MALHAVIVA_DEV_INTEGRITY=true` (ja no `infra/oracle/.env.example`).
- `ACCESS_LOCAL_NETWORK` nao e necessario: o host e https publico.
- Testadores precisam permitir "instalar apps desconhecidos" para o navegador/gerenciador usado no download.
