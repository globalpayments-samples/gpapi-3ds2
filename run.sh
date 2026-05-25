#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME_DEFAULT="/usr/lib/jvm/java-25-temurin-jdk"
FRONTEND_PORT="${FRONTEND_PORT:-8000}"

cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
GP-API 3DS2 helper

Usage:
  ./run.sh check                 Run syntax/build checks
  ./run.sh dev <backend>         Start frontend + backend for browser testing
  ./run.sh smoke <backend|all>   Run real GP-API CLI smoke tests
  ./run.sh setup <backend|all>   Install/restore backend dependencies
  ./run.sh docker <command>      Manage Docker Compose services

Backends:
  node     http://localhost:3001
  php      http://localhost:8003
  dotnet   http://localhost:8006
  java     http://localhost:8004

Examples:
  ./run.sh dev node
  ./run.sh smoke node
  ./run.sh smoke all
  ./run.sh check
  ./run.sh docker up
  ./run.sh docker smoke
USAGE
}

log() {
  printf '\n==> %s\n' "$*"
}

backend_port() {
  case "${1:-}" in
    node) printf '3001' ;;
    php) printf '8003' ;;
    dotnet) printf '8006' ;;
    java) printf '8004' ;;
    *) printf 'Unknown backend: %s\n' "${1:-}" >&2; exit 2 ;;
  esac
}

backend_env_file() {
  case "${1:-}" in
    node) printf 'nodejs/.env' ;;
    php) printf 'php/.env' ;;
    dotnet) printf 'dotnet/.env' ;;
    java) printf 'java/.env' ;;
    *) printf 'Unknown backend: %s\n' "${1:-}" >&2; exit 2 ;;
  esac
}

env_value() {
  local file="$1"
  local key="$2"
  awk -F= -v key="$key" '
    $0 !~ /^[[:space:]]*#/ && $1 == key {
      sub(/^[^=]*=/, "")
      gsub(/^[[:space:]]+|[[:space:]]+$/, "")
      gsub(/^"|"$/, "")
      gsub(/^'\''|'\''$/, "")
      print
      exit
    }
  ' "$file"
}

validate_backend_env() {
  local backend="$1"
  local file
  local missing=0
  file="$(backend_env_file "$backend")"

  if [ ! -f "$file" ]; then
    printf 'Missing %s. Copy %s.example and add credentials.\n' "$file" "$file" >&2
    return 1
  fi

  for key in GP_APP_ID GP_APP_KEY; do
    if [ -z "$(env_value "$file" "$key")" ]; then
      printf '%s is missing %s.\n' "$file" "$key" >&2
      missing=1
    fi
  done

  if [ -z "$(env_value "$file" GP_ACCOUNT_ID)" ] && [ -z "$(env_value "$file" GP_ACCOUNT_NAME)" ]; then
    printf '%s must set GP_ACCOUNT_ID or GP_ACCOUNT_NAME.\n' "$file" >&2
    missing=1
  fi

  local challenge_url
  challenge_url="$(env_value "$file" CHALLENGE_NOTIFICATION_URL)"
  if [[ ! "$challenge_url" =~ ^https:// ]]; then
    printf '%s must set CHALLENGE_NOTIFICATION_URL to an HTTPS URL for 3DS auth smoke tests.\n' "$file" >&2
    missing=1
  fi

  [ "$missing" -eq 0 ]
}

start_frontend() {
  log "Starting frontend on http://localhost:${FRONTEND_PORT}"
  python3 -m http.server "$FRONTEND_PORT" >/tmp/gpapi-3ds-frontend.log 2>&1 &
  FRONTEND_PID=$!
}

start_backend() {
  local backend="$1"
  case "$backend" in
    node)
      log "Starting Node backend on http://localhost:3001"
      (cd nodejs && PORT=3001 FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-http://localhost:${FRONTEND_PORT}}" node server.js) >/tmp/gpapi-3ds-node.log 2>&1 &
      ;;
    php)
      log "Starting PHP backend on http://localhost:8003"
      (cd php && FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-http://localhost:${FRONTEND_PORT}}" php -S 0.0.0.0:8003 router.php) >/tmp/gpapi-3ds-php.log 2>&1 &
      ;;
    dotnet)
      log "Starting .NET backend on http://localhost:8006"
      (cd dotnet && GP_SAMPLE_PORT=8006 FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-http://localhost:${FRONTEND_PORT}}" dotnet run --no-build) >/tmp/gpapi-3ds-dotnet.log 2>&1 &
      ;;
    java)
      log "Starting Java backend on http://localhost:8004"
      (cd java && FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-http://localhost:${FRONTEND_PORT}}" JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}" mvn -q -DskipTests cargo:run) >/tmp/gpapi-3ds-java.log 2>&1 &
      ;;
    *)
      printf 'Unknown backend: %s\n' "$backend" >&2
      exit 2
      ;;
  esac
  BACKEND_PID=$!
}

cleanup() {
  local pid
  for pid in ${FRONTEND_PID:-} ${BACKEND_PID:-}; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
}

wait_for_backend() {
  local backend="$1"
  local port
  local log_file
  port="$(backend_port "$backend")"
  log_file="/tmp/gpapi-3ds-${backend}.log"

  for _ in $(seq 1 45); do
    if curl -fsS "http://localhost:${port}/api/health" >/dev/null 2>&1; then
      return 0
    fi
    if [ -n "${BACKEND_PID:-}" ] && ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      printf 'Backend exited before becoming healthy: %s\n' "$backend" >&2
      printf 'Last log lines:\n' >&2
      tail -40 "$log_file" 2>/dev/null >&2 || true
      return 1
    fi
    sleep 1
  done

  printf 'Backend did not become healthy: %s on port %s\n' "$backend" "$port" >&2
  printf 'Last log lines:\n' >&2
  tail -40 "$log_file" 2>/dev/null >&2 || true
  return 1
}

run_check() {
  log "Node syntax"
  node --check nodejs/server.js
  node --check nodejs/gp-sdk.js

  log "Frontend inline JavaScript syntax"
  node -e "const fs=require('fs'); const html=fs.readFileSync('index.html','utf8'); const scripts=[...html.matchAll(/<script>([\\s\\S]*?)<\\/script>/g)].map(m=>m[1]).join('\\n'); new Function(scripts); console.log('frontend inline JS syntax ok');"

  log "PHP syntax"
  php -l php/api/initiate-auth.php
  php -l php/api/get-auth-result.php
  php -l php/api/authorize-payment.php
  php -l php/api/check-enrollment.php
  php -l php/api/tokenization-config.php
  php -l php/src/GpApiClient.php
  php -l php/router.php

  log ".NET build"
  (cd dotnet && dotnet build --no-restore)

  log "Java package"
  (cd java && JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}" mvn -q -DskipTests package)
}

run_setup() {
  local backend="$1"
  case "$backend" in
    node) (cd nodejs && npm install) ;;
    php) (cd php && composer install) ;;
    dotnet) (cd dotnet && dotnet restore) ;;
    java) (cd java && JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}" mvn -q -DskipTests package) ;;
    all)
      run_setup node
      run_setup php
      run_setup dotnet
      run_setup java
      ;;
    *) printf 'Unknown backend: %s\n' "$backend" >&2; exit 2 ;;
  esac
}

