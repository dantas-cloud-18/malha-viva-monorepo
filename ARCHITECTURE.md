# Arquitetura

```mermaid
flowchart LR
    A[Android] -->|HTTPS em lote| B[Backend Ktor]
    I[iOS] -->|HTTPS em lote| B
    B --> P[(PostgreSQL + PostGIS)]
    B --> V[(Valkey)]
    B -->|SSE/WebSocket| A
    B -->|SSE/WebSocket| I
```

Contratos em `contracts/`. Backend em AWS sa-east-1 (Fargate + RDS PostGIS).

## Decisões
- Telemetria anônima, partições por hora, retenção de 24 h.
- Identidade por @handle; telefone e e-mail ocultos.
- Ingestão por HTTPS em lote. Compartilhar viagem só depois da V1.

## Apps
Clean Architecture com MVVM/MVI. Os módulos das duas plataformas se espelham:

```mermaid
flowchart TD
    app --> feature
    feature --> ui[core:ui]
    feature --> ds[core:designsystem]
    feature --> domain[core:domain]
    data[core:data] --> domain
    app --> data
```

Android: `:app`, `:core:{ui,designsystem,data,domain}`, `:feature:{auth,trains,stations,tracking,alerts,privacy,onboarding,achievements,widgets}`.
iOS: targets equivalentes em `MalhaVivaKit` (`DomainLayer`, `DataLayer`, `DesignSystem`, `CoreUI`, `Feature*`).
