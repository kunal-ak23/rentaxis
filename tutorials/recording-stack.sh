#!/usr/bin/env bash
# Clean, LOCAL-ONLY stack for recording tutorials: its own database, a backend
# jar and a production web build, both built from `git archive HEAD` so a take
# records committed code, not whatever the working tree holds at the moment.
#
#   tutorials/recording-stack.sh build    # (re)build backend jar + web from HEAD
#   tutorials/recording-stack.sh start    # create DB if missing, start both (builds first if needed)
#   tutorials/recording-stack.sh seed [--reset] [--refresh-branding]  # seed "Oasis Crest Properties" (idempotent)
#   tutorials/recording-stack.sh stop     # stop only the processes this script started
#   tutorials/recording-stack.sh status
#   tutorials/recording-stack.sh snapshot|restore|drop-snapshot <tag>  # DB copy (restarts the backend)
#
# Everything lives under tutorials/work/stack/ (gitignored). Ports are
# overridable; the defaults stay clear of the user's stack (3000-3002/8081),
# the break-test stack (3003/8082) and the sim harness (8083).
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
stack="$repo/tutorials/work/stack"
src="$stack/src"
logs="$stack/logs"
pids="$stack/pids"

db_name=${TUTORIAL_DB:-rentaxis_tutorials}
db_host=${TUTORIAL_DB_HOST:-127.0.0.1}
db_port=${TUTORIAL_DB_PORT:-5432}
backend_port=${TUTORIAL_BACKEND_PORT:-8084}
web_port=${TUTORIAL_WEB_PORT:-3004}
backend_url="http://localhost:$backend_port"
web_url="http://localhost:$web_port"
seed_manifest=${TUTORIAL_SEED_MANIFEST:-"$repo/tutorials/work/seed/oasis-crest.out.json"}

case "$backend_port $web_port" in
  *3000*|*3001*|*3002*|*3003*|*8081*|*8082*|*8083*)
    echo "Refusing ports $backend_port/$web_port: reserved for other local stacks." >&2; exit 2 ;;
esac

mkdir -p "$logs" "$pids"

listening_pid() { lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1 || true; }

wait_for_gradle() {
  while pgrep -f "[G]radleWrapperMain|[G]radleWorkerMain" >/dev/null; do
    echo "waiting for another Gradle build to finish..."
    sleep 20
  done
}

build() {
  local sha
  sha=$(git -C "$repo" rev-parse --short=7 HEAD)
  echo "building recording stack from HEAD $sha"
  rm -rf "$src.new"
  mkdir -p "$src.new"
  git -C "$repo" archive HEAD backend web | tar -x -C "$src.new"
  # Local NextAuth secret: copied, never printed.
  cp "$repo/web/.env.local" "$src.new/web/.env.local"

  # Backend jar. No daemon, so no Gradle process outlives the build.
  wait_for_gradle
  (cd "$src.new/backend" && ./gradlew bootJar -x test --no-daemon --console=plain) > "$logs/build-backend.log" 2>&1 \
    || { echo "backend build failed, see $logs/build-backend.log" >&2; exit 1; }

  # Web dependencies: clone the repo's node_modules when the lockfile matches
  # (APFS copy-on-write, instant); otherwise install from the lockfile.
  if cmp -s "$repo/web/package-lock.json" "$src.new/web/package-lock.json" && [[ -d "$repo/web/node_modules" ]]; then
    cp -Rc "$repo/web/node_modules" "$src.new/web/node_modules" 2>/dev/null \
      || cp -R "$repo/web/node_modules" "$src.new/web/node_modules"
  else
    (cd "$src.new/web" && npm ci --no-audit --no-fund) > "$logs/build-web-deps.log" 2>&1
  fi
  # BACKEND_URL is baked into the rewrites at build time.
  (cd "$src.new/web" && BACKEND_URL="$backend_url" APP_GIT_SHA="$sha" NEXTAUTH_URL="$web_url" \
    NEXT_TELEMETRY_DISABLED=1 npx next build) > "$logs/build-web.log" 2>&1 \
    || { echo "web build failed, see $logs/build-web.log" >&2; exit 1; }

  rm -rf "$src"
  mv "$src.new" "$src"
  echo "$sha" > "$stack/BUILT_FROM"
  echo "built=$sha"
}

ensure_db() {
  if ! psql -h "$db_host" -p "$db_port" -U postgres -Atc "select 1 from pg_database where datname='$db_name'" postgres | grep -q 1; then
    createdb -h "$db_host" -p "$db_port" -U postgres "$db_name"
    echo "database created: $db_name"
  fi
}

