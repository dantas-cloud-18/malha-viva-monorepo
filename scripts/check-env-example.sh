#!/usr/bin/env sh
# Valida .env.example: formato CHAVE=valor e nenhum segredo preenchido.
set -eu
file="${1:-.env.example}"
status=0
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in ''|'#'*) continue ;; esac
  key=${line%%=*}
  value=${line#*=}
  if ! printf '%s' "$line" | grep -Eq '^[A-Z][A-Z0-9_]*='; then
    echo "$file: linha invalida: $line" >&2; status=1; continue
  fi
  if printf '%s' "$key" | grep -Eq '(KEY|PASSWORD|PEPPER|SECRET|TOKEN)$' && [ -n "$value" ]; then
    echo "$file: $key deve ficar vazio" >&2; status=1
  fi
done < "$file"
exit $status
