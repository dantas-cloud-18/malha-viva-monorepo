# Deploy do piloto na Oracle Cloud (Always Free)

Uma VM ARM (Ampere A1) roda tudo: Postgres/PostGIS, backend Ktor e Caddy (HTTPS automatico).

## Passo a passo

1. **Conta**: crie a conta Oracle Cloud Free Tier (regiao inicial **Brazil Southeast - Sao Paulo**, `sa-saopaulo-1`; nao muda depois). O cartao serve so para verificacao; nao ha cobranca dentro dos limites Always Free.
2. **VM**: Compute > Instances > Create. Image **Ubuntu 24.04**, shape **VM.Standard.A1.Flex** com **2 OCPU / 12 GB**, boot volume 50 GB, IP publico atribuido, adicione sua chave SSH publica.
3. **Rede**: Networking > VCN da instancia > Security List > Add Ingress Rules: origem `0.0.0.0/0`, TCP, portas **80** e **443** (a 22 ja existe).
4. **SSH**: `ssh ubuntu@<IP-PUBLICO>`
5. **Codigo**:
   ```bash
   git clone <URL-DO-REPO> malha-viva && cd malha-viva/infra/oracle
   ```
6. **Setup**: `./setup.sh`. Ele instala Docker, libera 80/443 no iptables (`/etc/iptables/rules.v4`), cria `.env` com segredos aleatorios e `DOMAIN=<IP>.sslip.io`, sobe a stack e agenda backup diario (03:00, 7 copias em `/var/backups/malhaviva`). O primeiro build do backend leva alguns minutos. Se o `docker` pedir permissao, saia e entre de novo no SSH.
7. **Teste**: `https://<IP>.sslip.io/v1/network/corridors/RAMAL_SANTA_CRUZ` deve devolver JSON (o certificado leva ~1 min na primeira vez).
8. Para outro dominio (ex.: DuckDNS), edite `DOMAIN` no `.env` e rode `docker compose up -d`.

Operacao: `docker compose logs -f backend`, `docker compose ps`. Restaurar backup: `gunzip -c db-AAAA-MM-DD.sql.gz | docker compose exec -T db psql -U $DB_USER $DB_NAME`.

## Notas e riscos

- **Imagem do Postgres**: `postgis/postgis:17-3.5` nao tem arm64; usamos `imresamu/postgis:17-3.5` (multi-arch).
- **Flags de piloto**: `MALHAVIVA_DEV_CAPTCHA` e `MALHAVIVA_DEV_INTEGRITY` = `true` desligam captcha e Play Integrity reais (o APK de piloto usa stubs). Qualquer um com a URL pode criar contas: divulgue a URL so ao grupo do piloto. Existe rate limit por IP no cadastro, atras do Caddy (X-Forwarded-For). `MALHAVIVA_MESSAGE_SENDER=log` nao envia SMS/e-mail: codigos OTP aparecem so no log do servidor.
- **Reclamacao por ociosidade**: a Oracle pode recuperar instancias Always Free com uso baixo (CPU/rede/memoria abaixo de ~20% por 7 dias). Mantenha trafego/monitoramento e backups copiados para fora da VM.
- **Falta de capacidade** (`Out of host capacity`): tente de novo mais tarde, outro Availability Domain, ou menos OCPU/RAM (1 OCPU / 6 GB basta para o piloto).
- Backups ficam na mesma VM; copie com `scp` periodicamente.
- O `.env` contem segredos: nunca versionar (ja esta no `.gitignore`).