start_backend() {
  if [[ -n "$(listening_pid "$backend_port")" ]]; then
    echo "backend: port $backend_port already in use (pid $(listening_pid "$backend_port")); not starting"; return
  fi
  local jar
  jar=$(ls "$src"/backend/build/libs/*-SNAPSHOT.jar 2>/dev/null | grep -v plain | head -1)
  [[ -n "$jar" ]] || { echo "no backend jar; run build" >&2; exit 1; }
  (
    set -a; source "$repo/.env.backend"; set +a
    export SPRING_DATASOURCE_URL="jdbc:postgresql://$db_host:$db_port/$db_name"
    export SERVER_PORT="$backend_port" SPRING_PROFILES_ACTIVE=tutorials
    # Never send anything outward from a recording: stub payments, blank
    # email/SMS and AI. Blob storage stays on the local Azurite of .env.backend.
    export RENTAXIS_GATEWAY_STUB_ENABLED=true
    export AZURE_COMMUNICATION_CONNECTION_STRING= AZURE_OPENAI_API_KEY= AZURE_OPENAI_ENDPOINT=
    export SPRING_JPA_SHOW_SQL=false RENTAXIS_RECOGNITION_JOB_CATCH_UP_ENABLED=false
    export APP_RENEWAL_PORTAL_BASE_URL="$web_url"
    cd "$src/backend"
    nohup java -Xmx2g -jar "$jar" > "$logs/backend.log" 2>&1 &
    echo $! > "$pids/backend.pid"
  )
  echo -n "backend: starting on $backend_port "
  for _ in $(seq 1 90); do
    if curl -fsS -o /dev/null "$backend_url/actuator/health" 2>/dev/null \
       || curl -s -o /dev/null -w '%{http_code}' "$backend_url/api/auth/login" 2>/dev/null | grep -qE '^(4|2)'; then
      echo "up"; return
    fi
    kill -0 "$(cat "$pids/backend.pid")" 2>/dev/null || { echo "died; see $logs/backend.log" >&2; exit 1; }
    sleep 2; echo -n "."
  done
  echo "timed out; see $logs/backend.log" >&2; exit 1
}

start_web() {
  if [[ -n "$(listening_pid "$web_port")" ]]; then
    echo "web: port $web_port already in use (pid $(listening_pid "$web_port")); not starting"; return
  fi
  [[ -d "$src/web/.next" ]] || { echo "no web build; run build" >&2; exit 1; }
  (
    cd "$src/web"
    export NEXTAUTH_URL="$web_url" AUTH_URL="$web_url" BACKEND_URL="$backend_url" \
      NODE_ENV=production NEXT_TELEMETRY_DISABLED=1
    nohup npx next start -p "$web_port" > "$logs/web.log" 2>&1 &
    echo $! > "$pids/web.pid"
  )
  echo -n "web: starting on $web_port "
  for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "$web_url/en/auth/login" 2>/dev/null; then echo "up"; return; fi
    sleep 1; echo -n "."
  done
  echo "timed out; see $logs/web.log" >&2; exit 1
}

# Seed the fictional demo company. Every endpoint and credential is set here
# explicitly: seed_demo_tenant.py otherwise falls back to the production values
# in web/e2e-prod/.env.local. The super admin is DataInitializer's local
# default (localhost-only test credential). The output file holds local test
# passwords: it stays in tutorials/work/ (gitignored) and is never printed.
seed() {
  local brand="$repo/tutorials/brand/oasis-crest"
  mkdir -p "$repo/tutorials/work/seed"
  PROD_BASE_URL="$backend_url" DEMO_WEB_BASE_URL="$web_url" \
  PROD_SUPERADMIN_EMAIL=admin@rentaxis.com PROD_SUPERADMIN_PASSWORD=admin123 \
  DEMO_TENANT_NAME="Oasis Crest Properties" DEMO_BRAND="Oasis Crest" \
  DEMO_EMAIL_DOMAIN=oasiscrest.example DEMO_ADMIN_NAME="Noura Al Suwaidi" \
  DEMO_MANAGER_PHONE=+971500009511 DEMO_GUARD_PHONE=+971500009512 \
  DEMO_BRAND_AR="قمة الواحة" \
  DEMO_ORG_ADDRESS="Office 1407, Crest Tower, Marasi Drive, Business Bay, Dubai, United Arab Emirates, P.O. Box 00000" \
  DEMO_ORG_PHONE="+971 4 000 0000" DEMO_ORG_TRN=100123456700003 \
  DEMO_ORG_LOGO="$brand/logo-mark.png" DEMO_ORG_STAMP="$brand/stamp.png" \
  DEMO_BUILDING_NAME="Crest Residences — Block A" DEMO_BUILDING_NAME_AR="مساكن القمة — المبنى أ" \
  DEMO_MAINTENANCE_DESK="Oasis Crest Maintenance Desk" DEMO_MAINTENANCE_EMAIL=maintenance@oasiscrest.example \
  DEMO_OUTPUT_FILE="$seed_manifest" \
    python3 "$repo/scripts/seed_demo_tenant.py" --redact-credentials "$@"
}

stop_one() {
  local name=$1 port=$2 file="$pids/$1.pid"
  [[ -f "$file" ]] || { echo "$name: not started by this script"; return; }
  local pid; pid=$(cat "$file")
  if kill -0 "$pid" 2>/dev/null; then
    # npx wraps next-server; stop the whole group we started, then whatever
    # still listens on OUR port if (and only if) it descends from that pid.
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
  fi
  local lp; lp=$(listening_pid "$port")
  if [[ -n "$lp" ]] && ps -o command= -p "$lp" | grep -qE "next-server|$src"; then kill -TERM "$lp" 2>/dev/null || true; fi
  rm -f "$file"
  echo "$name: stopped"
}

# Database snapshots, so a tutorial whose flow cannot be undone in the app
# (posting a contract, terminating one) starts every run from the same state:
# its proof, its capture and any retake. A snapshot is a template copy of the
# recording database (`<db>_snap_<tag>`); restoring stops only the backend
# this script started, recreates the database from the snapshot and starts the
# backend again. The web server stays up. Tutorial databases on localhost only.
snap_name() {
  [[ "$1" =~ ^[a-z0-9_]{1,32}$ ]] || { echo "snapshot tag must match [a-z0-9_]{1,32}: $1" >&2; exit 2; }
  echo "${db_name}_snap_$1"
}
assert_tutorial_db() {
  [[ "$db_name" =~ ^rentaxis_tutorials[a-z0-9_]*$ ]] || { echo "Refusing: $db_name is not a tutorial database." >&2; exit 2; }
  case "$db_host" in 127.0.0.1|localhost) ;; *) echo "Refusing: database host $db_host is not local." >&2; exit 2 ;; esac
}
db_exists() {
  [[ "$(psql -h "$db_host" -p "$db_port" -U postgres -Atc "select 1 from pg_database where datname='$1'" postgres)" == 1 ]]
}
stop_backend_and_wait() {
  local pid; pid=$(cat "$pids/backend.pid" 2>/dev/null || true)
  stop_one backend "$backend_port"
  for _ in $(seq 1 60); do
    [[ -z "$(listening_pid "$backend_port")" ]] && { [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; } && return
    sleep 1
  done
  echo "backend did not stop" >&2; exit 1
}
snapshot() {
  assert_tutorial_db
  local snap; snap=$(snap_name "$1")
  stop_backend_and_wait
  if db_exists "$snap"; then dropdb -h "$db_host" -p "$db_port" -U postgres "$snap"; fi
  createdb -h "$db_host" -p "$db_port" -U postgres -T "$db_name" "$snap"
  echo "snapshot=$snap"
  start_backend
}
restore() {
  assert_tutorial_db
  local snap; snap=$(snap_name "$1")
  db_exists "$snap" || { echo "no snapshot $snap" >&2; exit 1; }
  stop_backend_and_wait
  dropdb -h "$db_host" -p "$db_port" -U postgres --force "$db_name"
  createdb -h "$db_host" -p "$db_port" -U postgres -T "$snap" "$db_name"
  echo "restored=$snap"
  start_backend
}
drop_snapshot() {
  assert_tutorial_db
  local snap; snap=$(snap_name "$1")
  if db_exists "$snap"; then dropdb -h "$db_host" -p "$db_port" -U postgres "$snap"; echo "dropped=$snap"; fi
}

status() {
  echo "built_from=$(cat "$stack/BUILT_FROM" 2>/dev/null || echo none) head=$(git -C "$repo" rev-parse --short=7 HEAD)"
  echo "database=$db_name@$db_host:$db_port"
  for pair in "backend:$backend_port" "web:$web_port"; do
    local name=${pair%%:*} port=${pair##*:}
    local lp; lp=$(listening_pid "$port")
    if [[ -n "$lp" ]]; then echo "$name=http://localhost:$port listening (pid $lp)"; else echo "$name=http://localhost:$port down"; fi
  done
}

case "${1:-}" in
  build) build ;;
  start)
    if [[ ! -f "$stack/BUILT_FROM" ]]; then build; fi
    if [[ "$(cat "$stack/BUILT_FROM")" != "$(git -C "$repo" rev-parse --short=7 HEAD)" ]]; then
      echo "note: stack built from $(cat "$stack/BUILT_FROM"), HEAD is $(git -C "$repo" rev-parse --short=7 HEAD); run '$0 build' to refresh"
    fi
    ensure_db; start_backend; start_web; status ;;
  stop) stop_one web "$web_port"; stop_one backend "$backend_port" ;;
  seed) shift; seed "$@" ;;
  snapshot) snapshot "${2:?snapshot tag}" ;;
  restore) restore "${2:?snapshot tag}" ;;
  drop-snapshot) drop_snapshot "${2:?snapshot tag}" ;;
  status) status ;;
  *) echo "Usage: $0 build|start|seed|stop|status|snapshot <tag>|restore <tag>|drop-snapshot <tag>" >&2; exit 2 ;;
esac
