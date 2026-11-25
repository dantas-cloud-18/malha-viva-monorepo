#!/usr/bin/env bash
# Prepara a VM (Ubuntu 24.04 ARM): Docker, firewall 80/443, segredos, stack e backup diario.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
fi

# Imagens Oracle bloqueiam tudo no iptables exceto 22: insere 80/443 antes da regra REJECT e persiste.
for p in 80 443; do
  sudo iptables -C INPUT -p tcp --dport "$p" -j ACCEPT 2>/dev/null ||
    sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport "$p" -j ACCEPT
done
sudo mkdir -p /etc/iptables
sudo sh -c 'iptables-save > /etc/iptables/rules.v4'
sudo apt-get install -y -qq iptables-persistent >/dev/null 2>&1 || true

if [ ! -f .env ]; then
  cp .env.example .env
  ip=$(curl -fsS https://ifconfig.me || true)
  [ -n "$ip" ] && sed -i "s|^DOMAIN=.*|DOMAIN=${ip}.sslip.io|" .env
  sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" .env
fi
for k in MALHAVIVA_PEPPER MALHAVIVA_JWT_SECRET MALHAVIVA_OTP_KEY; do
  grep -q "^$k=.\+" .env || sed -i "s|^$k=.*|$k=$(openssl rand -base64 48 | tr -d '\n/|&')|" .env
done
chmod 600 .env

sudo docker compose up -d --build

# Backup diario 03:00, mantem 7.
sudo mkdir -p /var/backups/malhaviva
dir="$(pwd)"
sudo sh -c "cat > /etc/cron.d/malhaviva-backup" <<CRON
0 3 * * * root cd $dir && set -a && . ./.env && set +a && docker compose exec -T db pg_dump -U \$DB_USER \$DB_NAME | gzip > /var/backups/malhaviva/db-\$(date +\%F).sql.gz && find /var/backups/malhaviva -name 'db-*.sql.gz' -mtime +6 -delete
CRON
echo "OK. Teste: https://$(grep ^DOMAIN= .env | cut -d= -f2)/v1/network/corridors/RAMAL_SANTA_CRUZ"
