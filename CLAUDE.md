# Malha Viva

Monorepo: `backend/` (Ktor), `android/`, `ios/`, `contracts/`, `infra/`. Português do Brasil.

- Antes de commitar: `ktlintCheck` e `detekt` no módulo alterado; testes verdes.
- Conventional Commits, commits pequenos, uma branch por fase. Sem segredos, sem `.env`.
- Código de produção sem rastros de ferramentas de IA; este arquivo e `.claude/` ficam fora dos releases.
- Contratos mandam: altere `contracts/*.yaml` antes do código.
- Versões: consulte Maven Central / Google Maven, não a memória.
- Android/Gradle: chame `.\gradlew.bat` pelo PowerShell.
