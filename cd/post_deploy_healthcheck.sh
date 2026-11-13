#!/usr/bin/env bash
# Healthcheck posterior al despliegue (Guía 11, Ejercicio 4). Falla (código distinto de 0) si el sistema no queda sano.
# Uso: post_deploy_healthcheck.sh https://tu-servicio.onrender.com
# Ruta real de salud del backend: /actuator/health (el smoke test del pipeline exige "status":"UP").
set -euo pipefail

BASE_URL="${1:?Uso: $0 <url-base-sin-barra-final>}"
HEALTH_PATH="${HEALTH_PATH:-/actuator/health}"
TRIES="${TRIES:-30}"      # las capas gratuitas arrancan lento: 30 intentos x 10 s = 5 min
SLEEP="${SLEEP:-10}"

for i in $(seq 1 "$TRIES"); do
  body="$(curl -sS -m 10 "${BASE_URL}${HEALTH_PATH}" || true)"
  if printf '%s' "$body" | grep -q '"status":"UP"'; then
    echo "OK: ${BASE_URL}${HEALTH_PATH} responde UP (intento ${i})"
    # Comprobación mínima de seguridad activa: sin token, la API responde 401 (igual que el smoke test del CI).
    anon="$(curl -sS -m 10 -o /dev/null -w '%{http_code}' "${BASE_URL}/api/v1/players" || true)"
    [ "$anon" = "401" ] || { echo "FALLO: /api/v1/players sin token devolvió ${anon}, se esperaba 401"; exit 1; }
    exit 0
  fi
  echo "intento ${i}/${TRIES}: sin respuesta sana todavía"
  sleep "$SLEEP"
done

echo "FALLO: ${BASE_URL}${HEALTH_PATH} no respondió UP en $((TRIES * SLEEP)) s"
exit 1
