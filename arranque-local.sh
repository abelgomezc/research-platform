#!/usr/bin/env bash
# =====================================================================
# Levanta la plataforma completa en local (Ubuntu / Linux / macOS).
#
#   ./arranque-local.sh
#
# Pasos: variables -> modelos -> construccion -> arriba -> verificacion
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")"

MODELOS=("qwen3:8b" "nomic-embed-text")

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m    %s\033[0m\n' "$1" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "Docker no esta instalado"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 no esta disponible"

# ---------------------------------------------------------------------
log "Preparando configuracion"
# ---------------------------------------------------------------------
if [ ! -f .env ]; then
    cp .env.example .env
    ok "Se creo .env a partir de .env.example (revisa las contrasenas)"
else
    ok ".env ya existe, se respeta"
fi

# ---------------------------------------------------------------------
log "Descargando modelos de Ollama"
# ---------------------------------------------------------------------
if docker compose --profile with-ollama ps ollama >/dev/null 2>&1; then
    OLLAMA_URL="http://localhost:${OLLAMA_PORT:-11434}"
else
    OLLAMA_URL="${OLLAMA_HOST_URL:-http://localhost:11434}"
    ok "Usando el Ollama del host en $OLLAMA_URL"
fi

for modelo in "${MODELOS[@]}"; do
    if curl -sf "$OLLAMA_URL/api/tags" | grep -q "\"$modelo\""; then
        ok "$modelo ya esta descargado"
    else
        ok "Descargando $modelo (puede tardar varios minutos)"
        ollama pull "$modelo" >/dev/null 2>&1 || curl -sf -X POST "$OLLAMA_URL/api/pull" \
            -d "{\"model\":\"$modelo\",\"stream\":false}" >/dev/null || true
    fi
done

# ---------------------------------------------------------------------
log "Construyendo imagenes"
# ---------------------------------------------------------------------
docker compose build

# ---------------------------------------------------------------------
log "Levantando servicios"
# ---------------------------------------------------------------------
docker compose --profile with-ollama up -d

# ---------------------------------------------------------------------
log "Esperando a que el backend este listo"
# ---------------------------------------------------------------------
for intento in $(seq 1 60); do
    if curl -sf "http://localhost:${BACKEND_PORT:-8081}/actuator/health" >/dev/null 2>&1; then
        ok "Backend respondiendo"
        break
    fi
    if [ "$intento" -eq 60 ]; then
        fail "El backend no respondio tras 5 minutos. Revisa: docker compose logs backend"
    fi
    sleep 5
done

# ---------------------------------------------------------------------
log "Estado"
# ---------------------------------------------------------------------
curl -s "http://localhost:${BACKEND_PORT:-8081}/actuator/health" | head -c 400
echo
echo
echo "  Frontend:  http://localhost:${FRONTEND_PORT:-5174}"
echo "  API:       http://localhost:${BACKEND_PORT:-8081}"
echo "  OpenAPI:   http://localhost:${BACKEND_PORT:-8081}/swagger-ui.html"
echo "  Health:    http://localhost:${BACKEND_PORT:-8081}/actuator/health"
echo
