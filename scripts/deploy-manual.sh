#!/usr/bin/env bash
# Manual production deploy — the same steps as .github/workflows/deploy.yml,
# for when GitHub Actions cannot run. Run from the repo root on a machine with
# Docker (buildx) and SSH access to the VM as the deploy user.
#
#   NEXT_PUBLIC_GOOGLE_MAPS_API_KEY=… scripts/deploy-manual.sh            # build + deploy
#   SKIP_BUILD=1 scripts/deploy-manual.sh                                 # reuse /tmp/backend.tar.gz + /tmp/web.tar.gz
#
# The VM keeps its /opt/rentaxis/.env from earlier deploys; this script does
# not regenerate it (the workflow does, from GitHub secrets).
set -euo pipefail

VM_USER=${VM_USER:-rentaxis}
VM_IP=${VM_IP:-20.74.148.215}
REMOTE_DIR=${REMOTE_DIR:-/opt/rentaxis}
DOMAIN=${DOMAIN:-rentaxis.uaenorth.cloudapp.azure.com}
SSH_OPTS=${SSH_OPTS:--o ConnectTimeout=10}
TARGET="$VM_USER@$VM_IP"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

if [[ -z "${SKIP_BUILD:-}" ]]; then
  # Baked into the web image at build time. Not a repository secret today, so CI
  # has always built with it empty; leave it empty to match production.
  NEXT_PUBLIC_GOOGLE_MAPS_API_KEY=${NEXT_PUBLIC_GOOGLE_MAPS_API_KEY:-}
  GIT_SHA=$(git rev-parse --short=7 HEAD)
  say "Building backend image (linux/amd64) at $GIT_SHA"
  docker buildx build --platform linux/amd64 -t rentaxis-backend:latest --load --build-arg GIT_SHA="$GIT_SHA" ./backend
  say "Building web image (linux/amd64)"
  docker buildx build --platform linux/amd64 -t rentaxis-web:latest --load \
    --build-arg NEXT_PUBLIC_API_URL="https://$DOMAIN" \
    --build-arg NEXT_PUBLIC_GOOGLE_MAPS_API_KEY="$NEXT_PUBLIC_GOOGLE_MAPS_API_KEY" \
    --build-arg APP_GIT_SHA="$GIT_SHA" \
    ./web
  say "Saving images"
  docker save rentaxis-backend:latest | gzip > /tmp/backend.tar.gz
  docker save rentaxis-web:latest | gzip > /tmp/web.tar.gz
  ls -la /tmp/backend.tar.gz /tmp/web.tar.gz
fi

say "Database backup on the VM (before Liquibase runs the new changesets)"
# shellcheck disable=SC2029
ssh $SSH_OPTS "$TARGET" "cd $REMOTE_DIR && set -a && . ./.env && set +a && \
  mkdir -p backups && \
  docker compose exec -T postgres pg_dump -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Fc > backups/pre-accounting-v2-\$(date +%Y%m%d-%H%M%S).dump && \
  ls -la backups | tail -3"

say "Uploading images and compose file"
scp $SSH_OPTS /tmp/backend.tar.gz /tmp/web.tar.gz "$TARGET:$REMOTE_DIR/"
scp $SSH_OPTS docker-compose.prod.yml "$TARGET:$REMOTE_DIR/docker-compose.yml"

say "Loading images and restarting"
ssh $SSH_OPTS "$TARGET" "cd $REMOTE_DIR && \
  docker load < backend.tar.gz && docker load < web.tar.gz && \
  docker compose pull caddy postgres && \
  docker compose up -d --remove-orphans && \
  docker image prune -f >/dev/null && \
  docker compose ps"

say "Waiting for the backend (Liquibase 81–89 run on first boot)"
for i in $(seq 1 60); do
  if curl -fsS -m 5 "https://$DOMAIN/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    echo "backend UP after $((i*5))s"; break
  fi
  sleep 5
  [[ $i -eq 60 ]] && { echo "backend did not report UP in 5 min — check: ssh $TARGET 'cd $REMOTE_DIR && docker compose logs --tail=200 backend'"; exit 1; }
done

say "Version now serving"
curl -fsS -m 10 "https://$DOMAIN/actuator/info" || true; echo

say "Changesets applied (last five)"
ssh $SSH_OPTS "$TARGET" "cd $REMOTE_DIR && set -a && . ./.env && set +a && \
  docker compose exec -T postgres psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atc \
  \"select id, dateexecuted from databasechangelog order by orderexecuted desc limit 5;\""

say "Done. Next: run the prod Playwright suite:  cd web && npx playwright test --config=e2e-prod/playwright.config.ts \"global-setup\" \"tests/[0-9]\""