run_dev() {
  local backend="$1"
  trap cleanup EXIT INT TERM
  start_frontend
  start_backend "$backend"
  wait_for_backend "$backend"

  cat <<EOF

Ready.
  Frontend: http://localhost:${FRONTEND_PORT}
  Backend:  http://localhost:$(backend_port "$backend")

Use Hosted Fields with:
  Card:   4263970000005262
  Expiry: 12 / 2026
  CVV:    123

Press Ctrl+C to stop.
EOF

  wait
}

run_smoke_one() {
  local backend="$1"
  local port
  port="$(backend_port "$backend")"
  validate_backend_env "$backend"

  trap cleanup EXIT INT TERM
  BACKEND_PID=
  start_backend "$backend"
  wait_for_backend "$backend"

  log "Running smoke test for ${backend}"
  ./test-all-cards.sh "$port" "$backend"

  cleanup
  trap - EXIT INT TERM
}

run_smoke() {
  local target="$1"
  case "$target" in
    all)
      run_smoke_one node
      run_smoke_one php
      run_smoke_one dotnet
      run_smoke_one java
      ;;
    node|php|dotnet|java)
      run_smoke_one "$target"
      ;;
    *) printf 'Unknown smoke target: %s\n' "$target" >&2; exit 2 ;;
  esac
}

compose() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    printf 'Docker Compose not found. Install Docker Desktop or docker compose.\n' >&2
    exit 1
  fi
}

check_docker_env() {
  local missing=0
  local file
  for file in node php dotnet java; do
    if ! validate_backend_env "$file"; then
      missing=1
    fi
  done
  [ "$missing" -eq 0 ]
}

run_docker_smoke() {
  local status=0

  compose up --build -d nodejs php dotnet java

  {
    log "Waiting for Docker services"
    for backend in node php dotnet java; do
      wait_for_backend "$backend"
    done

    for backend in node php dotnet java; do
      if ! ./test-all-cards.sh "$(backend_port "$backend")" "$backend"; then
        status=1
      fi
    done
  } || status=$?

  if [ "$status" -ne 0 ]; then
    log "Docker service status"
    compose ps || true
    log "Recent Docker logs"
    compose logs --no-color --tail=80 nodejs php dotnet java || true
  fi

  compose down
  return "$status"
}

run_docker() {
  local action="${1:-help}"
  case "$action" in
    build)
      check_docker_env
      compose build
      ;;
    up|start)
      check_docker_env
      compose up --build -d frontend nodejs php dotnet java
      compose ps
      printf '\nFrontend: http://localhost:8000\n'
      ;;
    down|stop)
      compose down
      ;;
    logs)
      shift || true
      compose logs -f "$@"
      ;;
    ps|status)
      compose ps
      ;;
    smoke)
      check_docker_env
      run_docker_smoke
      ;;
    *)
      cat <<'USAGE'
Docker commands:
  ./run.sh docker build
  ./run.sh docker up
  ./run.sh docker smoke
  ./run.sh docker logs [service]
  ./run.sh docker ps
  ./run.sh docker down
USAGE
      ;;
  esac
}

cmd="${1:-help}"
case "$cmd" in
  help|-h|--help) usage ;;
  check) run_check ;;
  setup) run_setup "${2:-all}" ;;
  dev) run_dev "${2:-node}" ;;
  smoke) run_smoke "${2:-node}" ;;
  docker) shift; run_docker "$@" ;;
  *)
    usage >&2
    exit 2
    ;;
esac
