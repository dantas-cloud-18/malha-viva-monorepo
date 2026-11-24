# Malha Viva

App nativo (Android e iOS) de rastreamento colaborativo de trens. V1: Ramal Santa Cruz (Trens RJ).

| Pasta | Conteúdo |
|---|---|
| `backend/` | Kotlin + Ktor, PostgreSQL/PostGIS, Flyway |
| `android/` | Kotlin, Jetpack Compose, Hilt, módulos `core` e `feature` |
| `ios/` | SwiftUI, Swift Package `MalhaVivaKit`, XcodeGen |
| `contracts/` | `openapi.yaml` e `asyncapi.yaml` |
| `infra/` | Infraestrutura como código (AWS sa-east-1) |
| `scripts/`, `.githooks/` | Verificações locais |

## Começando
```sh
cp .env.example .env        # preencha os segredos; nunca commite o .env
docker compose up -d        # PostGIS + Valkey
git config core.hooksPath .githooks
```
- Backend: `cd backend && ./gradlew ktlintCheck detekt test` (testes usam Docker)
- Android: `cd android && ./gradlew assembleDebug ktlintCheck detekt lint testDebugUnitTest` (JDK 21, Android SDK)
- iOS: ver `ios/README.md` (precisa de macOS)

Arquitetura: `ARCHITECTURE.md`.
