#!/usr/bin/env bash
# =====================================================================
# Detiene la plataforma completa en local (Ubuntu / Linux / macOS).
#
#   ./detener-local.sh
#
# Los contenedores se detienen y eliminan, pero los volumenes de
# datos (postgres-data) se conservan para poder reanudir sin perder
# la base de datos.
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m    %s\033[0m\n' "$1" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "Docker no esta instalado"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 no esta disponible"

log "Deteniendo servicios"
docker compose down

ok "Plataforma detenida"
echo "  Para volver a levantar:  ./arranque-local.sh"
